package com.huanghuang.rsintegration.crafting.graph;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OperationBudgetTest {

    @Test
    void activePermitLimitAndLifetimeStartLimitAreIndependent() {
        OperationBudget budget = new OperationBudget(2, 3);
        OperationBudget.Permit first = budget.tryAcquire();
        OperationBudget.Permit second = budget.tryAcquire();
        assertNotNull(first);
        assertNotNull(second);
        assertNull(budget.tryAcquire());
        assertEquals(2, budget.active());

        first.close();
        OperationBudget.Permit third = budget.tryAcquire();
        assertNotNull(third);
        assertEquals(2, budget.active());
        second.close();
        third.close();
        assertEquals(0, budget.active());
        assertNull(budget.tryAcquire(), "lifetime start budget must remain exhausted");
    }

    @Test
    void requestedCostIsBoundedByReportedCapacity() {
        OperationBudget budget = new OperationBudget(4, 10);
        OperationBudget.Permit first = budget.tryAcquire(3);
        assertNotNull(first);
        assertEquals(1, budget.availableCapacity());
        assertNull(budget.tryAcquire(2));
        OperationBudget.Permit second = budget.tryAcquire(budget.availableCapacity());
        assertNotNull(second);
        assertEquals(4, budget.active());
        first.close();
        second.close();
        assertEquals(0, budget.active());
    }

    @Test
    void reusedWorkerStartsAreRecordedAtomicallyAcrossScopes() {
        OperationBudget craft = new OperationBudget(1, 3);
        OperationBudget global = new OperationBudget(1, 2);
        OperationBudget.Permit craftPermit = craft.tryAcquire();
        OperationBudget.Permit globalPermit = global.tryAcquire();

        assertTrue(OperationBudget.tryRecordStart(craft, global));
        assertFalse(OperationBudget.tryRecordStart(craft, global));
        assertEquals(2, craft.starts());
        assertEquals(2, global.starts());
        assertEquals(1, craft.active());
        assertEquals(1, global.active());

        craftPermit.close();
        globalPermit.close();
    }

    @Test
    void cancelledPermitRestoresLifetimeStartAndActiveCapacity() {
        OperationBudget budget = new OperationBudget(1, 1);
        OperationBudget.Permit permit = budget.tryAcquire();

        permit.cancelBeforeStart();
        permit.cancelBeforeStart();

        assertEquals(0, budget.active());
        assertEquals(0, budget.starts());
        assertNotNull(budget.tryAcquire());
    }

    @Test
    void combinedPermitReleasesBothBudgetsExactlyOnce() {
        OperationBudget craft = new OperationBudget(1, 2);
        OperationBudget global = new OperationBudget(1, 2);
        OperationBudget.Permit combined = OperationBudget.combine(
                craft.tryAcquire(), global.tryAcquire());

        combined.close();
        combined.close();

        assertEquals(0, craft.active());
        assertEquals(0, global.active());
        assertNotNull(craft.tryAcquire());
        assertNotNull(global.tryAcquire());
    }
}
