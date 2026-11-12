package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.crafting.graph.CaptureLeaseRegistry;
import com.huanghuang.rsintegration.crafting.graph.MachineLeaseRegistry;
import com.huanghuang.rsintegration.crafting.graph.OperationBudget;

/** Operation ownership services scoped to one logical server generation. */
final class OperationServices {
    private final GlobalMaterialCoordinator materials = new GlobalMaterialCoordinator();
    GlobalMaterialCoordinator materials() { return materials; }

    private final MachineLeaseRegistry machines = new MachineLeaseRegistry();
    private final CaptureLeaseRegistry captures = new CaptureLeaseRegistry();
    private final OperationBudget globalBudget = new OperationBudget(
            Integer.MAX_VALUE, Integer.MAX_VALUE);
    private final OperationResourceCoordinator resources = new OperationResourceCoordinator(
            machines, captures, globalBudget);
    private final OperationExecutionKernel kernel = new OperationExecutionKernel(resources);

    MachineLeaseRegistry machines() { return machines; }
    CaptureLeaseRegistry captures() { return captures; }
    OperationBudget globalBudget() { return globalBudget; }
    OperationResourceCoordinator resources() { return resources; }
    OperationExecutionKernel kernel() { return kernel; }

    Audit audit(int activeCrafts) {
        return new Audit(activeCrafts, globalBudget.active(), machines.size(),
                captures.size(), CraftOutputInterceptor.activeZoneCount(), materials.size());
    }

    record Audit(int activeCrafts, int activeOperations, int machineLeases,
                 int captureLeases, int captureZones, int materialAccounts) {
        boolean clean() {
            return activeCrafts == 0 && activeOperations == 0 && machineLeases == 0
                    && captureLeases == 0 && captureZones == 0 && materialAccounts == 0;
        }
    }
}
