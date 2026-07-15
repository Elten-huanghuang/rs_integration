package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.mixin.minecraft.ItemEntityAccessor;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Intercepts world-item machine outputs at spawn time, before magnets or pickup
 * handlers can move them. Each armed craft owns a unique, one-shot handle so
 * overlapping or consecutive crafts cannot overwrite or drain one another.
 */
@Mod.EventBusSubscriber(modid = RSIntegrationMod.MOD_ID)
public final class CraftOutputInterceptor {

    private CraftOutputInterceptor() {}

    private static final class ActiveZone {
        final AABB region;
        final ItemStack expectedOutput;
        final Queue<ItemStack> buffer = new ConcurrentLinkedQueue<>();

        ActiveZone(AABB region, ItemStack expectedOutput) {
            this.region = region;
            this.expectedOutput = expectedOutput == null ? ItemStack.EMPTY : expectedOutput.copy();
        }

        boolean accepts(ItemStack stack) {
            return expectedOutput.isEmpty() || ItemStack.isSameItem(expectedOutput, stack);
        }
    }

    /** Unique ownership token. Closing/draining the same token twice is harmless. */
    public static final class CaptureHandle {
        private final ResourceKey<Level> dimension;
        private final UUID id;
        private final AtomicBoolean closed = new AtomicBoolean();

        private CaptureHandle(ResourceKey<Level> dimension, UUID id) {
            this.dimension = dimension;
            this.id = id;
        }

        public List<ItemStack> drainAndClose() {
            if (!closed.compareAndSet(false, true)) return List.of();
            Map<UUID, ActiveZone> dimZones = ZONES.get(dimension);
            if (dimZones == null) return List.of();
            ActiveZone zone = dimZones.remove(id);
            if (dimZones.isEmpty()) ZONES.remove(dimension, dimZones);
            return zone == null ? List.of() : new ArrayList<>(zone.buffer);
        }

        /** True once an output entity has been claimed by this craft. */
        public boolean hasCaptured() {
            Map<UUID, ActiveZone> dimZones = ZONES.get(dimension);
            ActiveZone zone = dimZones == null ? null : dimZones.get(id);
            return zone != null && !zone.buffer.isEmpty();
        }
    }

    private static final Map<ResourceKey<Level>, Map<UUID, ActiveZone>> ZONES =
            new ConcurrentHashMap<>();

    /** Arm a uniquely-owned zone, rejecting ambiguous overlapping captures. */
    public static CaptureHandle arm(ResourceKey<Level> dim, AABB region, ItemStack expectedOutput) {
        if (dim == null || region == null) return null;
        Map<UUID, ActiveZone> dimZones = ZONES.computeIfAbsent(dim, k -> new ConcurrentHashMap<>());
        for (ActiveZone active : dimZones.values()) {
            if (active.region.intersects(region) && outputsMayOverlap(active.expectedOutput, expectedOutput)) {
                return null;
            }
        }
        UUID id = UUID.randomUUID();
        dimZones.put(id, new ActiveZone(region, expectedOutput));
        return new CaptureHandle(dim, id);
    }

    private static boolean outputsMayOverlap(ItemStack first, ItemStack second) {
        if (first == null || first.isEmpty() || second == null || second.isEmpty()) return true;
        return ItemStack.isSameItem(first, second);
    }

    public static int activeZoneCount() {
        int total = 0;
        for (Map<UUID, ActiveZone> zones : ZONES.values()) total += zones.size();
        return total;
    }

    /** Used by optional magnet mixins to leave protected entities untouched. */
    public static boolean isInActiveZone(Level level, Vec3 pos) {
        if (ZONES.isEmpty()) return false;
        Map<UUID, ActiveZone> dimZones = ZONES.get(level.dimension());
        if (dimZones == null || dimZones.isEmpty()) return false;
        for (ActiveZone zone : dimZones.values()) {
            if (zone.region.contains(pos)) return true;
        }
        return false;
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (ZONES.isEmpty()) return;
        Level level = event.getLevel();
        if (level.isClientSide) return;
        if (!(event.getEntity() instanceof ItemEntity itemEntity)) return;
        if (((ItemEntityAccessor) (Object) itemEntity).rsi$getThrower() != null) return;
        if (itemEntity.tickCount > 0) return;

        Map<UUID, ActiveZone> dimZones = ZONES.get(level.dimension());
        if (dimZones == null || dimZones.isEmpty()) return;
        ItemStack stack = itemEntity.getItem();
        if (stack.isEmpty()) return;

        ActiveZone best = null;
        double bestDistance = Double.MAX_VALUE;
        Vec3 position = itemEntity.position();
        for (ActiveZone zone : dimZones.values()) {
            if (!zone.region.contains(position) || !zone.accepts(stack)) continue;
            double distance = zone.region.getCenter().distanceToSqr(position);
            if (distance < bestDistance) {
                best = zone;
                bestDistance = distance;
            }
        }
        if (best != null) {
            best.buffer.add(stack.copy());
            event.setCanceled(true);
        }
    }
}
