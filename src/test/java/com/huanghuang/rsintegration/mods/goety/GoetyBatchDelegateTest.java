package com.huanghuang.rsintegration.mods.goety;

import com.huanghuang.rsintegration.crafting.batch.IBatchDelegate;
import com.huanghuang.rsintegration.crafting.plan.MachineCandidateView;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.network.chat.Component;
import net.minecraftforge.items.ItemStackHandler;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;

class GoetyBatchDelegateTest extends BootstrapTest {

    private static class Research {}
    private static final class AddonResearch extends Research {}
    private static final class ResearchApi {
        public static boolean hasResearch(Object player, Research research) {
            return player != null && research != null;
        }
        public boolean instanceOnly(Object player, Research research) {
            return true;
        }
    }

    @Test
    void matchesRuntimeNbtOutputByItemType() {
        ItemStack expected = new ItemStack(Items.IRON_LEGGINGS);
        ItemStack enchanted = expected.copy();
        CompoundTag tag = new CompoundTag();
        tag.putString("runtime_enchantment", "present");
        enchanted.setTag(tag);

        assertTrue(IBatchDelegate.matchesProducedItem(enchanted, expected));
        assertFalse(IBatchDelegate.matchesProducedItem(
                new ItemStack(Items.IRON_CHESTPLATE), expected));
    }

    @Test
    void claimsAltarOutputExactlyOnceForDeferredSettlement() {
        ItemStackHandler altar = new ItemStackHandler(1);
        altar.setStackInSlot(0, new ItemStack(Items.NETHERITE_INGOT));
        ItemStack expected = new ItemStack(Items.NETHERITE_INGOT);

        ItemStack claimed = GoetyBatchDelegate.claimMatchingOutput(altar, 0, expected);

        assertEquals(1, claimed.getCount());
        assertTrue(altar.getStackInSlot(0).isEmpty());
        assertTrue(GoetyBatchDelegate.claimMatchingOutput(altar, 0, expected).isEmpty());
    }

    @Test
    void doesNotClaimPartialOrWrongAltarOutput() {
        ItemStackHandler altar = new ItemStackHandler(1);
        altar.setStackInSlot(0, new ItemStack(Items.IRON_INGOT));

        assertTrue(GoetyBatchDelegate.claimMatchingOutput(
                altar, 0, new ItemStack(Items.IRON_INGOT, 2)).isEmpty());
        assertEquals(1, altar.getStackInSlot(0).getCount());
        assertTrue(GoetyBatchDelegate.claimMatchingOutput(
                altar, 0, new ItemStack(Items.GOLD_INGOT)).isEmpty());
        assertEquals(1, altar.getStackInSlot(0).getCount());
    }

    @Test
    void recognizesOnlyTaggedActivationScrollAsSubstituteResult() {
        ItemStack activation = new ItemStack(Items.PAPER);
        ItemStack result = activation.copy();
        result.getOrCreateTag().putString("GoeticLegacySub", "example:research");
        result.getOrCreateTag().putInt("GoeticLegacySubRerolls", 0);

        assertTrue(GoetyBatchDelegate.matchesGoeticLegacySubstituteResult(result, activation));
        assertFalse(GoetyBatchDelegate.matchesGoeticLegacySubstituteResult(activation, activation));
        assertFalse(GoetyBatchDelegate.matchesGoeticLegacySubstituteResult(
                new ItemStack(Items.BOOK), activation));

        ItemStack pendingPlaceholder = new ItemStack(Items.BOOK);
        pendingPlaceholder.getOrCreateTag().putBoolean("GoeticLegacySubPending", true);
        assertFalse(GoetyBatchDelegate.matchesGoeticLegacySubstituteResult(
                pendingPlaceholder, activation));
    }

    @Test
    void claimsTaggedSubstituteOutputExactlyOnce() {
        ItemStack activation = new ItemStack(Items.PAPER);
        ItemStack result = activation.copy();
        result.getOrCreateTag().putString("GoeticLegacySub", "example:research");
        ItemStackHandler altar = new ItemStackHandler(1);
        altar.setStackInSlot(0, result);

        ItemStack claimed = GoetyBatchDelegate.claimGoeticLegacySubstituteOutput(
                altar, 0, activation);

        assertTrue(GoetyBatchDelegate.matchesGoeticLegacySubstituteResult(claimed, activation));
        assertTrue(altar.getStackInSlot(0).isEmpty());
        assertTrue(GoetyBatchDelegate.claimGoeticLegacySubstituteOutput(
                altar, 0, activation).isEmpty());
    }

    @Test
    void pedestalInsertionMustBeCompleteAndObservable() {
        ItemStackHandler pedestal = new ItemStackHandler(1);
        ItemStack material = new ItemStack(Items.IRON_INGOT, 4);

        assertTrue(GoetyBatchDelegate.insertPedestalStack(pedestal, material));
        assertEquals(4, pedestal.getStackInSlot(0).getCount());
        assertFalse(GoetyBatchDelegate.insertPedestalStack(
                pedestal, new ItemStack(Items.GOLD_INGOT)));
        assertEquals(Items.IRON_INGOT, pedestal.getStackInSlot(0).getItem());
        assertTrue(GoetyBatchDelegate.insertPedestalStack(pedestal, ItemStack.EMPTY));
        assertTrue(pedestal.getStackInSlot(0).isEmpty());
    }

    @Test
    void researchLookupAcceptsApiParameterSuperclass() throws Exception {
        Object player = new Object();
        AddonResearch research = new AddonResearch();

        Method method = GoetyBatchDelegate.findCompatibleStaticMethod(
                ResearchApi.class, "hasResearch", player, research);

        assertTrue(method != null && Boolean.TRUE.equals(method.invoke(null, player, research)));
    }

    @Test
    void researchLookupNeverSelectsInstanceMethod() {
        assertTrue(GoetyBatchDelegate.findCompatibleStaticMethod(
                ResearchApi.class, "instanceOnly", new Object(), new Research()) == null);
    }

    @Test
    void sacrificeDisplayAcceptsGoetyStringAndComponents() {
        assertEquals("Zombie", GoetyBatchDelegate.displayComponent("Zombie").getString());
        assertEquals("Skeleton", GoetyBatchDelegate
                .displayComponent(Component.literal("Skeleton")).getString());
        assertEquals("?", GoetyBatchDelegate.displayComponent(null).getString());
    }

    @Test
    void verifiedAltarWinsOverOtherCandidates() {
        assertEquals(GoetyBatchDelegate.PlanStructureOutcome.VERIFIED,
                GoetyBatchDelegate.summarizePlanStructureProbes(List.of(
                        GoetyBatchDelegate.PlanStructureProbe.STRUCTURE_MISMATCH,
                        GoetyBatchDelegate.PlanStructureProbe.VERIFIED,
                        GoetyBatchDelegate.PlanStructureProbe.UNLOADED)));
    }

    @Test
    void unloadedAltarDefersPreviewInsteadOfBlocking() {
        assertEquals(GoetyBatchDelegate.PlanStructureOutcome.DEFERRED_UNLOADED,
                GoetyBatchDelegate.summarizePlanStructureProbes(List.of(
                        GoetyBatchDelegate.PlanStructureProbe.STRUCTURE_MISMATCH,
                        GoetyBatchDelegate.PlanStructureProbe.UNLOADED)));
    }

    @Test
    void explicitStructureMismatchStillBlocks() {
        assertEquals(GoetyBatchDelegate.PlanStructureOutcome.STRUCTURE_MISMATCH,
                GoetyBatchDelegate.summarizePlanStructureProbes(List.of(
                        GoetyBatchDelegate.PlanStructureProbe.INVALID_BINDING,
                        GoetyBatchDelegate.PlanStructureProbe.STRUCTURE_MISMATCH)));
    }

    @Test
    void emptyCandidateListReportsNoBinding() {
        assertEquals(GoetyBatchDelegate.PlanStructureOutcome.NO_BINDING,
                GoetyBatchDelegate.summarizePlanStructureProbes(List.of()));
    }

    @Test
    void machineSpecificPrerequisiteFailureRemainsRetryable() {
        assertEquals(IBatchDelegate.PreparationState.RETRY,
                GoetyBatchDelegate.prerequisiteFailureState(false));
        assertEquals(IBatchDelegate.PreparationState.FATAL,
                GoetyBatchDelegate.prerequisiteFailureState(true));
    }

    @Test
    void soulProbeAcceptsEnoughEnergy() {
        assertEquals(IBatchDelegate.PreparationState.READY,
                GoetyBatchDelegate.soulPreparationResult(100, 100).state());
    }

    @Test
    void removedSoulTotemProducesRetryableSpecificFailure() {
        IBatchDelegate.PreparationResult result =
                GoetyBatchDelegate.soulPreparationResult(100, 0);

        assertEquals(IBatchDelegate.PreparationState.RETRY, result.state());
        assertTrue(result.detail().contains("required=100"));
        assertEquals("rsi.goety.error.insufficient_souls",
                ((net.minecraft.network.chat.contents.TranslatableContents)
                        result.userMessage().getContents()).getKey());
    }

    @Test
    void readyAltarWinsWhenAnotherAltarHasNoTotem() {
        MachineCandidateView missingTotem = candidate(
                MachineCandidateView.State.TEMPORARY,
                GoetyBatchDelegate.soulPreparationResult(100, 0).userMessage());
        MachineCandidateView ready = candidate(
                MachineCandidateView.State.READY,
                Component.translatable("rsi.machine_candidate.ready"));

        assertEquals(ready, GoetyBatchDelegate.firstReadyMachine(
                List.of(missingTotem, ready)));
        assertTrue(GoetyBatchDelegate.hasReadyMachine(List.of(missingTotem, ready)));
    }

    @Test
    void allAltarsWithoutSoulTotemsHaveNoReadyCandidate() {
        MachineCandidateView first = candidate(MachineCandidateView.State.TEMPORARY,
                GoetyBatchDelegate.soulPreparationResult(100, 0).userMessage());
        MachineCandidateView second = candidate(MachineCandidateView.State.TEMPORARY,
                GoetyBatchDelegate.soulPreparationResult(100, 0).userMessage());

        assertNull(GoetyBatchDelegate.firstReadyMachine(List.of(first, second)));
        assertFalse(GoetyBatchDelegate.hasReadyMachine(List.of(first, second)));
    }

    private static MachineCandidateView candidate(
            MachineCandidateView.State state, Component status) {
        return new MachineCandidateView("minecraft:overworld", 0, 64, 0,
                ItemStack.EMPTY, state, status);
    }
}
