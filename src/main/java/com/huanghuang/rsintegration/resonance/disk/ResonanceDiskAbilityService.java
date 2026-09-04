package com.huanghuang.rsintegration.resonance.disk;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.resonance.item.ResonanceDiskItem;
import com.huanghuang.rsintegration.resonance.api.ResonanceStorageResolvers;
import com.huanghuang.rsintegration.resonance.api.ResonanceStorageView;
import com.refinedmods.refinedstorage.apiimpl.API;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fml.ModList;

public final class ResonanceDiskAbilityService {

    public enum UnlockResult {
        UNLOCKED,
        ALREADY_UNLOCKED,
        NO_RESONANCE_DISK
    }

    private ResonanceDiskAbilityService() {}

    public static boolean hasActiveAbility(ServerPlayer player, int ability) {
        for (ResonanceStorageView view : ResonanceStorageResolvers.resolveAll(player)) {
            if (view.hasAbility(ability)) return true;
        }
        return false;
    }

    public static UnlockResult unlockActiveDisk(ServerPlayer player, int ability) {
        var views = ResonanceStorageResolvers.resolveAll(player);
        if (views.isEmpty()) return UnlockResult.NO_RESONANCE_DISK;
        for (ResonanceStorageView view : views) {
            if (view.unlockAbility(ability)) {
                view.markDirty(player);
                return UnlockResult.UNLOCKED;
            }
        }
        return UnlockResult.ALREADY_UNLOCKED;
    }

    public static UnlockResult unlockDiskStack(ServerLevel level, ItemStack stack, int ability) {
        // BD resonance disks have their own UUID-owned persistence and do not
        // depend on Refined Storage's StorageDiskItem hierarchy.
        if (com.huanghuang.rsintegration.resonance.bd.BDResonanceDiskAccess.isDisk(stack)) {
            var diskId = com.huanghuang.rsintegration.resonance.bd.BDResonanceDiskAccess
                    .getDiskId(stack);
            if (diskId == null) return UnlockResult.NO_RESONANCE_DISK;
            var data = com.huanghuang.rsintegration.resonance.bd.BDResonanceDiskData
                    .get(level.getServer());
            return data.unlock(diskId, ability)
                    ? UnlockResult.UNLOCKED : UnlockResult.ALREADY_UNLOCKED;
        }

        // Do not resolve ResonanceDiskItem when RS is absent: it subclasses
        // StorageDiskItem, which is not present in BD-only or Malum-only packs.
        if (!ModList.get().isLoaded("refinedstorage")
                || !(stack.getItem() instanceof ResonanceDiskItem diskItem)
                || !diskItem.isValid(stack)) {
            return UnlockResult.NO_RESONANCE_DISK;
        }

        var manager = API.instance().getStorageDiskManager(level);
        var storedDisk = manager.getByStack(stack);
        if (!(storedDisk instanceof ResonanceDiskWrapper disk)) {
            RSIntegrationMod.LOGGER.warn(
                    "[RSI-Resonance] UUID {} belongs to an unexpected disk implementation: {}",
                    diskItem.getId(stack),
                    storedDisk == null ? "null" : storedDisk.getClass().getName());
            return UnlockResult.NO_RESONANCE_DISK;
        }
        if (!disk.unlockAbility(ability)) return UnlockResult.ALREADY_UNLOCKED;
        manager.markForSaving();
        return UnlockResult.UNLOCKED;
    }
}
