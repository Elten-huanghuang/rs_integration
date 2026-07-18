package com.huanghuang.rsintegration.mods.distantworlds.client;

import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.mods.distantworlds.LithumAltarRecipeResolver;
import com.huanghuang.rsintegration.mods.distantworlds.LithumAltarStatusSnapshot;
import com.huanghuang.rsintegration.reflection.probes.DistantWorldsReflection;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.client.gui.overlay.IGuiOverlay;
import net.minecraft.world.phys.BlockHitResult;

import java.util.List;

@OnlyIn(Dist.CLIENT)
public final class LithumAltarHUDOverlay implements IGuiOverlay {
    public static final LithumAltarHUDOverlay INSTANCE = new LithumAltarHUDOverlay();
    private static final List<int[]> PEDESTALS = List.of(
            new int[]{-2, -1}, new int[]{-1, -2}, new int[]{1, -2}, new int[]{2, -1},
            new int[]{2, 1}, new int[]{1, 2}, new int[]{-1, 2}, new int[]{-2, 1});

    private LithumAltarHUDOverlay() {}

    @Override
    public void render(ForgeGui gui, GuiGraphics graphics, float partialTick, int width, int height) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.options.hideGui || mc.screen != null
                || !RSIntegrationConfig.ENABLE_DISTANT_WORLDS_HUD.get()) return;
        if (!(mc.hitResult instanceof BlockHitResult hit) || DistantWorldsReflection.lithumCoreBlockClass == null
                || !DistantWorldsReflection.lithumCoreBlockClass.isInstance(
                mc.level.getBlockState(hit.getBlockPos()).getBlock())) return;

        BlockEntity core = mc.level.getBlockEntity(hit.getBlockPos());
        if (core == null || DistantWorldsReflection.lithumCoreBEClass == null
                || !DistantWorldsReflection.lithumCoreBEClass.isInstance(core)) return;

        int x = Math.min(width - 190, width / 2 + 24);
        int y = Math.max(12, height / 2 - 64);
        int line = 12;
        Font font = mc.font;
        int white = 0xFFFFFF;
        int good = 0x55FF55;
        int warn = 0xFFFF55;
        int bad = 0xFF5555;
        LithumAltarStatusSnapshot snapshot = LithumAltarStatusCache.current();
        boolean matchingSnapshot = snapshot != null
                && snapshot.dimension().equals(mc.level.dimension().location())
                && snapshot.pos().equals(hit.getBlockPos());
        String current = matchingSnapshot
                ? snapshot.currentRecipe() : core.getPersistentData().getString("CurrentRecipe");
        double energy = matchingSnapshot
                ? snapshot.currentEnergy() : core.getPersistentData().getDouble("CurrentEnergy");
        double maxEnergy = matchingSnapshot
                ? snapshot.maxEnergy() : core.getPersistentData().getDouble("MaxEnergy");
        double recovery = matchingSnapshot
                ? snapshot.recovery() : core.getPersistentData().getDouble("Recovery");
        var definition = LithumAltarRecipeResolver.resolveCurrentRecipe(current);
        String state = current.isEmpty()
                ? Component.translatable("rsi.distant_worlds.hud.idle").getString()
                : Component.translatable("rsi.distant_worlds.hud.working").getString();
        int stateColor = current.isEmpty() ? white : (energy >= maxEnergy && maxEnergy > 0 ? good : warn);

        graphics.drawString(font, Component.translatable("rsi.distant_worlds.hud.title"), x, y, 0xFFAA00, true);
        y += line;
        graphics.drawString(font, Component.translatable("rsi.distant_worlds.hud.state", state), x, y, stateColor, false);
        y += line;
        if (!current.isEmpty()) {
            String label = definition == null ? current : definition.output().getHoverName().getString();
            graphics.drawString(font, Component.translatable("rsi.distant_worlds.hud.recipe", label), x, y, white, false);
            y += line;
        }
        graphics.drawString(font, Component.translatable("rsi.distant_worlds.hud.energy",
                        String.format("%.0f", energy), String.format("%.0f", maxEnergy)),
                x, y, energy < maxEnergy && !current.isEmpty() ? warn : good, false);
        y += line;
        graphics.drawString(font, Component.translatable("rsi.distant_worlds.hud.recovery",
                String.format("%.0f", recovery)), x, y, white, false);
        y += line + 2;

        graphics.drawString(font, Component.translatable("rsi.distant_worlds.hud.pedestals"), x, y, white, false);
        y += line;
        int shown = 0;
        for (int[] offset : PEDESTALS) {
            BlockEntity pedestal = mc.level.getBlockEntity(hit.getBlockPos().offset(offset[0], 0, offset[1]));
            ItemStack stack = pedestal == null ? ItemStack.EMPTY : getSlot(pedestal, 0);
            String text = stack.isEmpty() ? "-"
                    : stack.getHoverName().getString() + " x" + stack.getCount();
            graphics.drawString(font, Component.literal((shown + 1) + ". " + text),
                    x, y, stack.isEmpty() ? bad : white, false);
            y += line;
            shown++;
            if (shown == 4) {
                x += 92;
                y -= line * 4;
            }
        }
    }

    private static ItemStack getSlot(BlockEntity be, int slot) {
        var capability = be.getCapability(net.minecraftforge.common.capabilities.ForgeCapabilities.ITEM_HANDLER, null);
        return capability.resolve().map(handler -> handler.getStackInSlot(slot)).orElse(ItemStack.EMPTY);
    }
}
