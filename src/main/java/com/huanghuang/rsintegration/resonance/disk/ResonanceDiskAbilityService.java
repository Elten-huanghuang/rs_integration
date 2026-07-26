package com.huanghuang.rsintegration.resonance.disk;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.network.RSIntegrationNetwork;
import com.huanghuang.rsintegration.resonance.item.ResonanceDiskItem;
import com.huanghuang.rsintegration.resonance.passive.PassiveEffectEngine;
import com.refinedmods.refinedstorage.apiimpl.API;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

public final class ResonanceDiskAbilityService {

    public enum UnlockResult {
        UNLOCKED,
        ALREADY_UNLOCKED,
        NO_RESONANCE_DISK
    }

    private ResonanceDiskAbilityService() {}

    public static boolean hasActiveAbility(ServerPlayer player, int ability) {
        var network = RSIntegrationNetwork.resolveNetworkFromPlayer(player);
        ResonanceDiskWrapper disk = network == null
                ? null : PassiveEffectEngine.findResonanceDisk(network);
        return disk != null && disk.hasAbility(ability);
    }

    public static UnlockResult unlockActiveDisk(ServerPlayer player, int ability) {
        var network = RSIntegrationNetwork.resolveNetworkFromPlayer(player);
        ResonanceDiskWrapper disk = network == null
                ? null : PassiveEffectEngine.findResonanceDisk(network);
        if (disk == null) return UnlockResult.NO_RESONANCE_DISK;
        if (!disk.unlockAbility(ability)) return UnlockResult.ALREADY_UNLOCKED;

        // A bound network may live in a different dimension from the player.
        // Mark every level manager dirty so the UUID-owned disk record is saved.
        for (ServerLevel level : player.server.getAllLevels()) {
            API.instance().getStorageDiskManager(level).markForSaving();
        }
        return UnlockResult.UNLOCKED;
    }

    public static UnlockResult unlockDiskStack(ServerLevel level, ItemStack stack, int ability) {
        if (!(stack.getItem() instanceof ResonanceDiskItem diskItem)
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
