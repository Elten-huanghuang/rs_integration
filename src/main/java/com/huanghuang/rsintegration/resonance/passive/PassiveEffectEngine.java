package com.huanghuang.rsintegration.resonance.passive;

import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.network.packet.NetworkHandler;
import com.huanghuang.rsintegration.network.packet.ResonanceSyncPacket;
import com.huanghuang.rsintegration.mods.lychee.LycheeVirtualCatalysts;
import com.huanghuang.rsintegration.resonance.api.ResonanceStorageMenu;
import com.huanghuang.rsintegration.resonance.api.ResonanceStorageResolvers;
import com.huanghuang.rsintegration.resonance.api.ResonanceStorageView;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class PassiveEffectEngine {

    private static final Map<UUID, List<ResonanceStorageView>> DISK_CACHE = new ConcurrentHashMap<>();
    private static final Map<UUID, DiskSyncState> SYNC_CACHE = new ConcurrentHashMap<>();

    private PassiveEffectEngine() {}

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!(event.player instanceof ServerPlayer player)) return;
        // Resolve disk once per second; every tick use cached reference
        if (player.tickCount % 20 == 0) {
            List<ResonanceStorageView> views = ResonanceStorageResolvers.resolveAll(player);
            if (views.isEmpty()) DISK_CACHE.remove(player.getUUID());
            else DISK_CACHE.put(player.getUUID(), views);
            syncDiskState(player, views);
        }

        List<ResonanceStorageView> cachedDisks = DISK_CACHE.get(player.getUUID());
        if (!RSIntegrationConfig.ENABLE_RS_PASSIVE_EFFECTS.get()) return;
        // The backpack menu owns a slot snapshot and reconciles user changes
        // against it. Mutating NBT in the delegate while that menu is open can
        // make the snapshot stale and overwrite a different NBT variant when
        // the player takes an item (notably Apotheosis potion charms).
        if (cachedDisks != null && !(player.containerMenu instanceof ResonanceStorageMenu)) {
            for (ResonanceStorageView disk : cachedDisks) TickSimulator.simulate(player, disk);
        }
    }

    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) {
            DISK_CACHE.remove(sp.getUUID());
            SYNC_CACHE.remove(sp.getUUID());
        }
    }

    public static void refreshPlayer(ServerPlayer player) {
        List<ResonanceStorageView> views = ResonanceStorageResolvers.resolveAll(player);
        if (views.isEmpty()) DISK_CACHE.remove(player.getUUID());
        else DISK_CACHE.put(player.getUUID(), views);
        syncDiskState(player, views);
    }

    private static void syncDiskState(ServerPlayer player, List<ResonanceStorageView> views) {
        int gemCount = 0;
        int abilityMask = 0;
        for (ResonanceStorageView disk : views) {
            for (ResonanceStorageView.StoredStack stored : disk.storedStacks()) {
                ItemStack stack = stored.stack();
                if (!stack.isEmpty() && stack.is(net.minecraftforge.common.Tags.Items.GEMS)) {
                    gemCount += stack.getCount();
                }
            }
            abilityMask |= disk.abilityMask();
        }
        int catalystMask = LycheeVirtualCatalysts.catalystMask(views);
        DiskSyncState previous = SYNC_CACHE.get(player.getUUID());
        long revision = previous == null ? 0L : previous.revision();
        boolean changed = previous == null || previous.gemCount() != gemCount
                || previous.lycheeCatalystMask() != catalystMask
                || previous.abilityMask() != abilityMask;
        if (changed) {
            revision++;
        }
        SYNC_CACHE.put(player.getUUID(),
                new DiskSyncState(gemCount, catalystMask, abilityMask, revision));
        if (!changed) return;
        NetworkHandler.CHANNEL.sendTo(
                new ResonanceSyncPacket(gemCount, catalystMask, abilityMask, revision),
                player.connection.connection,
                net.minecraftforge.network.NetworkDirection.PLAY_TO_CLIENT);
    }

    private record DiskSyncState(int gemCount, int lycheeCatalystMask,
                                 int abilityMask, long revision) {}

}
