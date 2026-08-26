package com.huanghuang.rsintegration.resonance.bridge;

import com.huanghuang.rsintegration.resonance.disk.ResonanceDiskWrapper;
import com.refinedmods.refinedstorage.api.network.INetwork;
import com.refinedmods.refinedstorage.api.storage.IStorage;
import com.refinedmods.refinedstorage.api.storage.disk.IStorageDisk;
import com.refinedmods.refinedstorage.apiimpl.network.node.diskdrive.ItemDriveWrapperStorageDisk;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.lang.reflect.Field;

/** Refined Storage-specific access to a resonance disk in an item storage cache. */
public final class RSResonanceDiskAccess {

    private static final Field DRIVE_PARENT = resolveDriveParent();

    private RSResonanceDiskAccess() {}

    @Nullable
    public static ResonanceDiskWrapper find(INetwork network) {
        if (network == null) return null;
        var cache = network.getItemStorageCache();
        if (cache == null) return null;
        for (IStorage<ItemStack> storage : cache.getStorages()) {
            if (storage instanceof ResonanceDiskWrapper wrapper) return wrapper;
            if (storage instanceof IStorageDisk<ItemStack> disk
                    && ResonanceDiskWrapper.FACTORY_ID.equals(disk.getFactoryId())) {
                if (disk instanceof ItemDriveWrapperStorageDisk && DRIVE_PARENT != null) {
                    try {
                        IStorageDisk<ItemStack> inner =
                                (IStorageDisk<ItemStack>) DRIVE_PARENT.get(disk);
                        if (inner instanceof ResonanceDiskWrapper wrapper) return wrapper;
                    } catch (IllegalAccessException ignored) {
                    }
                }
                if (disk instanceof ResonanceDiskWrapper wrapper) return wrapper;
            }
        }
        return null;
    }

    @Nullable
    private static Field resolveDriveParent() {
        try {
            Field field = ItemDriveWrapperStorageDisk.class.getDeclaredField("parent");
            field.setAccessible(true);
            return field;
        } catch (NoSuchFieldException ignored) {
            return null;
        }
    }
}
