package com.huanghuang.rsintegration.resonance.passive;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.network.packet.NetworkHandler;
import com.huanghuang.rsintegration.network.packet.ResonanceSyncPacket;
import com.huanghuang.rsintegration.network.RSIntegrationNetwork;
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
        if (!RSIntegrationConfig.ENABLE_RS_PASSIVE_EFFECTS.get()) return;

        // Resolve disk once per second; every tick use cached reference
        if (player.tickCount % 20 == 0) {
            INetwork network = RSIntegrationNetwork.resolveNetworkFromPlayer(player);
            ResonanceDiskWrapper disk = (network != null) ? findResonanceDisk(network) : null;
            if (disk != null) {
                DISK_CACHE.put(player.getUUID(), disk);
                syncDiskGemCount(player, disk);
            } else {
                DISK_CACHE.remove(player.getUUID());
            }
        }

        ResonanceDiskWrapper cachedDisk = DISK_CACHE.get(player.getUUID());
        if (cachedDisk != null) {
            TickSimulator.simulate(player, cachedDisk);
        }
    }

    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) {
            DISK_CACHE.remove(sp.getUUID());
        }
    }

    private static void syncDiskGemCount(ServerPlayer player, ResonanceDiskWrapper disk) {
        int gemCount = 0;
        for (ItemStack stack : disk.getStacks()) {
            if (!stack.isEmpty() && stack.is(net.minecraftforge.common.Tags.Items.GEMS)) {
                gemCount += stack.getCount();
            }
        }
        NetworkHandler.CHANNEL.sendTo(
                new ResonanceSyncPacket(gemCount),
                player.connection.connection,
                net.minecraftforge.network.NetworkDirection.PLAY_TO_CLIENT);
    }

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
