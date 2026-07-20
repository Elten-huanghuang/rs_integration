package com.huanghuang.rsintegration.villager.client;

import com.huanghuang.rsintegration.mixin.jei.BookmarkOverlayAccessor;
import com.huanghuang.rsintegration.mixin.minecraft.MerchantScreenAccessor;
import com.huanghuang.rsintegration.network.packet.NetworkHandler;
import com.huanghuang.rsintegration.villager.VillagerRestockRequestPacket;
import com.huanghuang.rsintegration.network.RSJeiPlugin;
import com.huanghuang.rsintegration.villager.VillagerRestockResultPacket;
import com.mojang.blaze3d.systems.RenderSystem;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.gui.bookmarks.IngredientBookmark;
import mezz.jei.gui.overlay.bookmarks.BookmarkOverlay;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.MerchantScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

public final class VillagerRestockClient {
    private static VillagerRestockResultPacket last;
    private static long visibleUntil;
    private static boolean initialized;

    private VillagerRestockClient() {}

    public static void init() {
        if (!initialized) {
            initialized=true;
            MinecraftForge.EVENT_BUS.register(VillagerRestockClient.class);
        }
    }

    public static void accept(VillagerRestockResultPacket result) {
        last=result;
        visibleUntil=System.currentTimeMillis()+4500;
        for (var missing:result.missing()) bookmark(missing.stack());
        var player=Minecraft.getInstance().player;
        if (player != null) player.playSound(result.missing().isEmpty()
                ? SoundEvents.EXPERIENCE_ORB_PICKUP : SoundEvents.NOTE_BLOCK_BASS.value(), 0.8F, 1.0F);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void bookmark(ItemStack stack) {
        var runtime=RSJeiPlugin.getRuntime();
        if (runtime == null || !(runtime.getBookmarkOverlay() instanceof BookmarkOverlay overlay)) return;
        var typed=runtime.getIngredientManager().createTypedIngredient(VanillaTypes.ITEM_STACK, stack);
        if (typed.isEmpty()) return;
        var bookmark=IngredientBookmark.create(typed.get(), runtime.getIngredientManager());
        ((BookmarkOverlayAccessor) overlay).rsIntegration$getBookmarkList().add(bookmark);
    }

    @SubscribeEvent(priority=EventPriority.HIGHEST)
    public static void keyPressed(ScreenEvent.KeyPressed.Pre event) {
        if (!(event.getScreen() instanceof MerchantScreen screen)
                || event.getKeyCode() != org.lwjgl.glfw.GLFW.GLFW_KEY_SPACE
                || event.getModifiers() != 0) return;
        int offer=((MerchantScreenAccessor) screen).rsIntegration$getShopItem();
        NetworkHandler.CHANNEL.sendToServer(new VillagerRestockRequestPacket(offer));
        event.setCanceled(true);
    }
    @SubscribeEvent(priority=EventPriority.LOWEST)
    public static void render(ScreenEvent.Render.Post event) {
        if (last == null || System.currentTimeMillis() >= visibleUntil
                || !(event.getScreen() instanceof MerchantScreen)) return;
        GuiGraphics gfx=event.getGuiGraphics();
        Minecraft mc=Minecraft.getInstance();
        Component message=message(last);
        int icons=last.missing().size();
        int width=Math.max(180, mc.font.width(message)+24+icons*20);
        int x=(event.getScreen().width-width)/2;
        int y=8;
        int color=last.missing().isEmpty() ? 0xE0185A35 : 0xE08A321F;
        gfx.pose().pushPose();
        gfx.pose().translate(0, 0, 1000);
        RenderSystem.disableDepthTest();
        gfx.fill(x-1, y-1, x+width+1, y+29, 0xFF000000);
        gfx.fill(x, y, x+width, y+28, color);
        gfx.fill(x, y, x+4, y+28, last.missing().isEmpty() ? 0xFF55FF88 : 0xFFFFB04A);
        int tx=x+12;
        for (var missing:last.missing()) {
            gfx.renderItem(missing.stack(), tx, y+6);
            gfx.renderItemDecorations(mc.font, missing.stack(), tx, y+6, "-" + missing.count());
            tx+=20;
        }
        gfx.drawString(mc.font, message, tx+2, y+10, 0xFFFFFFFF, true);
        RenderSystem.enableDepthTest();
        gfx.pose().popPose();
    }

    private static Component message(VillagerRestockResultPacket result) {
        return switch (result.status()) {
            case COMPLETE -> Component.translatable("rsi.villager.restock.complete",
                    result.inventoryCount(), result.rsCount());
            case PARTIAL -> Component.translatable("rsi.villager.restock.partial");
            case NO_NETWORK -> Component.translatable("rsi.villager.restock.no_network");
            case NO_PERMISSION -> Component.translatable("rsi.villager.restock.no_permission");
            case INVALID -> Component.translatable("rsi.villager.restock.invalid");
        };
    }
}
