package com.huanghuang.rsintegration.reforging.client;

import com.huanghuang.rsintegration.mixin.jei.BookmarkOverlayAccessor;
import com.huanghuang.rsintegration.network.RSJeiPlugin;
import com.huanghuang.rsintegration.network.packet.NetworkHandler;
import com.huanghuang.rsintegration.reforging.ReforgingRestockRequestPacket;
import com.huanghuang.rsintegration.reforging.ReforgingRestockResultPacket;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.gui.bookmarks.IngredientBookmark;
import mezz.jei.gui.overlay.bookmarks.BookmarkOverlay;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.registries.ForgeRegistries;
import org.lwjgl.glfw.GLFW;

import java.util.Set;

public final class ReforgingRestockClient {
    private static final Set<String> SUPPORTED_SCREENS = Set.of(
            "dev.shadowsoffire.apotheosis.adventure.affix.reforging.ReforgingScreen",
            "com.ianm1647.ancientreforging.screen.AncientReforgingScreen");
    private static boolean initialized;
    private static ReforgingRestockResultPacket last;
    private static long visibleUntil;

    private ReforgingRestockClient() {}

    public static void init() {
        if (!initialized) {
            initialized = true;
            MinecraftForge.EVENT_BUS.register(ReforgingRestockClient.class);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void keyPressed(ScreenEvent.KeyPressed.Pre event) {
        if (!SUPPORTED_SCREENS.contains(event.getScreen().getClass().getName())
                || event.getKeyCode() != GLFW.GLFW_KEY_SPACE
                || event.getModifiers() != 0) return;
        NetworkHandler.CHANNEL.sendToServer(new ReforgingRestockRequestPacket());
    }

    public static void accept(ReforgingRestockResultPacket result) {
        Minecraft minecraft = Minecraft.getInstance();
        var player = minecraft.player;
        if (player == null) return;

        last = result;
        visibleUntil = System.currentTimeMillis() + 4500;
        if (result.missing() > 0) bookmarkSigil();
        player.playSound(result.missing() == 0
                ? SoundEvents.EXPERIENCE_ORB_PICKUP : SoundEvents.NOTE_BLOCK_BASS.value(), 0.8F, 1.0F);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void render(ScreenEvent.Render.Post event) {
        if (last == null || System.currentTimeMillis() >= visibleUntil
                || !SUPPORTED_SCREENS.contains(event.getScreen().getClass().getName())) return;
        Minecraft minecraft = Minecraft.getInstance();
        GuiGraphics graphics = event.getGuiGraphics();
        Component message = message(last);
        ItemStack sigil = sigilStack();
        boolean showIcon = !sigil.isEmpty() && (last.inserted() > 0 || last.missing() > 0);
        int iconWidth = showIcon ? 22 : 0;
        int width = Math.max(180, minecraft.font.width(message) + 24 + iconWidth);
        int x = (event.getScreen().width - width) / 2;
        int y = 8;
        boolean complete = last.status() == ReforgingRestockResultPacket.Status.COMPLETE;
        graphics.pose().pushPose();
        graphics.pose().translate(0, 0, 1000);
        RenderSystem.disableDepthTest();
        graphics.fill(x - 1, y - 1, x + width + 1, y + 29, 0xFF000000);
        graphics.fill(x, y, x + width, y + 28, complete ? 0xE0185A35 : 0xE08A321F);
        graphics.fill(x, y, x + 4, y + 28, complete ? 0xFF55FF88 : 0xFFFFB04A);
        int textX = x + 12;
        if (iconWidth > 0) {
            graphics.renderItem(sigil, textX, y + 6);
            String decoration = last.inserted() > 0 && last.missing() == 0
                    ? "+" + last.inserted()
                    : (last.missing() > 0 ? "-" + last.missing() : null);
            graphics.renderItemDecorations(minecraft.font, sigil, textX, y + 6,
                    decoration);
            textX += iconWidth;
        }
        graphics.drawString(minecraft.font, message, textX, y + 10, 0xFFFFFFFF, true);
        RenderSystem.enableDepthTest();
        graphics.pose().popPose();
    }

    private static Component message(ReforgingRestockResultPacket result) {
        return switch (result.status()) {
            case COMPLETE -> Component.translatable("rsi.reforging.restock.complete", result.inserted());
            case PARTIAL -> Component.translatable("rsi.reforging.restock.partial", result.inserted(), result.missing());
            case NO_NETWORK -> Component.translatable("rsi.reforging.restock.no_network", result.missing());
            case NO_PERMISSION -> Component.translatable("rsi.reforging.restock.no_permission", result.missing());
            case INVALID -> Component.translatable("rsi.reforging.restock.invalid");
        };
    }

    private static ItemStack sigilStack() {
        var item = ForgeRegistries.ITEMS.getValue(
                new ResourceLocation("apotheosis", "sigil_of_rebirth"));
        return item == null ? ItemStack.EMPTY : new ItemStack(item);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void bookmarkSigil() {
        var runtime = RSJeiPlugin.getRuntime();
        var item = ForgeRegistries.ITEMS.getValue(new ResourceLocation("apotheosis", "sigil_of_rebirth"));
        if (runtime == null || item == null
                || !(runtime.getBookmarkOverlay() instanceof BookmarkOverlay overlay)) return;
        var typed = runtime.getIngredientManager().createTypedIngredient(VanillaTypes.ITEM_STACK, new ItemStack(item));
        if (typed.isEmpty()) return;
        var bookmark = IngredientBookmark.create(typed.get(), runtime.getIngredientManager());
        ((BookmarkOverlayAccessor) overlay).rsIntegration$getBookmarkList().add(bookmark);
    }
}
