package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.crafting.graph.CaptureLeaseRegistry;
import com.huanghuang.rsintegration.crafting.graph.MachineLeaseRegistry;
import com.huanghuang.rsintegration.crafting.graph.MaterialKey;
import com.huanghuang.rsintegration.crafting.graph.NodeId;
import com.huanghuang.rsintegration.crafting.graph.OperationBudget;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Atomically owns the resources needed by one physical delegate start. */
public final class OperationResourceCoordinator {
    private final MachineLeaseRegistry machines;
    private final CaptureLeaseRegistry captures;
    private final OperationBudget globalBudget;

    public OperationResourceCoordinator(MachineLeaseRegistry machines,
                                        CaptureLeaseRegistry captures,
                                        OperationBudget globalBudget) {
        this.machines = Objects.requireNonNull(machines, "machines");
        this.captures = Objects.requireNonNull(captures, "captures");
        this.globalBudget = Objects.requireNonNull(globalBudget, "globalBudget");
    }

    @Nullable
    public Scope tryAcquireBudget(OperationBudget craftBudget) {
        OperationBudget.Permit craftPermit = craftBudget.tryAcquire();
        if (craftPermit == null) return null;
        OperationBudget.Permit globalPermit = globalBudget.tryAcquire();
        if (globalPermit == null) {
            craftPermit.cancelBeforeStart();
            return null;
        }
        return new Scope(null, List.of(),
                OperationBudget.combine(craftPermit, globalPermit), null);
    }

    @Nullable
    public Scope tryAcquire(UUID craftId, NodeId nodeId, int operationId,
                            OperationBudget craftBudget,
                            MachineLeaseRegistry.MachineKey machine,
                            @Nullable CaptureRequest capture) {
        return tryAcquire(craftId, nodeId, operationId, craftBudget, List.of(machine), capture);
    }

    @Nullable
    public Scope tryAcquire(UUID craftId, NodeId nodeId, int operationId,
                            OperationBudget craftBudget,
                            List<MachineLeaseRegistry.MachineKey> machineScope,
                            @Nullable CaptureRequest capture) {
        OperationBudget.Permit craftPermit = craftBudget.tryAcquire();
        if (craftPermit == null) return null;
        OperationBudget.Permit globalPermit = globalBudget.tryAcquire();
        if (globalPermit == null) {
            craftPermit.cancelBeforeStart();
            return null;
        }
        OperationBudget.Permit permit = OperationBudget.combine(craftPermit, globalPermit);
        MachineLeaseRegistry.Owner machineOwner = new MachineLeaseRegistry.Owner(
                craftId, nodeId, operationId);
        List<MachineLeaseRegistry.Lease> machineLeases = machines.tryAcquireAll(
                machineScope, machineOwner);
        if (machineLeases == null) {
            permit.cancelBeforeStart();
            return null;
        }

        CaptureSession captureSession = null;
        if (capture != null) {
            CaptureLeaseRegistry.Owner captureOwner = new CaptureLeaseRegistry.Owner(
                    craftId, nodeId, operationId);
            CaptureLeaseRegistry.Lease captureLease = captures.tryAcquire(
                    capture.dimension(), capture.region(), MaterialKey.of(capture.expected()), captureOwner);
            if (captureLease == null) {
                machines.releaseAll(machineLeases);
                permit.cancelBeforeStart();
                return null;
            }
            ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, capture.dimension());
            CraftOutputInterceptor.CaptureHandle handle = CraftOutputInterceptor.arm(
                    dimension, capture.region(), capture.expected());
            if (handle == null) {
                captures.release(captureLease);
                machines.releaseAll(machineLeases);
                permit.cancelBeforeStart();
                return null;
            }
            captureSession = new CaptureSession(captures, captureLease, handle);
        }
        return new Scope(machines, machineLeases, permit, captureSession);
    }

    public record CaptureRequest(ResourceLocation dimension, AABB region, ItemStack expected) {
        public CaptureRequest {
            Objects.requireNonNull(dimension, "dimension");
            Objects.requireNonNull(region, "region");
            Objects.requireNonNull(expected, "expected");
            if (expected.isEmpty()) throw new IllegalArgumentException("expected output must be non-empty");
            expected = expected.copy();
        }
    }

    public static final class Scope implements AutoCloseable {
        private MachineLeaseRegistry registry;
        private List<MachineLeaseRegistry.Lease> machineLeases;
        private OperationBudget.Permit permit;
        private CaptureSession capture;
        private boolean startAttempted;

        private Scope(@Nullable MachineLeaseRegistry registry,
                      @Nullable List<MachineLeaseRegistry.Lease> machineLeases,
                      OperationBudget.Permit permit, @Nullable CaptureSession capture) {
            this.registry = registry;
            this.machineLeases = machineLeases;
            this.permit = permit;
            this.capture = capture;
        }

        public MachineLeaseRegistry.Lease machineLease() {
            return machineLeases == null || machineLeases.isEmpty() ? null : machineLeases.get(0);
        }

        public List<MachineLeaseRegistry.Lease> machineLeases() {
            return machineLeases == null ? List.of() : machineLeases;
        }

        public void markStartAttempted() {
            startAttempted = true;
        }

        public boolean hasCaptured() {
            return capture != null && capture.hasCaptured();
        }

        public List<ItemStack> capturedSnapshot() {
            return capture == null ? List.of() : capture.snapshot();
        }

        public List<ItemStack> drainCapture() {
            CaptureSession current = capture;
            capture = null;
            return current == null ? List.of() : current.drainAndClose();
        }

        @Override
        public void close() {
            drainCapture();
            if (machineLeases != null && registry != null) {
                registry.releaseAll(machineLeases);
                machineLeases = null;
            }
            if (permit != null) {
                if (startAttempted) permit.close();
                else permit.cancelBeforeStart();
                permit = null;
            }
        }
    }
}
