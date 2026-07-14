package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.crafting.batch.IBatchDelegate;
import com.huanghuang.rsintegration.crafting.graph.ConcurrentNodeExecutor;
import com.huanghuang.rsintegration.crafting.graph.MachineLeaseRegistry;
import com.huanghuang.rsintegration.crafting.graph.NodeId;
import com.huanghuang.rsintegration.crafting.loadbalancer.ParallelCraftGroup;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.List;

/**
 * Wraps a single DAG node's physical delegate, ledger, capture handle, and timeout.
 * Exposes {@link ConcurrentNodeExecutor.Worker} so the graph scheduler can observe
 * and drain it alongside other running nodes.
 */
final class CraftNodeRuntime implements ConcurrentNodeExecutor.Worker {

    private final NodeId nodeId;
    private final String nodeLabel;
    private IBatchDelegate delegate;
    @Nullable
    private final ExtractionLedger nodeLedger;
    @Nullable
    private final MachineLeaseRegistry.Lease machineLease;
    @Nullable
    private CraftOutputInterceptor.CaptureHandle capture;
    private int waitTicks;
    private int drainingTicks;
    private boolean stopRequested;
    private String failureReason;
    private boolean terminal;
    private List<ItemStack> virtualInventory;
    @Nullable
    private ServerPlayer player;

    CraftNodeRuntime(NodeId nodeId, String nodeLabel, IBatchDelegate delegate,
                     @Nullable ExtractionLedger nodeLedger,
                     @Nullable MachineLeaseRegistry.Lease machineLease) {
        this.nodeId = nodeId;
        this.nodeLabel = nodeLabel;
        this.delegate = delegate;
        this.nodeLedger = nodeLedger;
        this.machineLease = machineLease;
    }

    void attachCapture(CraftOutputInterceptor.CaptureHandle handle) {
        this.capture = handle;
    }

    void setChainContext(List<ItemStack> virtualInventory, @Nullable ServerPlayer player) {
        this.virtualInventory = virtualInventory;
        this.player = player;
    }

    NodeId nodeId() { return nodeId; }

    boolean hasDelegate() { return delegate != null; }

    IBatchDelegate delegate() { return delegate; }

    @Nullable ExtractionLedger nodeLedger() { return nodeLedger; }

    @Nullable MachineLeaseRegistry.Lease machineLease() { return machineLease; }

    int waitTicks() { return waitTicks; }

    String failureReason() { return failureReason; }

    // ── Worker ──────────────────────────────────────────────────

    @Override
    public ConcurrentNodeExecutor.Observation observe() {
        if (delegate == null) return ConcurrentNodeExecutor.Observation.FAILED;
        waitTicks++;

        try {
            if (delegate instanceof ParallelCraftGroup group && group.isDraining()) {
                drainingTicks++;
            } else {
                drainingTicks = 0;
            }

            IBatchDelegate.CraftObservation observation = delegate.observeCraft(null);
            if (observation.phase() == IBatchDelegate.CraftPhase.FAILED) {
                failureReason = observation.detail();
                return ConcurrentNodeExecutor.Observation.FAILED;
            }
            if (observation.phase() == IBatchDelegate.CraftPhase.DONE) {
                doSucceed();
                return ConcurrentNodeExecutor.Observation.SUCCEEDED;
            }
        } catch (Exception e) {
            failureReason = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            return ConcurrentNodeExecutor.Observation.FAILED;
        }

        // Timeout: if this node is draining, give it extra time
        int timeoutTicks = RSIntegrationConfig.MULTIBLOCK_CRAFT_TIMEOUT_SECONDS.get() * 20;
        if (waitTicks > timeoutTicks) {
            if (drainingTicks > 0) {
                int drainLimit = Math.max(timeoutTicks * 4, 20 * 60);
                if (drainingTicks > drainLimit) {
                    failureReason = "Timeout draining " + nodeLabel;
                    return ConcurrentNodeExecutor.Observation.FAILED;
                }
                waitTicks = 0;
                return ConcurrentNodeExecutor.Observation.WORKING;
            }
            failureReason = "Timeout waiting for " + nodeLabel;
            return ConcurrentNodeExecutor.Observation.FAILED;
        }

        return ConcurrentNodeExecutor.Observation.WORKING;
    }

    @Override
    public void stopDispatch() {
        stopRequested = true;
        if (delegate instanceof ParallelCraftGroup group) {
            group.isDraining(); // force lazy drain flag
        }
    }

    @Override
    public void cleanupFailure() {
        if (terminal) return;
        terminal = true;
        if (delegate != null) {
            try {
                delegate.onBatchFailed(null, failureReason != null ? failureReason : "node failure");
            } catch (Exception ignored) {
                // Best-effort cleanup
            }
        }
        disarmCapture();
    }

    List<ItemStack> drainSettledResults() {
        if (delegate instanceof ParallelCraftGroup group) {
            return group.drainSettledResults();
        }
        return List.of();
    }

    List<ItemStack> drainQueuedMaterials() {
        if (delegate instanceof ParallelCraftGroup group) {
            return group.drainQueuedMaterialsForRecovery();
        }
        return List.of();
    }

    private void doSucceed() {
        if (terminal) return;
        terminal = true;
        // Drain settled results and capture before calling onBatchFinished
        drainSettledIntoVirtual();
        List<ItemStack> captured = disarmCapture();
        if (virtualInventory != null) {
            for (ItemStack s : captured) {
                if (s != null && !s.isEmpty()) virtualInventory.add(s.copy());
            }
        }
        List<ItemStack> results = collectResults(player);
        if (virtualInventory != null) {
            for (ItemStack s : results) {
                if (s != null && !s.isEmpty()) virtualInventory.add(s.copy());
            }
        }
        if (delegate != null) {
            try {
                delegate.onBatchFinished(player);
            } catch (Exception ignored) {
                // Best-effort
            }
        }
    }

    private void drainSettledIntoVirtual() {
        if (virtualInventory == null) return;
        List<ItemStack> settled = drainSettledResults();
        for (ItemStack s : settled) {
            if (s != null && !s.isEmpty()) virtualInventory.add(s.copy());
        }
        List<ItemStack> queued = drainQueuedMaterials();
        for (ItemStack s : queued) {
            if (s != null && !s.isEmpty()) virtualInventory.add(s.copy());
        }
    }

    List<ItemStack> collectResults(ServerPlayer player) {
        if (delegate == null) return List.of();
        return delegate.collectAllResults(player);
    }

    List<ItemStack> disarmCapture() {
        CraftOutputInterceptor.CaptureHandle handle = capture;
        capture = null;
        return handle == null ? List.of() : handle.drainAndClose();
    }

    boolean isDraining() {
        return stopRequested
                || (delegate instanceof ParallelCraftGroup group && group.isDraining());
    }

    String describe() {
        return nodeLabel + " (" + nodeId + ")";
    }
}
