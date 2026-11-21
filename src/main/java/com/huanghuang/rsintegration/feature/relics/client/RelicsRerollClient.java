package com.huanghuang.rsintegration.feature.relics.client;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.client.recipe.RecipeBrowserBridge;
import com.huanghuang.rsintegration.feature.relics.client.RelicRerollHold.Roll;
import com.mojang.blaze3d.platform.InputConstants;
import it.hurts.sskirillss.relics.client.screen.description.AbilityDescriptionScreen;
import it.hurts.sskirillss.relics.client.screen.description.misc.DescriptionUtils;
import it.hurts.sskirillss.relics.client.screen.description.widgets.ability.RerollActionWidget;
import it.hurts.sskirillss.relics.items.relics.base.IRelicItem;
import it.hurts.sskirillss.relics.items.relics.base.data.leveling.AbilityData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.lwjgl.glfw.GLFW;

import java.util.Map;

public final class RelicsRerollClient {
    private static final int BLOCKED_MODIFIERS = GLFW.GLFW_MOD_CONTROL | GLFW.GLFW_MOD_ALT | GLFW.GLFW_MOD_SUPER;
    private static final RelicRerollHold HOLD = new RelicRerollHold();
    private static AbilityDescriptionScreen activeScreen;
    private static Item targetItem;

    private RelicsRerollClient() {}

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void keyPressed(ScreenEvent.KeyPressed.Pre event) {
        // 使用 Shift 查看遗物详情时，可以直接按空格重掷。
        if (event.getKeyCode() != GLFW.GLFW_KEY_SPACE || (event.getModifiers() & BLOCKED_MODIFIERS) != 0
                || !(event.getScreen() instanceof AbilityDescriptionScreen screen) || hasTextFocus(screen)) return;

        event.setCanceled(true);
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || player.containerMenu.containerId != screen.getContainer()) return;
        if (activeScreen != screen) clearTarget();
        if (!HOLD.press()) return;
        activeScreen = screen;
        targetItem = DescriptionUtils.gatherRelicStack(player, screen.getSlot()).getItem();
        advance(minecraft);
    }

    @SubscribeEvent
    public static void keyReleased(ScreenEvent.KeyReleased.Pre event) {
        if (event.getKeyCode() == GLFW.GLFW_KEY_SPACE) HOLD.release();
    }

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || (activeScreen == null && !HOLD.isHeld())) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (!InputConstants.isKeyDown(minecraft.getWindow().getWindow(), GLFW.GLFW_KEY_SPACE)) HOLD.release();
        if (activeScreen != null) advance(minecraft);
    }

    private static void advance(Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        if (minecraft.screen != activeScreen || player == null
                || player.containerMenu.containerId != activeScreen.getContainer()) {
            clearTarget();
            return;
        }
        if (!HOLD.isActive()) return;
        if (!minecraft.isWindowActive() || hasTextFocus(activeScreen)
                || Screen.hasControlDown() || Screen.hasAltDown()) {
            HOLD.cancel();
        }

        ItemStack stack = DescriptionUtils.gatherRelicStack(player, activeScreen.getSlot());
        if (stack.getItem() != targetItem || !(targetItem instanceof IRelicItem relic)
                || activeScreen.rerollButton == null) {
            clearTarget();
            return;
        }

        RerollActionWidget button = activeScreen.rerollButton;
        String ability = button.getAbility();
        AbilityData data = relic.getAbilityData(ability);
        if (data == null || data.getStats().isEmpty()) {
            HOLD.clearTarget();
            return;
        }
        Map<String, Double> stats = relic.getAbilityInitialValues(stack, ability);
        // 只用已同步的属性判定星级，避免触发 Relics 在缺失属性时本地随机初始化。
        boolean hasStats = stats.keySet().containsAll(data.getStats().keySet());
        int quality = hasStats ? relic.getAbilityQuality(stack, ability) : 0;
        int cost = relic.getRerollRequiredExperience(ability);
        Roll roll = new Roll(stats, quality, relic.getMaxQuality(), player.experienceLevel);
        boolean canReroll = button.active && button.visible && !button.isLocked();
        var action = HOLD.tick(roll, canReroll, cost);
        switch (action) {
            case REROLL -> {
                button.playDownSound(minecraft.getSoundManager());
                button.onPress();
            }
            case FULL_QUALITY -> player.displayClientMessage(Component.translatable("rsi.relics.full_quality"), true);
            case UNAVAILABLE, TIMED_OUT -> {
                RSIntegrationMod.LOGGER.debug("[RSI-Relics] Reroll stopped for {} ({}), level={}, cost={}",
                        ability, action, roll.experienceLevel(), cost);
                player.displayClientMessage(Component.translatable("rsi.relics.reroll_unavailable"), true);
            }
            case NONE -> { }
        }
    }

    @SubscribeEvent
    public static void logout(ClientPlayerNetworkEvent.LoggingOut event) {
        HOLD.release();
        clearTarget();
    }

    private static boolean hasTextFocus(Screen screen) {
        return screen.getFocused() instanceof EditBox || RecipeBrowserBridge.hasSearchFocus();
    }

    private static void clearTarget() {
        HOLD.clearTarget();
        activeScreen = null;
        targetItem = null;
    }
}
