package com.huanghuang.rsintegration.recipe;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.crafting.batch.HandlerVirtualExchangeBatchDelegate;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InstantRecipePolicyTest extends BootstrapTest {

    @Test
    void noneKeepsTheExistingSingleOperationPath() {
        InstantRecipePolicy policy = InstantRecipePolicy.none();

        assertFalse(policy.enabled());
        assertEquals(1, policy.limit(1000));
    }

    @Test
    void directLedgerTransactionUsesTheWholeRemainingStepByDefault() {
        InstantRecipePolicy policy = InstantRecipePolicy.ledgerTransactionPolicy();

        assertTrue(policy.enabled());
        assertTrue(policy.ledgerTransaction());
        assertEquals(1000, policy.limit(1000));
    }

    @Test
    void simulatedAssemblyKeepsItsExplicitMemoryBound() {
        InstantRecipePolicy policy = InstantRecipePolicy.simulatedAssembly(64);

        assertTrue(policy.simulatedAssembly());
        assertEquals(64, policy.limit(1000));
        assertEquals(17, policy.limit(17));
    }

    @Test
    void fluidContainerUsesTheSharedHandlerExchangeRoute() {
        assertInstanceOf(HandlerVirtualExchangeBatchDelegate.class,
                ModType.FLUID_CONTAINER.createDelegate());
    }
}
