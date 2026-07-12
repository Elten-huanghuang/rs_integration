package com.huanghuang.rsintegration.resonance.item;

import com.huanghuang.rsintegration.resonance.disk.ResonanceDiskWrapper;
import com.refinedmods.refinedstorage.api.IRSAPI;
import com.refinedmods.refinedstorage.api.storage.StorageType;
import com.refinedmods.refinedstorage.api.storage.disk.IStorageDisk;
import com.refinedmods.refinedstorage.apiimpl.API;
import com.refinedmods.refinedstorage.apiimpl.storage.ItemStorageType;
import com.refinedmods.refinedstorage.item.StorageDiskItem;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.util.UUID;

public final class ResonanceDiskItem extends StorageDiskItem {

    public static final ResonanceDiskItem INSTANCE = new ResonanceDiskItem();

    static final int RESONANCE_CAPACITY = 36 * 64; // 36 slots × 64 max stack = 2304

    private ResonanceDiskItem() {
        super(ItemStorageType.FOUR_K);
    }

    @Override
    public int getCapacity(ItemStack stack) {
        return RESONANCE_CAPACITY;
    }

    @Override
    public void inventoryTick(ItemStack stack, Level level, Entity entity, int slot, boolean selected) {
        // isValid() checks hasTag() before reading the Id key, so it is null-safe
        // for freshly-created disks (creative tab / give / datapack) that have no
        // NBT yet. Calling getId() directly here would NPE inside RS's getTag().
        if (level.isClientSide() || isValid(stack) || !(entity instanceof Player player)) {
            return;
        }

        UUID id = UUID.randomUUID();
        IRSAPI api = API.instance();
        ServerLevel serverLevel = (ServerLevel) level;

        IStorageDisk<ItemStack> inner = api.createDefaultItemDisk(serverLevel, RESONANCE_CAPACITY, player);
        ResonanceDiskWrapper wrapper = new ResonanceDiskWrapper(inner);

        api.getStorageDiskManager(serverLevel).set(id, wrapper);
        api.getStorageDiskManager(serverLevel).markForSaving();
        setId(stack, id);
    }
}
