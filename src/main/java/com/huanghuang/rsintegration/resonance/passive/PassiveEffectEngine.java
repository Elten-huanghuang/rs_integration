package com.huanghuang.rsintegration.resonance.passive;

import com.huanghuang.rsintegration.network.RSIntegrationNetwork;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.network.packet.NetworkHandler;
import com.huanghuang.rsintegration.network.packet.ResonanceSyncPacket;
import com.huanghuang.rsintegration.mods.lychee.LycheeVirtualCatalysts;
import com.huanghuang.rsintegration.resonance.backpack.ResonanceBackpackContainer;
import com.huanghuang.rsintegration.resonance.disk.ResonanceDiskWrapper;
import com.refinedmods.refinedstorage.api.network.INetwork;
import com.refinedmods.refinedstorage.api.storage.IStorage;
import com.refinedmods.refinedstorage.api.storage.disk.IStorageDisk;
import com.refinedmods.refinedstorage.apiimpl.network.node.diskdrive.ItemDriveWrapperStorageDisk;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class PassiveEffectEngine {

    private static final Field DRIVE_PARENT;
    private static final Map<UUID, ResonanceDiskWrapper> DISK_CACHE = new ConcurrentHashMap<>();
    private static final Map<UUID, DiskSyncState> SYNC_CACHE = new ConcurrentHashMap<>();

    static {
        Field f = null;
        try {
            f = ItemDriveWrapperStorageDisk.class.getDeclaredField("parent");
            f.setAccessible(true);
        } catch (NoSuchFieldException ignored) {}
        DRIVE_PARENT = f;
    }

    private PassiveEffectEngine() {}

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!(event.player instanceof ServerPlayer player)) return;
        // Resolve disk once per second; every tick use cached reference
        if (player.tickCount % 20 == 0) {
            INetwork network = RSIntegrationNetwork.resolveNetworkFromPlayer(player);
            ResonanceDiskWrapper disk = (network != null) ? findResonanceDisk(network) : null;
            if (disk != null) {
                DISK_CACHE.put(player.getUUID(), disk);
            } else {
                DISK_CACHE.remove(player.getUUID());
            }
            syncDiskState(player, disk);
        }

        ResonanceDiskWrapper cachedDisk = DISK_CACHE.get(player.getUUID());
        if (!RSIntegrationConfig.ENABLE_RS_PASSIVE_EFFECTS.get()) return;
        // The backpack menu owns a slot snapshot and reconciles user changes
        // against it. Mutating NBT in the delegate while that menu is open can
        // make the snapshot stale and overwrite a different NBT variant when
        // the player takes an item (notably Apotheosis potion charms).
        if (cachedDisk != null && !(player.containerMenu instanceof ResonanceBackpackContainer)) {
            TickSimulator.simulate(player, cachedDisk);
        }
    }

    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) {
            DISK_CACHE.remove(sp.getUUID());
            SYNC_CACHE.remove(sp.getUUID());
        }
    }

    private static void syncDiskState(ServerPlayer player, @Nullable ResonanceDiskWrapper disk) {
        int gemCount = 0;
        if (disk != null) {
            for (ItemStack stack : disk.getStacks()) {
                if (!stack.isEmpty() && stack.is(net.minecraftforge.common.Tags.Items.GEMS)) {
                    gemCount += stack.getCount();
                }
            }
        }
        int catalystMask = LycheeVirtualCatalysts.catalystMask(disk);
        int abilityMask = disk == null ? 0 : disk.abilityMask();
        DiskSyncState previous = SYNC_CACHE.get(player.getUUID());
        long revision = previous == null ? 0L : previous.revision();
        if (previous == null || previous.gemCount() != gemCount
                || previous.lycheeCatalystMask() != catalystMask
                || previous.abilityMask() != abilityMask) {
            revision++;
        }
        SYNC_CACHE.put(player.getUUID(),
                new DiskSyncState(gemCount, catalystMask, abilityMask, revision));
        NetworkHandler.CHANNEL.sendTo(
                new ResonanceSyncPacket(gemCount, catalystMask, abilityMask, revision),
                player.connection.connection,
                net.minecraftforge.network.NetworkDirection.PLAY_TO_CLIENT);
    }

    private record DiskSyncState(int gemCount, int lycheeCatalystMask,
                                 int abilityMask, long revision) {}

    @Nullable
    public static ResonanceDiskWrapper findResonanceDisk(INetwork network) {
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
                    } catch (IllegalAccessException ignored) {}
                }
                if (disk instanceof ResonanceDiskWrapper wrapper) return wrapper;
            }
        }
        return null;
    }
}
