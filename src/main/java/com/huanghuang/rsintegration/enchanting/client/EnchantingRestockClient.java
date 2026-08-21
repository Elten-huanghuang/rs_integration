package com.huanghuang.rsintegration.enchanting.client;

import com.huanghuang.rsintegration.enchanting.EnchantingRestockRequestPacket;
import com.huanghuang.rsintegration.enchanting.EnchantingRestockResultPacket;
import com.huanghuang.rsintegration.mixin.jei.BookmarkOverlayAccessor;
import com.huanghuang.rsintegration.network.RSJeiPlugin;
import com.huanghuang.rsintegration.network.packet.NetworkHandler;
import com.mojang.blaze3d.systems.RenderSystem;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.gui.bookmarks.IngredientBookmark;
import mezz.jei.gui.overlay.bookmarks.BookmarkOverlay;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.EnchantmentScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.lwjgl.glfw.GLFW;

public final class EnchantingRestockClient {
    private static EnchantingRestockResultPacket last;
    private static long visibleUntil;
    private static boolean initialized;

    private EnchantingRestockClient() {}

    public static void init() {
        if (initialized) return;
        initialized = true;
        MinecraftForge.EVENT_BUS.register(EnchantingRestockClient.class);
    }

    public static void accept(EnchantingRestockResultPacket result) {
        last = result;
        visibleUntil = System.currentTimeMillis() + 4500L;
        if (result.missing() > 0) bookmarkLapis();
        var player = Minecraft.getInstance().player;
        if (player != null) {
            player.playSound(result.status() == EnchantingRestockResultPacket.Status.COMPLETE
                    ? SoundEvents.EXPERIENCE_ORB_PICKUP
                    : SoundEvents.NOTE_BLOCK_BASS.value(), 0.8F, 1.0F);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void keyPressed(ScreenEvent.KeyPressed.Pre event) {
        if (!(event.getScreen() instanceof EnchantmentScreen)
                || event.getKeyCode() != GLFW.GLFW_KEY_SPACE
                || event.getModifiers() != 0) return;
        NetworkHandler.CHANNEL.sendToServer(new EnchantingRestockRequestPacket());
        event.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void render(ScreenEvent.Render.Post event) {
        if (last == null || System.currentTimeMillis() >= visibleUntil
                || !(event.getScreen() instanceof EnchantmentScreen)) return;
        GuiGraphics graphics = event.getGuiGraphics();
        Minecraft minecraft = Minecraft.getInstance();
        Component message = message(last);
        int width = Math.max(180, minecraft.font.width(message) + 44);
        int x = (event.getScreen().width - width) / 2;
        int y = 8;
        boolean success = last.status() == EnchantingRestockResultPacket.Status.COMPLETE;
        graphics.pose().pushPose();
        graphics.pose().translate(0, 0, 1000);
        RenderSystem.disableDepthTest();
        graphics.fill(x - 1, y - 1, x + width + 1, y + 29, 0xFF000000);
        graphics.fill(x, y, x + width, y + 28, success ? 0xE0185A35 : 0xE08A321F);
        graphics.fill(x, y, x + 4, y + 28, success ? 0xFF55FF88 : 0xFFFFB04A);
        ItemStack lapis = new ItemStack(Items.LAPIS_LAZULI);
        graphics.renderItem(lapis, x + 11, y + 6);
        if (last.missing() > 0) {
            graphics.renderItemDecorations(minecraft.font, lapis, x + 11, y + 6,
                    "-" + last.missing());
        }
        graphics.drawString(minecraft.font, message, x + 34, y + 10, 0xFFFFFFFF, true);
        RenderSystem.enableDepthTest();
        graphics.pose().popPose();
    }

    private static Component message(EnchantingRestockResultPacket result) {
        return switch (result.status()) {
            case COMPLETE -> result.inserted() == 0
                    ? Component.translatable("rsi.enchanting.restock.full")
                    : Component.translatable("rsi.enchanting.restock.complete", result.inserted());
            case PARTIAL -> Component.translatable(
                    "rsi.enchanting.restock.partial", result.inserted(), result.missing());
            case NO_NETWORK -> Component.translatable("rsi.enchanting.restock.no_network");
            case NO_PERMISSION -> Component.translatable("rsi.enchanting.restock.no_permission");
            case INVALID -> Component.translatable("rsi.enchanting.restock.invalid");
        };
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void bookmarkLapis() {
        var runtime = RSJeiPlugin.getRuntime();
        if (runtime == null || !(runtime.getBookmarkOverlay() instanceof BookmarkOverlay overlay)) return;
        var typed = runtime.getIngredientManager().createTypedIngredient(
                VanillaTypes.ITEM_STACK, new ItemStack(Items.LAPIS_LAZULI));
        if (typed.isEmpty()) return;
        var bookmark = IngredientBookmark.create(typed.get(), runtime.getIngredientManager());
        ((BookmarkOverlayAccessor) overlay).rsIntegration$getBookmarkList().add(bookmark);
    }
}
