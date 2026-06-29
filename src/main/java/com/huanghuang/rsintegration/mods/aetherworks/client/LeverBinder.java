package com.huanghuang.rsintegration.mods.aetherworks.client;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import javax.annotation.Nullable;

@OnlyIn(Dist.CLIENT)
public final class LeverBinder {

    private static final int MEDIAN_MARGIN = 50;

    @Nullable private static BlockPos boundLeverPos;
    private static int tempMin;
    private static int tempMax = 3000;
    private static boolean leverControlEnabled = true;
    private static boolean autoRefillEnabled = true;

    private static ItemStack lastTrackedItem = ItemStack.EMPTY;
    private static ItemStack lastSeenItem = ItemStack.EMPTY;
    private static boolean justRefilled;

    private LeverBinder() {}

    // ---- Public API ----

    public static boolean isBound() { return boundLeverPos != null; }
    @Nullable public static BlockPos getBoundLever() { return boundLeverPos; }
    public static int getTempMin() { return tempMin; }
    public static int getTempMax() { return tempMax; }
    public static void setTempRange(int min, int max) { tempMin = min; tempMax = max; }
    public static boolean isLeverControlEnabled() { return leverControlEnabled; }
    public static void setLeverControlEnabled(boolean v) { leverControlEnabled = v; }
    public static boolean isAutoRefillEnabled() { return autoRefillEnabled; }
    public static void setAutoRefill(boolean v) { autoRefillEnabled = v; }

    private static boolean isLeverActuallyOn() {
        if (boundLeverPos == null) return false;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return false;
        return mc.level.getBlockState(boundLeverPos).getValue(LeverBlock.POWERED);
    }

    // ---- Temp auto-detect from nearby anvil ----

    private static boolean tryAutoSetTempFromAnvil(Minecraft mc) {
        for (int dx = -5; dx <= 5; dx++)
            for (int dy = -3; dy <= 3; dy++)
                for (int dz = -5; dz <= 5; dz++) {
                    BlockPos pos = (boundLeverPos != null ? boundLeverPos : mc.player.blockPosition())
                            .offset(dx, dy, dz);
                    BlockEntity be = mc.level.getBlockEntity(pos);
                    if (AetherworksHelper.isAnvil(be)) {
                        ItemStack item = AetherworksHelper.getAnvilSlot(be, 0);
                        if (!item.isEmpty()) {
                            Object recipe = AetherworksHelper.findRecipe(mc.level, item);
                            if (recipe != null) {
                                int median = (AetherworksHelper.getRecipeTempMin(recipe)
                                        + AetherworksHelper.getRecipeTempMax(recipe)) / 2;
                                tempMin = median - MEDIAN_MARGIN;
                                tempMax = median + MEDIAN_MARGIN;
                                return true;
                            }
                        }
                    }
                }
        return false;
    }

    // ---- Bind / Unbind ----

    public static void bindLever(BlockPos leverPos) {
        boundLeverPos = leverPos;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        if (tryAutoSetTempFromAnvil(mc)) {
            mc.player.displayClientMessage(
                    Component.literal("§a已绑定拉杆 温控: " + tempMin + "° - " + tempMax + "°"), true);
        } else {
            mc.player.displayClientMessage(
                    Component.literal("§a已绑定拉杆 默认温控: " + tempMin + "° - " + tempMax + "°"), true);
        }
    }

    public static void unbind() {
        boundLeverPos = null;
        lastSeenItem = ItemStack.EMPTY;
        lastTrackedItem = ItemStack.EMPTY;
        justRefilled = false;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.player.displayClientMessage(Component.literal("§c已解绑拉杆"), true);
    }

    // ---- Tick ----

    public static void onTick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        BlockEntity anvil = AetherworksHelper.getTargetAnvil();
        if (anvil == null) return;

        BlockEntity forge = AetherworksHelper.findForge(mc.level, anvil.getBlockPos());
        ItemStack currentItem = AetherworksHelper.getAnvilSlot(anvil, 0);

        // 1. Auto-detect temp range on item change
        if (!currentItem.isEmpty() && !ItemStack.isSameItemSameTags(currentItem, lastTrackedItem)) {
            Object recipe = AetherworksHelper.findRecipe(mc.level, currentItem);
            if (recipe != null) {
                int median = (AetherworksHelper.getRecipeTempMin(recipe)
                        + AetherworksHelper.getRecipeTempMax(recipe)) / 2;
                tempMin = median - MEDIAN_MARGIN;
                tempMax = median + MEDIAN_MARGIN;
            }
            lastTrackedItem = currentItem.copy();
        }
        if (currentItem.isEmpty()) lastTrackedItem = ItemStack.EMPTY;

        // 2. Temperature control via lever
        if (boundLeverPos != null && leverControlEnabled && forge != null) {
            double heat = AetherworksHelper.getForgeHeat(forge);
            boolean actuallyOn = isLeverActuallyOn();
            if (heat > tempMax && actuallyOn) {
                toggleLever();
            } else if (heat < tempMin && !actuallyOn) {
                toggleLever();
            }
        }

        // 3. Auto-refill from offhand
        if (!justRefilled && autoRefillEnabled && !lastSeenItem.isEmpty() && !currentItem.isEmpty()
                && !ItemStack.isSameItemSameTags(currentItem, lastSeenItem)) {
            ItemStack offhand = mc.player.getOffhandItem();
            if (!offhand.isEmpty()) {
                pickupResult(mc, anvil.getBlockPos());
                BlockHitResult hit = new BlockHitResult(
                        new Vec3(anvil.getBlockPos().getX() + 0.5,
                                anvil.getBlockPos().getY() + 0.5,
                                anvil.getBlockPos().getZ() + 0.5),
                        Direction.UP, anvil.getBlockPos(), false);
                mc.gameMode.useItemOn(mc.player, InteractionHand.OFF_HAND, hit);
                justRefilled = true;
                lastSeenItem = offhand.copy();
            } else {
                turnOffHeatingIfOn();
            }
        }

        if (justRefilled) {
            justRefilled = false;
        } else if (!currentItem.isEmpty()) {
            lastSeenItem = currentItem.copy();
        } else {
            lastSeenItem = ItemStack.EMPTY;
        }
    }

    // ---- Actions ----

    private static void toggleLever() {
        if (boundLeverPos == null) return;
        Minecraft mc = Minecraft.getInstance();
        BlockHitResult hit = new BlockHitResult(
                new Vec3(boundLeverPos.getX() + 0.5, boundLeverPos.getY() + 0.5, boundLeverPos.getZ() + 0.5),
                Direction.UP, boundLeverPos, false);
        mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit);
    }

    private static void turnOffHeatingIfOn() {
        if (boundLeverPos == null || !isLeverActuallyOn()) return;
        toggleLever();
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.player.displayClientMessage(Component.literal("§e加热拉杆已关闭"), true);
    }

    private static void pickupResult(Minecraft mc, BlockPos anvilPos) {
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getItem(i).isEmpty()) {
                int prev = mc.player.getInventory().selected;
                mc.player.getInventory().selected = i;
                sendUseOnBlock(mc, anvilPos);
                mc.player.getInventory().selected = prev;
                return;
            }
        }
        for (int i = 9; i < 36; i++) {
            if (mc.player.getInventory().getItem(i).isEmpty()) {
                mc.gameMode.handleInventoryMouseClick(
                        mc.player.containerMenu.containerId, i,
                        mc.player.getInventory().selected,
                        net.minecraft.world.inventory.ClickType.SWAP, mc.player);
                sendUseOnBlock(mc, anvilPos);
                mc.gameMode.handleInventoryMouseClick(
                        mc.player.containerMenu.containerId, i,
                        mc.player.getInventory().selected,
                        net.minecraft.world.inventory.ClickType.SWAP, mc.player);
                return;
            }
        }
    }

    private static void sendUseOnBlock(Minecraft mc, BlockPos pos) {
        BlockHitResult hit = new BlockHitResult(
                new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5),
                Direction.UP, pos, false);
        mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit);
    }
}
