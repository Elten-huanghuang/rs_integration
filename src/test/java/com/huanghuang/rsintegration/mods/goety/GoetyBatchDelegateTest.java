package com.huanghuang.rsintegration.mods.goety;

import com.huanghuang.rsintegration.crafting.batch.IBatchDelegate;
import com.huanghuang.rsintegration.crafting.plan.MachineCandidateView;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import com.huanghuang.rsintegration.crafting.batch.AbstractBatchDelegate;
import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.items.IItemHandler;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.network.chat.Component;
import net.minecraftforge.items.ItemStackHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.mockito.MockedStatic;

import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

class GoetyBatchDelegateTest extends BootstrapTest {
    @BeforeAll
    static void initializeModRuntime() throws Exception {
        FMLJavaModLoadingContext context = mock(FMLJavaModLoadingContext.class);
        when(context.getModEventBus()).thenReturn(mock(IEventBus.class));
        try (MockedStatic<FMLJavaModLoadingContext> loading = mockStatic(FMLJavaModLoadingContext.class)) {
            loading.when(FMLJavaModLoadingContext::get).thenReturn(context);
            Class.forName("com.huanghuang.rsintegration.RSIntegrationMod");
        }
    }
    public static class RitualHolder extends BlockEntity {
        final ItemStackHandler inventory = new ItemStackHandler(1);
        public final LazyOptional<IItemHandler> itemStackHandler = LazyOptional.of(() -> inventory);
        public Object currentRitualRecipe;
        public Object currentRitualRecipeId;
        public int stops;

        RitualHolder(BlockPos pos) {
            super(BlockEntityType.CHEST, pos, Blocks.CHEST.defaultBlockState());
        }

        public void stopRitual(boolean completed) {
            assertTrue(inventory.getStackInSlot(0).isEmpty());
            stops++;
            currentRitualRecipe = null;
        }

        @Override public void setChanged() {}
    }

    public static final class BrazierHolder extends RitualHolder {
        final NonNullList<ItemStack> items = NonNullList.withSize(3, ItemStack.EMPTY);
        public Object recipe;
        public Object recipeId;
        public int currentTime;

        BrazierHolder(BlockPos pos) { super(pos); }
        public int getContainerSize() { return items.size(); }
        public NonNullList<ItemStack> getItems() { return items; }
        public ItemStack getItem(int slot) { return items.get(slot); }
        public ItemStack removeItem(int slot, int amount) {
            return items.get(slot).split(amount);
        }
        public void stopBrazier(boolean completed) {
            recipe = null;
            recipeId = null;
            currentTime = 0;
        }
    }

    @Test
    void offlineCancellationRecoversOnlyRemainingOwnedInputsAndStopsRitualOnce() throws Exception {
        ServerLevel level = mock(ServerLevel.class);
        RitualHolder altar = holder(level, BlockPos.ZERO);
        RitualHolder remaining = holder(level, BlockPos.ZERO.east());
        RitualHolder consumed = holder(level, BlockPos.ZERO.west());
        RitualHolder replaced = holder(level, BlockPos.ZERO.north());
        ItemStack material = tagged(3);
        remaining.inventory.setStackInSlot(0, material.copyWithCount(2));
        replaced.inventory.setStackInSlot(0, new ItemStack(Items.GOLD_INGOT));
        altar.inventory.setStackInSlot(0, new ItemStack(Items.PAPER));
        Object recipe = new Object();
        altar.currentRitualRecipe = recipe;
        altar.currentRitualRecipeId = new ResourceLocation("test", "ritual");

        GoetyBatchDelegate delegate = offlineDelegate(level, altar);
        set(delegate, "altar", altar);
        set(delegate, "ritualRecipe", recipe);
        set(delegate, "altarStartAttempted", true);
        set(delegate, "activationPlacedInAltar", true);
        set(delegate, "ritualActivationTemplate", new ItemStack(Items.PAPER));
        set(delegate, "filledPedestals", new ArrayList<>(List.of(
                new GoetyBatchDelegate.PlacedPedestal(remaining, material.copy()),
                new GoetyBatchDelegate.PlacedPedestal(consumed, material.copy()),
                new GoetyBatchDelegate.PlacedPedestal(replaced, material.copy()))));

        delegate.onBatchFailed(null, "offline");
        delegate.onBatchFailed(null, "repeat");

        List<ItemStack> recovered = delegate.failureRecoveredInputs();
        assertEquals(2, recovered.size());
        assertTrue(ItemStack.isSameItemSameTags(material, recovered.get(0)));
        assertEquals(2, recovered.get(0).getCount());
        assertEquals(Items.PAPER, recovered.get(1).getItem());
        assertTrue(remaining.inventory.getStackInSlot(0).isEmpty());
        assertEquals(Items.GOLD_INGOT, replaced.inventory.getStackInSlot(0).getItem());
        assertTrue(altar.inventory.getStackInSlot(0).isEmpty());
        assertEquals(1, altar.stops);
        assertNull(altar.currentRitualRecipeId);
        assertTrue(delegate.physicalFailureCleanupCompleted());
    }

    @Test
    void cancellationDoesNotTouchReplacedBlockEntityOrAnotherRitual() throws Exception {
        ServerLevel level = mock(ServerLevel.class);
        RitualHolder altar = holder(level, BlockPos.ZERO);
        RitualHolder stale = holder(level, BlockPos.ZERO.east());
        stale.inventory.setStackInSlot(0, tagged(1));
        when(level.getBlockEntity(stale.getBlockPos())).thenReturn(mock(BlockEntity.class));
        altar.inventory.setStackInSlot(0, new ItemStack(Items.PAPER));
        altar.currentRitualRecipe = new Object();
        GoetyBatchDelegate delegate = offlineDelegate(level, altar);
        set(delegate, "altar", altar);
        set(delegate, "ritualRecipe", new Object());
        set(delegate, "altarStartAttempted", true);
        set(delegate, "ritualActivationTemplate", new ItemStack(Items.PAPER));
        set(delegate, "filledPedestals", List.of(
                new GoetyBatchDelegate.PlacedPedestal(stale, tagged(1))));

        delegate.onBatchFailed(null, "offline");

        assertTrue(delegate.failureRecoveredInputs().isEmpty());
        assertEquals(1, stale.inventory.getStackInSlot(0).getCount());
        assertEquals(1, altar.inventory.getStackInSlot(0).getCount());
        assertEquals(0, altar.stops);
    }

    @Test
    void missingAltarStillRecoversUnplacedInputs() throws Exception {
        ServerLevel level = mock(ServerLevel.class);
        RitualHolder altar = holder(level, BlockPos.ZERO);
        GoetyBatchDelegate delegate = offlineDelegate(level, altar);
        Field pending = GoetyBatchDelegate.class.getDeclaredField("unplacedRitualInputs");
        pending.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<ItemStack> inputs = (List<ItemStack>) pending.get(delegate);
        inputs.add(tagged(2));
        when(level.getBlockEntity(BlockPos.ZERO)).thenReturn(null);

        delegate.onBatchFailed(null, "missing");

        assertEquals(2, delegate.failureRecoveredInputs().get(0).getCount());
    }

    @Test
    void reservedActivationIsNotRecoverableBeforeCommit() throws Exception {
        ServerLevel level = mock(ServerLevel.class);
        RitualHolder altar = holder(level, BlockPos.ZERO);
        GoetyBatchDelegate delegate = offlineDelegate(level, altar);
        ServerPlayer player = mock(ServerPlayer.class);
        ExtractionLedger ledger = new ExtractionLedger();
        set(delegate, "player", player);
        set(delegate, "altar", altar);
        Field ledgerField = AbstractBatchDelegate.class.getDeclaredField("ledger");
        ledgerField.setAccessible(true);
        ledgerField.set(delegate, ledger);
        Ingredient ingredient = Ingredient.of(Items.PAPER);
        try (MockedStatic<CraftPacketUtils> crafting = mockStatic(CraftPacketUtils.class)) {
            crafting.when(() -> CraftPacketUtils.ensureMaterialAvailable(
                    eq(player), any(), eq(BlockPos.ZERO), eq(ingredient), eq(1), eq(ledger)))
                    .thenReturn(new ItemStack(Items.PAPER));
            Method extract = GoetyBatchDelegate.class.getDeclaredMethod(
                    "extractActivation", Ingredient.class);
            extract.setAccessible(true);
            assertEquals(Items.PAPER, ((ItemStack) extract.invoke(delegate, ingredient)).getItem());
        }

        delegate.onBatchFailed(null, "not committed");

        assertTrue(delegate.failureRecoveredInputs().isEmpty());
    }

    @Test
    void brazierCancellationAuditsSurvivorsWithoutRefundingConsumedOrForeignItems() throws Exception {
        ServerLevel level = mock(ServerLevel.class);
        BrazierHolder brazier = new BrazierHolder(BlockPos.ZERO);
        brazier.setLevel(level);
        when(level.getBlockEntity(BlockPos.ZERO)).thenReturn(brazier);
        brazier.items.set(0, tagged(2));
        brazier.items.set(2, new ItemStack(Items.GOLD_INGOT));
        brazier.recipe = new Object();
        brazier.recipeId = new ResourceLocation("test", "brazier");
        brazier.currentTime = 100;
        GoetyBatchDelegate delegate = offlineDelegate(level, brazier);
        set(delegate, "isBrazier", true);
        set(delegate, "brazier", brazier);
        set(delegate, "brazierRecipeObj", brazier.recipe);
        delegate.rememberBrazierPlacement(List.of(tagged(3), tagged(1), tagged(1)));

        delegate.onBatchFailed(null, "offline");

        assertEquals(1, delegate.failureRecoveredInputs().size());
        assertEquals(2, delegate.failureRecoveredInputs().get(0).getCount());
        assertTrue(brazier.items.get(0).isEmpty());
        assertEquals(Items.GOLD_INGOT, brazier.items.get(2).getItem());
        assertNull(brazier.recipe);
        assertNull(brazier.recipeId);
        assertEquals(0, brazier.currentTime);
    }

    private static RitualHolder holder(ServerLevel level, BlockPos pos) {
        RitualHolder holder = new RitualHolder(pos);
        holder.setLevel(level);
        when(level.hasChunkAt(pos)).thenReturn(true);
        when(level.getBlockEntity(pos)).thenReturn(holder);
        return holder;
    }

    private static GoetyBatchDelegate offlineDelegate(ServerLevel level, BlockEntity machine)
            throws Exception {
        MinecraftServer server = mock(MinecraftServer.class);
        when(server.getLevel(Level.OVERWORLD)).thenReturn(level);
        when(level.hasChunkAt(machine.getBlockPos())).thenReturn(true);
        GoetyBatchDelegate delegate = new GoetyBatchDelegate();
        delegate.setMachineDim(Level.OVERWORLD.location());
        delegate.setMachineServer(server);
        set(delegate, "myPos", machine.getBlockPos());
        Field shared = AbstractBatchDelegate.class.getDeclaredField("usingSharedLedger");
        shared.setAccessible(true);
        shared.set(delegate, true);
        return delegate;
    }

    private static ItemStack tagged(int count) {
        ItemStack stack = new ItemStack(Items.IRON_INGOT, count);
        stack.getOrCreateTag().putString("owner", "ritual");
        return stack;
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

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
    void soulProbeCombinesTaskReservationWithAltarEnergy() {
        int available = GoetyBatchDelegate.combinedSoulAvailability(50, 0, 50);

        assertEquals(100, available);
        assertEquals(IBatchDelegate.PreparationState.READY,
                GoetyBatchDelegate.soulPreparationResult(100, available).state());
    }

    @Test
    void soulProbeCombinesUnreservedStorageWithAltarEnergy() {
        assertEquals(100,
                GoetyBatchDelegate.combinedSoulAvailability(50, 50, 0));
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

    @Test
    void temporaryCandidatesRemainRetryableWhenNoAltarIsReady() {
        MachineCandidateView temporary = candidate(MachineCandidateView.State.TEMPORARY,
                GoetyBatchDelegate.soulPreparationResult(100, 0).userMessage());

        assertTrue(GoetyBatchDelegate.hasTemporaryMachine(List.of(temporary)));
    }

    private static MachineCandidateView candidate(
            MachineCandidateView.State state, Component status) {
        return new MachineCandidateView("minecraft:overworld", 0, 64, 0,
                ItemStack.EMPTY, state, status);
    }
}
