package com.huanghuang.rsintegration.crafting.graph;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Atomically admits ready DAG nodes into the running set.
 * Physical ledgers and delegates are attached only after this logical gate succeeds.
 */
public final class NodeAdmissionCoordinator {

    public record Candidate(
            NodeId nodeId,
            List<MaterialBroker.Request> materialRequests,
            List<MachineRequest> machines,
            List<CaptureRequest> captures
    ) {
        public Candidate {
            Objects.requireNonNull(nodeId, "nodeId");
            materialRequests = List.copyOf(materialRequests);
            machines = List.copyOf(machines);
            captures = List.copyOf(captures);
        }
    }

    public record MachineRequest(MachineLeaseRegistry.MachineKey machine, int operationId) {
        public MachineRequest {
            Objects.requireNonNull(machine, "machine");
            if (operationId < 0) throw new IllegalArgumentException("operation id must be non-negative");
        }
    }

    public record CaptureRequest(
            net.minecraft.resources.ResourceLocation dimension,
            net.minecraft.world.phys.AABB region,
            MaterialKey expectedMaterial,
            int operationId
    ) {
        public CaptureRequest {
            Objects.requireNonNull(dimension, "dimension");
            Objects.requireNonNull(region, "region");
            Objects.requireNonNull(expectedMaterial, "expectedMaterial");
            if (operationId < 0) throw new IllegalArgumentException("operation id must be non-negative");
        }
    }

    public record Admission(
            NodeId nodeId,
            MaterialBroker.ReservationToken materialToken,
            List<MachineLeaseRegistry.Lease> machineLeases,
            List<CaptureLeaseRegistry.Lease> captureLeases
    ) {
        public Admission {
            Objects.requireNonNull(nodeId, "nodeId");
            Objects.requireNonNull(materialToken, "materialToken");
            machineLeases = List.copyOf(machineLeases);
            captureLeases = List.copyOf(captureLeases);
        }
    }

    private final java.util.UUID craftId;
    private final DagScheduler scheduler;
    private final MaterialBroker materials;
    private final MachineLeaseRegistry machines;
    private final CaptureLeaseRegistry captures;

    public NodeAdmissionCoordinator(java.util.UUID craftId, DagScheduler scheduler,
                                    MaterialBroker materials, MachineLeaseRegistry machines,
                                    CaptureLeaseRegistry captures) {
        this.craftId = Objects.requireNonNull(craftId, "craftId");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.materials = Objects.requireNonNull(materials, "materials");
        this.machines = Objects.requireNonNull(machines, "machines");
        this.captures = Objects.requireNonNull(captures, "captures");
    }

    public List<Admission> admit(List<Candidate> candidates, int limit) {
        if (limit <= 0 || scheduler.isStopping()) return List.of();
        List<NodeId> claimed = scheduler.claimReady(Math.min(limit, candidates.size()));
        List<Admission> admitted = new ArrayList<>();
        int candidateIndex = 0;
        for (NodeId claimedNode : claimed) {
            Candidate candidate = nextCandidate(candidates, claimedNode, candidateIndex);
            if (candidate == null) {
                scheduler.releaseClaim(claimedNode);
                continue;
            }
            candidateIndex = candidates.indexOf(candidate) + 1;
            Admission admission = tryAdmit(candidate);
            if (admission == null) {
                scheduler.releaseClaim(claimedNode);
            } else {
                admitted.add(admission);
            }
        }
        return List.copyOf(admitted);
    }

    public void commit(Admission admission) {
        materials.commit(admission.materialToken());
    }

    public void releaseBeforeDispatch(Admission admission) {
        materials.release(admission.materialToken());
        releaseLeases(admission);
        scheduler.releaseClaim(admission.nodeId());
    }

    public void succeed(Admission admission) {
        materials.settle(admission.materialToken());
        releaseLeases(admission);
        scheduler.succeed(admission.nodeId());
    }

    public void failAfterCommit(Admission admission) {
        materials.refund(admission.materialToken());
        releaseLeases(admission);
        scheduler.fail(admission.nodeId());
    }

    private Admission tryAdmit(Candidate candidate) {
        MaterialBroker.ReservationToken materialToken = materials.reserve(
                candidate.nodeId(), candidate.materialRequests());
        if (materialToken == null) return null;

        List<MachineLeaseRegistry.Lease> machineLeases = new ArrayList<>();
        List<CaptureLeaseRegistry.Lease> captureLeases = new ArrayList<>();
        for (MachineRequest request : candidate.machines()) {
            MachineLeaseRegistry.Lease lease = machines.tryAcquire(request.machine(),
                    new MachineLeaseRegistry.Owner(craftId, candidate.nodeId(), request.operationId()));
            if (lease == null) {
                rollbackAdmission(materialToken, machineLeases, captureLeases);
                return null;
            }
            machineLeases.add(lease);
        }
        for (CaptureRequest request : candidate.captures()) {
            CaptureLeaseRegistry.Lease lease = captures.tryAcquire(request.dimension(), request.region(),
                    request.expectedMaterial(), new CaptureLeaseRegistry.Owner(
                            craftId, candidate.nodeId(), request.operationId()));
            if (lease == null) {
                rollbackAdmission(materialToken, machineLeases, captureLeases);
                return null;
            }
            captureLeases.add(lease);
        }
        return new Admission(candidate.nodeId(), materialToken, machineLeases, captureLeases);
    }

    private void rollbackAdmission(MaterialBroker.ReservationToken materialToken,
                                   List<MachineLeaseRegistry.Lease> machineLeases,
                                   List<CaptureLeaseRegistry.Lease> captureLeases) {
        for (CaptureLeaseRegistry.Lease lease : captureLeases) captures.release(lease);
        for (MachineLeaseRegistry.Lease lease : machineLeases) machines.release(lease);
        materials.release(materialToken);
    }

    private void releaseLeases(Admission admission) {
        for (CaptureLeaseRegistry.Lease lease : admission.captureLeases()) captures.release(lease);
        for (MachineLeaseRegistry.Lease lease : admission.machineLeases()) machines.release(lease);
    }

    private static Candidate nextCandidate(List<Candidate> candidates, NodeId nodeId, int start) {
        for (int i = Math.max(0, start); i < candidates.size(); i++) {
            Candidate candidate = candidates.get(i);
            if (candidate.nodeId().equals(nodeId)) return candidate;
        }
        for (int i = 0; i < Math.min(start, candidates.size()); i++) {
            Candidate candidate = candidates.get(i);
            if (candidate.nodeId().equals(nodeId)) return candidate;
        }
        return null;
    }
}
