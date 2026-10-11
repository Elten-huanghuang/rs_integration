package com.huanghuang.rsintegration.mods.tacz;

import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.resonance.api.ResonanceStorageView;
import com.huanghuang.rsintegration.resonance.bridge.ResonanceInventoryBridge;
import com.huanghuang.rsintegration.resonance.bridge.ClientDiskData;
import com.tacz.guns.api.item.IAmmo;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemStackHandler;

import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;

/** 仅在 TaCZ 查询弹药时追加共振盘槽位，沿用原模组的口径匹配与装填逻辑。 */
public final class TaczResonanceAmmoHandler implements IItemHandler {
    private final IItemHandler inventory;
    private final List<AmmoSlot> ammoSlots = new ArrayList<>();

    public TaczResonanceAmmoHandler(IItemHandler inventory, List<ResonanceStorageView> views) {
        this.inventory = inventory;
        for (ResonanceStorageView view : views) {
            for (ResonanceStorageView.StoredStack stored : view.storedStacks()) {
                ItemStack stack = stored.stack();
                if (!stack.isEmpty() && stack.getItem() instanceof IAmmo) {
                    ammoSlots.add(new AmmoSlot(view, stored.slot(), stack.copy()));
                }
            }
        }
    }

    public static LazyOptional<IItemHandler> includeResonanceAmmo(
            LivingEntity shooter, Capability<IItemHandler> capability,
            LazyOptional<IItemHandler> original) {
        if (capability != ForgeCapabilities.ITEM_HANDLER || !RSIntegrationConfig.ENABLE_TACZ.get()
                || !RSIntegrationConfig.ENABLE_RS_PASSIVE_EFFECTS.get()) return original;
        if (shooter instanceof Player player && player.isLocalPlayer()) {
            // 本地换弹会先检查弹药；服务端快照仅供判断，实际扣弹仍由服务端完成。
            TaczResonanceAmmoHandler preview = new TaczResonanceAmmoHandler(
                    original.orElseGet(() -> new ItemStackHandler(0)), List.of());
            for (ItemStack stack : ClientDiskData.getTaczAmmo()) {
                if (!stack.isEmpty() && stack.getItem() instanceof IAmmo) {
                    preview.ammoSlots.add(new AmmoSlot(null, -1, stack));
                }
            }
            return preview.ammoSlots.isEmpty() ? original : LazyOptional.of(() -> preview);
        }
        if (!(shooter instanceof ServerPlayer player)) return original;
        List<ResonanceStorageView> views = ResonanceInventoryBridge.getViews(player);
        if (views.isEmpty()) return original;
        TaczResonanceAmmoHandler combined = new TaczResonanceAmmoHandler(
                original.orElseGet(() -> new ItemStackHandler(0)), views);
        return combined.ammoSlots.isEmpty() ? original : LazyOptional.of(() -> combined);
    }

    /** HUD 直接扫描背包，补入当前枪械能使用的盘内子弹，并保留原生创造弹药计数。 */
    public static int includeClientAmmoCount(ItemStack gun, int inventoryCount) {
        if (gun.isEmpty() || inventoryCount == 9999
                || !RSIntegrationConfig.ENABLE_TACZ.get()
                || !RSIntegrationConfig.ENABLE_RS_PASSIVE_EFFECTS.get()) return inventoryCount;
        long count = inventoryCount;
        for (ItemStack stack : ClientDiskData.getTaczAmmo()) {
            if (!stack.isEmpty() && stack.getItem() instanceof IAmmo ammo
                    && ammo.isAmmoOfGun(gun, stack)) count += stack.getCount();
        }
        return (int) Math.min(Integer.MAX_VALUE, count);
    }

    @Override
    public int getSlots() {
        return inventory.getSlots() + ammoSlots.size();
    }

    @Override
    public ItemStack getStackInSlot(int slot) {
        if (slot < inventory.getSlots()) return inventory.getStackInSlot(slot);
        return ammoSlot(slot).stack.copy();
    }

    @Override
    public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
        if (slot < inventory.getSlots()) return inventory.insertItem(slot, stack, simulate);
        ammoSlot(slot);
        return stack;
    }

    @Override
    public ItemStack extractItem(int slot, int amount, boolean simulate) {
        if (slot < inventory.getSlots()) return inventory.extractItem(slot, amount, simulate);
        AmmoSlot ammo = ammoSlot(slot);
        if (amount <= 0 || ammo.stack.isEmpty() || ammo.view == null) return ItemStack.EMPTY;
        ItemStack extracted = ammo.view.extractExactView(ammo.slot, ammo.stack,
                Math.min(amount, ammo.stack.getCount()), simulate);
        if (!simulate) ammo.stack.shrink(extracted.getCount());
        return extracted;
    }

    @Override
    public int getSlotLimit(int slot) {
        if (slot < inventory.getSlots()) return inventory.getSlotLimit(slot);
        return ammoSlot(slot).stack.getMaxStackSize();
    }

    @Override
    public boolean isItemValid(int slot, ItemStack stack) {
        if (slot < inventory.getSlots()) return inventory.isItemValid(slot, stack);
        ammoSlot(slot);
        return false;
    }

    private AmmoSlot ammoSlot(int slot) {
        return ammoSlots.get(slot - inventory.getSlots());
    }

    private static final class AmmoSlot {
        @Nullable private final ResonanceStorageView view;
        private final int slot;
        private final ItemStack stack;

        private AmmoSlot(@Nullable ResonanceStorageView view, int slot, ItemStack stack) {
            this.view = view;
            this.slot = slot;
            this.stack = stack;
        }
    }
}
