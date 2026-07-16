package com.huanghuang.rsintegration.crafting.loadbalancer;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.api.RSIMachineAccessor;
import com.huanghuang.rsintegration.crafting.batch.IBatchDelegate;
import com.huanghuang.rsintegration.network.binding.AltarBindingRegistry.BoundMachine;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Busy-aware round-robin dispatcher for multi-machine parallel crafting.
 *
 * <p>Core algorithm:</p>
 * <ol>
 *   <li>Filter bound machines: chunk loaded, BE present, not busy</li>
 *   <li>Distribute recipe operations evenly across available machines</li>
 *   <li>Return a dispatch decision: SINGLE, PARALLEL, or NO_IDLE_MACHINE</li>
 * </ol>
 *
 * <p>Does NOT modify any delegate or chain — operates purely at the
 * machine-selection level before delegate creation.</p>
 */
public final class LoadBalancer {

    private LoadBalancer() {}

    // ── dispatch result ────────────────────────────────────────────

    public enum Kind { SINGLE_MACHINE, PARALLEL, NO_IDLE_MACHINE }

    public record DispatchResult(
            Kind kind,
            @Nullable BoundMachine singleMachine,          // SINGLE_MACHINE
            @Nullable List<MachineAssignment> assignments,  // PARALLEL
            @Nullable String errorKey                       // NO_IDLE_MACHINE
    ) {
        public static DispatchResult single(BoundMachine m) {
            return new DispatchResult(Kind.SINGLE_MACHINE, m, null, null);
        }
        public static DispatchResult parallel(List<MachineAssignment> a) {
            return new DispatchResult(Kind.PARALLEL, null, a, null);
        }
        public static DispatchResult noIdle(String key) {
            return new DispatchResult(Kind.NO_IDLE_MACHINE, null, null, key);
        }
    }

    /** Assignment of N recipe operations to a specific machine. */
    public record MachineAssignment(BoundMachine machine, int operations) {}

    // ── public API ─────────────────────────────────────────────────

    /**
     * Dispatch a recipe to bound machines of the given mod type.
     *
     * @param player   the requesting player
     * @param machines all bound machines of the target mod type (from AltarBindingRegistry)
     * @param totalOps total recipe operations needed
     * @param server   the server, used to resolve each machine's level for BE lookups
     * @return dispatch decision
     */
    public static DispatchResult dispatch(ServerPlayer player, List<BoundMachine> machines,
                                          int totalOps, MinecraftServer server) {
        if (machines.isEmpty()) {
            return DispatchResult.noIdle("rsi.loadbalancer.error.no_machines");
        }

        // 1. Filter to available (chunk loaded, BE present, not busy)
        List<BoundMachine> available = filterAvailable(machines, server);
        if (available.isEmpty()) {
            return DispatchResult.noIdle("rsi.loadbalancer.error.all_busy");
        }

        // 2. Single machine → no balancing needed
        if (available.size() == 1) {
            return DispatchResult.single(available.get(0));
        }

        // 3. Distribute operations
        List<MachineAssignment> assignments = distribute(totalOps, available);
        if (assignments.isEmpty()) {
            return DispatchResult.noIdle("rsi.loadbalancer.error.distribution_failed");
        }

        return DispatchResult.parallel(assignments);
    }

    // ── availability filter ────────────────────────────────────────

    /**
     * Filter machines to those that are ready to accept work.
     *
     * <p>Structural checks (always applied):</p>
     * <ul>
     *   <li>{@code level.isLoaded(pos)} — chunk must be resident</li>
     *   <li>{@code be != null && !be.isRemoved()} — machine must exist</li>
     * </ul>
     *
     * <p>Busy check (when Mixin is present):</p>
     * <ul>
     *   <li>{@code instanceof RSIMachineAccessor acc && acc.rsi$isBusy()} — skip busy</li>
     *   <li>When Mixin is not yet implemented for a mod, the instanceof check
     *       returns false and the machine passes the filter (conservative: don't
     *       block machines that might be idle).</li>
     * </ul>
     */
    /**
     * Filter machines to those that are ready to accept work.
     * Resolves the correct {@link ServerLevel} per machine so machines in
     * different dimensions are all checked correctly.
     */
    public static List<BoundMachine> filterAvailable(List<BoundMachine> machines, MinecraftServer server) {
        return filterAvailable(machines, server, null);
    }

    public static List<BoundMachine> filterAvailable(List<BoundMachine> machines, MinecraftServer server,
                                                     @Nullable IBatchDelegate delegate) {
        List<BoundMachine> available = new ArrayList<>(machines.size());
        for (BoundMachine m : machines) {
            BlockPos pos = m.pos();
            var dimKey = ResourceKey.create(
                    net.minecraft.core.registries.Registries.DIMENSION, m.dim());
            ServerLevel level = server.getLevel(dimKey);
            if (level == null) continue;
            if (!level.isLoaded(pos)) continue;

            BlockEntity be = level.getBlockEntity(pos);
            if (be == null || be.isRemoved()) {
                if (delegate != null && delegate.acceptsMachineWithoutBlockEntity(level, pos)) {
                    available.add(m);
                }
                continue;
            }

            // Busy check via Mixin interface (no-op when Mixin not yet implemented)
            if (be instanceof RSIMachineAccessor acc && acc.rsi$isBusy()) {
                RSIntegrationMod.LOGGER.debug("[RSI-LoadBalancer] Machine at {} is busy, skipping", pos);
                continue;
            }

            available.add(m);
        }
        return available;
    }

    // ── distribution algorithm ─────────────────────────────────────

    /**
     * Distribute recipe operations evenly across available machines.
     *
     * <p>Remainder is assigned to the first N machines (one extra op each).
     * Machines that would receive 0 operations are excluded from the result.</p>
     *
     * @param totalOps    total recipe operations to distribute
     * @param machines    available machines (already filtered)
     * @return assignments, one per machine that receives work
     */
    public static List<MachineAssignment> distribute(int totalOps, List<BoundMachine> machines) {
        int count = machines.size();
        if (count == 0 || totalOps <= 0) return List.of();

        int base = totalOps / count;
        int remainder = totalOps % count;

        List<MachineAssignment> assignments = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int ops = base + (i < remainder ? 1 : 0);
            if (ops > 0) {
                assignments.add(new MachineAssignment(machines.get(i), ops));
            }
        }
        return assignments;
    }
}
