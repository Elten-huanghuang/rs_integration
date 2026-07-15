package com.huanghuang.rsintegration.crafting.graph;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Atomically admits ready DAG nodes by reserving their material allocations.
 * Physical operation resources are owned exclusively by OperationExecutionKernel sessions.
 */
public final class NodeAdmissionCoordinator {

    public record Candidate(
            NodeId nodeId,
            List<MaterialBroker.Request> materialRequests
    ) {
        public Candidate {
            Objects.requireNonNull(nodeId, "nodeId");
            materialRequests = List.copyOf(materialRequests);
        }
    }

    public record Admission(
            NodeId nodeId,
            MaterialBroker.ReservationToken materialToken
    ) {
        public Admission {
            Objects.requireNonNull(nodeId, "nodeId");
            Objects.requireNonNull(materialToken, "materialToken");
        }
    }

    private final DagScheduler scheduler;
    private final MaterialBroker materials;

    public NodeAdmissionCoordinator(DagScheduler scheduler, MaterialBroker materials) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.materials = Objects.requireNonNull(materials, "materials");
    }

    /**
     * Admit one node that the caller has already claimed RUNNING. A null result
     * means a transient material conflict; the caller may release the claim and
     * retry on a later tick.
     */
    public Admission tryAdmitClaimed(Candidate candidate) {
        Objects.requireNonNull(candidate, "candidate");
        if (scheduler.isStopping()) return null;
        if (scheduler.state(candidate.nodeId()) != DagScheduler.NodeState.RUNNING) {
            throw new IllegalStateException("candidate must be claimed before admission: "
                    + candidate.nodeId());
        }
        return tryAdmit(candidate);
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
        scheduler.releaseClaim(admission.nodeId());
    }

    public void releaseMaterial(Admission admission) {
        materials.release(admission.materialToken());
    }

    public void settleMaterial(Admission admission) {
        materials.settle(admission.materialToken());
    }

    public void refundCommittedMaterial(Admission admission) {
        materials.refund(admission.materialToken());
    }

    public void succeed(Admission admission) {
        settleMaterial(admission);
        scheduler.succeed(admission.nodeId());
    }

    public void failAfterCommit(Admission admission) {
        refundCommittedMaterial(admission);
        scheduler.fail(admission.nodeId());
    }

    private Admission tryAdmit(Candidate candidate) {
        MaterialBroker.ReservationToken materialToken = materials.reserve(
                candidate.nodeId(), candidate.materialRequests());
        return materialToken == null ? null : new Admission(candidate.nodeId(), materialToken);
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
