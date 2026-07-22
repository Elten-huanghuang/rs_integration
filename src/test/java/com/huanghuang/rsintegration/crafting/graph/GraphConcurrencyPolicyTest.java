package com.huanghuang.rsintegration.crafting.graph;

import com.huanghuang.rsintegration.crafting.batch.BatchConcurrencyCapabilities;
import com.huanghuang.rsintegration.crafting.batch.IBatchDelegate;
import com.huanghuang.rsintegration.mods.crockpot.CrockPotBatchDelegate;
import com.huanghuang.rsintegration.mods.farmersdelight.CookingPotBatchDelegate;
import com.huanghuang.rsintegration.mods.vanilla.brewing.BrewingStandBatchDelegate;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphConcurrencyPolicyTest {

    @Test
    void disabledModOverridesDelegateCapability() {
        assertTrue(GraphConcurrencyPolicy.isModDisabled("malum", List.of(" MALUM ", "goety")));
        assertFalse(GraphConcurrencyPolicy.isModDisabled("embers", List.of("malum")));
        assertTrue(GraphConcurrencyPolicy.decide("test", new FakeDelegate(safe()),
                List.of("test"), List.of()).exclusive());
    }

    @Test
    void unknownAndLegacyOnlyDelegatesStayExclusive() {
        assertTrue(GraphConcurrencyPolicy.isExclusive("test", null));
        GraphConcurrencyPolicy.Decision unknown = GraphConcurrencyPolicy.decide(
                "test", new FakeDelegate(null), List.of(), List.of());
        assertTrue(unknown.exclusive());
        assertEquals("legacy boolean lacks capability contract", unknown.reason());
    }

    @Test
    void completeMachineLocalCapabilityCanRunConcurrently() {
        GraphConcurrencyPolicy.Decision decision = GraphConcurrencyPolicy.decide(
                "test", new FakeDelegate(safe()), List.of(), List.of());
        assertFalse(decision.exclusive());
    }

    @Test
    void localWorldRemaindersRemainConcurrencySafe() {
        GraphConcurrencyPolicy.Decision decision = GraphConcurrencyPolicy.decide(
                "test", new FakeDelegate(BatchConcurrencyCapabilities.machineSlotWithLocalWorldItems()),
                List.of(), List.of());

        assertFalse(decision.exclusive());
        assertEquals(BatchConcurrencyCapabilities.SideEffects.LOCAL_WORLD_ITEMS,
                decision.capabilities().sideEffects());
        assertEquals(BatchConcurrencyCapabilities.OutputOwnership.MACHINE_SLOT,
                decision.capabilities().outputOwnership());
    }

    @Test
    void realCookingDelegatesKeepOnlyProvenConcurrencyContracts() {
        GraphConcurrencyPolicy.Decision farmersDelight = GraphConcurrencyPolicy.decide(
                "farmersdelight_cooking_pot", new CookingPotBatchDelegate(), List.of(), List.of());
        GraphConcurrencyPolicy.Decision crockPot = GraphConcurrencyPolicy.decide(
                "crockpot", new CrockPotBatchDelegate(), List.of(), List.of());

        assertFalse(farmersDelight.exclusive());
        assertEquals(BatchConcurrencyCapabilities.SideEffects.LOCAL_WORLD_ITEMS,
                farmersDelight.capabilities().sideEffects());
        assertTrue(crockPot.exclusive());
        assertEquals("delegate has no concurrency capability", crockPot.reason());
    }

    @Test
    void vanillaBrewingStandCanUseIndependentMachineSlotsConcurrently() {
        GraphConcurrencyPolicy.Decision decision = GraphConcurrencyPolicy.decide(
                null, new BrewingStandBatchDelegate(), List.of(), List.of());

        assertFalse(decision.exclusive());
        assertEquals(BatchConcurrencyCapabilities.OutputOwnership.MACHINE_SLOT,
                decision.capabilities().outputOwnership());
        assertEquals(BatchConcurrencyCapabilities.SideEffects.MACHINE_LOCAL,
                decision.capabilities().sideEffects());
    }

    @Test
    void unsafeCapabilityDimensionsRemainExclusiveEvenWhenForced() {
        BatchConcurrencyCapabilities unsafe = new BatchConcurrencyCapabilities(
                BatchConcurrencyCapabilities.MaterialOwnership.SELF_EXTRACTING,
                BatchConcurrencyCapabilities.OutputOwnership.OWNED_WORLD_CAPTURE,
                BatchConcurrencyCapabilities.CleanupContract.SEPARABLE_OFFLINE,
                BatchConcurrencyCapabilities.SideEffects.WORLD_GLOBAL,
                BatchConcurrencyCapabilities.PreparationContract.RELIABLE,
                List.of());
        GraphConcurrencyPolicy.Decision decision = GraphConcurrencyPolicy.decide(
                "test", new FakeDelegate(unsafe), List.of(),
                List.of("test=FORCE_WITH_GUARDS"));
        assertTrue(decision.exclusive());
        assertEquals("materials are not fully chain-reserved", decision.reason());
    }

    @Test
    void delegateSpecificOffOverridesModAuto() {
        GraphConcurrencyPolicy.Decision decision = GraphConcurrencyPolicy.decide(
                "test", new FakeDelegate(safe()), List.of(),
                List.of("test=AUTO", "FakeDelegate=OFF"));
        assertTrue(decision.exclusive());
        assertEquals("delegate policy is OFF", decision.reason());
    }

    private static BatchConcurrencyCapabilities safe() {
        return BatchConcurrencyCapabilities.machineSlot();
    }

    private static final class FakeDelegate implements IBatchDelegate {
        private final BatchConcurrencyCapabilities capabilities;

        private FakeDelegate(BatchConcurrencyCapabilities capabilities) {
            this.capabilities = capabilities;
        }

        @Override public BatchConcurrencyCapabilities concurrencyCapabilities() { return capabilities; }
        @Override public boolean supportsConcurrentNodeExecution() { return true; }
        @Override public boolean validateAndInit(ServerPlayer p, ResourceLocation r, ResourceLocation d, BlockPos pos) { return false; }
        @Override public boolean tryStartSingleCraft(ServerPlayer p) { return false; }
        @Override public boolean isCraftComplete(ServerLevel level) { return false; }
        @Override public ItemStack collectResult(ServerPlayer p) { return ItemStack.EMPTY; }
        @Override public void onBatchFailed(ServerPlayer p, String reason) { }
        @Override public void onBatchFinished(ServerPlayer p) { }
        @Override public BlockPos getMachinePos() { return BlockPos.ZERO; }
    }
}
