package com.huanghuang.rsintegration.mods.apprenticecodex;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.batch.AbstractBatchDelegate;
import com.huanghuang.rsintegration.crafting.batch.BatchConcurrencyCapabilities;
import com.huanghuang.rsintegration.crafting.batch.InputBufferContract;
import com.huanghuang.rsintegration.crafting.batch.InputBufferPlan;
import com.huanghuang.rsintegration.crafting.batch.OutputAccounting;
import com.huanghuang.rsintegration.crafting.batch.OutputContract;
import com.huanghuang.rsintegration.crafting.batch.ParallelBatchSizing;
import com.huanghuang.rsintegration.recipe.ModRecipeHandlers;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.registries.ForgeRegistries;
import java.util.Arrays;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

public final class ApprenticeCodexEssenceSmokerBatchDelegate extends AbstractBatchDelegate {
    private static final String BE_CLASS =
            "jp.aquafactory.apprenticecodex.block.essencesmoker.EssenceSmokerBlockEntity";
    private static final int MAX_MATERIAL_COUNT =
            ApprenticeCodexRecipeHandler.ESSENCE_SMOKER_MATERIAL_SLOTS;
    /** IDs must match MaterialPlan.fromLegacy's stable compatibility IDs. */
    static final String CATALYST_ENTRY_ID = "legacy:material:0";
    static final String MATERIAL_ENTRY_ID = "legacy:material:1";

    private ServerLevel level;
    private ResourceKey<Level> dimension;
    private BlockPos pos;
    private Recipe<?> recipe;
    private ItemStack expected = ItemStack.EMPTY;
    private final List<ItemStack> results = new ArrayList<>();
    private boolean started;
    private int activeMaterialCount;

    @Override
    public boolean validateAndInit(@Nonnull ServerPlayer player, @Nonnull ResourceLocation recipeId,
                                   @Nullable ResourceLocation dim, @Nonnull BlockPos pos) {
        ServerLevel resolved = CraftPacketUtils.resolveLevel(player.server, dim, player);
        Recipe<?> found = resolved == null ? null : resolved.getRecipeManager().byKey(recipeId).orElse(null);
        if (resolved == null || found == null
                || !found.getClass().getName().equals(ApprenticeCodexRSModule.ESSENCE_RECIPE)
                || !isMachine(resolved, pos)) return false;
        this.level = resolved;
        this.dimension = resolved.dimension();
        this.pos = pos.immutable();
        this.recipe = found;
        this.expected = found.getResultItem(resolved.registryAccess()).copy();
        this.results.clear();
        this.started = false;
        this.activeMaterialCount = 0;
        this.machineDim = resolved.dimension().location();
        this.machineServer = player.server;
        markCraftStarted();
        return !expected.isEmpty();
    }

    @Override public List<IngredientSpec> getRequiredMaterials() {
        if (recipe == null) return null;
        var handler = ModRecipeHandlers.handlerFor(recipe);
        return handler != null ? handler.getIngredients(recipe)
                : CraftPacketUtils.extractIngredientSpecs(recipe);
    }
    @Override public BatchConcurrencyCapabilities concurrencyCapabilities() {
        return BatchConcurrencyCapabilities.delegateResult();
    }

    @Override
    public int prepareFlatBatch(int remainingOperations) {
        return Math.max(0, Math.min(MAX_MATERIAL_COUNT, remainingOperations));
    }

    @Override
    public void prepareGraphBatch(int executions) {
        // Graph dispatch supplies the concrete batch size through
        // preferredParallelBatchSize and the aggregated material stack.
    }

    @Override
    public int preferredParallelBatchSize(int totalOperations, int workerCount) {
        return ParallelBatchSizing.boundedEvenShare(totalOperations, workerCount, MAX_MATERIAL_COUNT);
    }

    @Override
    public boolean supportsInputBuffer() {
        return level != null && pos != null && recipe != null
                && apprenticeInputBufferEnabled();
    }

    @Override
    public InputBufferContract inputBufferContract() {
        if (!supportsInputBuffer()) return InputBufferContract.none();
        List<IngredientSpec> specs = getRequiredMaterials();
        if (specs == null || specs.size() != 2 || expected.isEmpty()) {
            return InputBufferContract.none();
        }
        ItemStack catalyst = ingredientPrototype(specs.get(0));
        ItemStack material = ingredientPrototype(specs.get(1));
        if (catalyst.isEmpty() || material.isEmpty()) return InputBufferContract.none();
        return new InputBufferContract(
                apprenticeInputBufferLimit(),
                List.of(
                        new InputBufferContract.InputSlot(
                                CATALYST_ENTRY_ID, 0, catalyst, 1, true, 1),
                        new InputBufferContract.InputSlot(
                                MATERIAL_ENTRY_ID, 1, material, 1, false,
                                apprenticeInputBufferLimit())),
                List.of(new OutputContract.Port(
                        "apprenticecodex:primary", null, expected, expected.getCount(),
                        InputBufferPlan.OutputPort.Kind.PRIMARY, OutputContract.Source.VIRTUAL)));
    }

    @Override
    public InputBufferPlan inputBufferPlan(int requestedOperations) {
        return inputBufferContract().plan(requestedOperations);
    }

    @Override
    public boolean tryStartWithInputBuffer(@Nonnull ServerPlayer player,
                                           @Nonnull InputBufferPlan plan,
                                           @Nonnull ExtractionLedger sharedLedger) {
        if (!supportsInputBuffer() || plan == null || !plan.enabled()
                || plan.inputs().size() != 2) return false;
        InputBufferPlan.InputSlot catalyst = plan.inputs().stream()
                .filter(input -> CATALYST_ENTRY_ID.equals(input.entryId())).findFirst().orElse(null);
        InputBufferPlan.InputSlot material = plan.inputs().stream()
                .filter(input -> MATERIAL_ENTRY_ID.equals(input.entryId())).findFirst().orElse(null);
        if (catalyst == null || material == null || catalyst.stack().isEmpty()
                || material.stack().isEmpty() || catalyst.stack().getCount() != 1
                || catalyst.reusable() != true || material.perOperation() != 1
                || material.stack().getCount() != plan.operations()) return false;
        InputBufferPlan expectedPlan = inputBufferPlan(plan.operations());
        if (!expectedPlan.enabled() || expectedPlan.operations() != plan.operations()) return false;

        this.ledger = sharedLedger;
        this.sharedLedger = sharedLedger;
        this.usingSharedLedger = true;
        return start(List.of(catalyst.stack(), material.stack()));
    }

    @Override
    public OutputContract outputContract() {
        if (!supportsInputBuffer() || expected.isEmpty()) return OutputContract.none();
        return new OutputContract(List.of(new OutputContract.Port(
                "apprenticecodex:primary", null, expected, expected.getCount(),
                InputBufferPlan.OutputPort.Kind.PRIMARY, OutputContract.Source.VIRTUAL)));
    }

    @Override
    public List<OutputAccounting.CollectedOutput> collectStructuredResults(
            @Nonnull ServerPlayer player) {
        OutputContract contract = outputContract();
        if (contract.ports().size() != 1) return List.of();
        List<ItemStack> collected = collectAllResults(player);
        if (collected.isEmpty()) return List.of();
        String portId = contract.ports().get(0).portId();
        return collected.stream()
                .filter(stack -> stack != null && !stack.isEmpty())
                .map(stack -> new OutputAccounting.CollectedOutput(
                        portId, OutputContract.Source.VIRTUAL, stack))
                .toList();
    }

    @Override public boolean tryStartSingleCraft(@Nonnull ServerPlayer player) {
        List<IngredientSpec> specs = getRequiredMaterials();
        if (specs == null || specs.size() != 2) return false;
        ExtractionLedger privateLedger = new ExtractionLedger();
        this.ledger = privateLedger;
        this.usingSharedLedger = false;
        if (storageEndpoint() == null) {
            this.network = CraftPacketUtils.resolveNetworkForCraft(player, dimension, pos);
        }
        privateLedger.setStorageEndpoint(storageEndpoint());
        List<ItemStack> materials = new ArrayList<>(2);
        for (IngredientSpec spec : specs) {
            ItemStack stack = CraftPacketUtils.ensureMaterialAvailable(player, dimension, pos,
                    spec.ingredient(), spec.count(), privateLedger);
            if (stack.isEmpty()) { privateLedger.close(); return false; }
            materials.add(stack.copy());
        }
        if (!privateLedger.commit(network, player)) return false;
        if (start(materials)) return true;
        privateLedger.refundCommitted(network, player);
        return false;
    }

    @Override public boolean tryStartWithMaterials(@Nonnull ServerPlayer player,
                                                    @Nonnull List<ItemStack> materials,
                                                    @Nonnull ExtractionLedger sharedLedger) {
        this.ledger = sharedLedger;
        this.sharedLedger = sharedLedger;
        this.usingSharedLedger = true;
        return start(materials);
    }

    private boolean start(List<ItemStack> materials) {
        List<IngredientSpec> specs = getRequiredMaterials();
        BlockEntity be = level.getBlockEntity(pos);
        if (specs == null || specs.size() != 2 || materials.size() != 2 || !isIdle(be)) return false;
        ItemStack catalystInput = materials.get(0);
        ItemStack materialInput = materials.get(1);
        if (catalystInput.isEmpty() || catalystInput.getCount() < specs.get(0).count()
                || !specs.get(0).ingredient().test(catalystInput)) return false;
        if (materialInput.isEmpty() || materialInput.getCount() < specs.get(1).count()
                || materialInput.getCount() > MAX_MATERIAL_COUNT
                || !specs.get(1).ingredient().test(materialInput)) return false;

        ItemStack catalyst = catalystInput.copyWithCount(1);
        if (!invokeBoolean(be, "setCatalyst", new Class<?>[]{ItemStack.class}, catalyst)) return false;
        int materialCount = materialInput.getCount();
        int added = 0;
        for (; added < materialCount; added++) {
            if (!invokeBoolean(be, "addMaterial", new Class<?>[]{ItemStack.class},
                    materialInput.copyWithCount(1))) {
                for (int i = 0; i < added; i++) invoke(be, "popLastMaterial", new Class<?>[0]);
                invoke(be, "popCatalyst", new Class<?>[0]);
                return false;
            }
        }
        if (!invokeBoolean(be, "ignite", new Class<?>[]{long.class}, level.getGameTime())) {
            for (int i = 0; i < added; i++) invoke(be, "popLastMaterial", new Class<?>[0]);
            invoke(be, "popCatalyst", new Class<?>[0]);
            return false;
        }
        activeMaterialCount = materialCount;
        started = true;
        return true;
    }

    private static boolean isIdle(@Nullable BlockEntity be) {
        return be != null && be.getClass().getName().equals(BE_CLASS)
                && !invokeBoolean(be, "hasCatalyst", new Class<?>[0])
                && !invokeBoolean(be, "hasMaterials", new Class<?>[0])
                && !invokeBoolean(be, "isProcessing", new Class<?>[0])
                && !invokeBoolean(be, "isCompleted", new Class<?>[0]);
    }

    private static ItemStack ingredientPrototype(IngredientSpec spec) {
        if (spec == null || spec.isEmpty()) return ItemStack.EMPTY;
        return Arrays.stream(spec.ingredient().getItems())
                .filter(stack -> stack != null && !stack.isEmpty())
                .findFirst().map(ItemStack::copy).orElse(ItemStack.EMPTY);
    }

    private static boolean apprenticeInputBufferEnabled() {
        try {
            return RSIntegrationConfig.ENABLE_APPRENTICE_CODEX_INPUT_BUFFER.get();
        } catch (IllegalStateException | NullPointerException ignored) {
            return false;
        }
    }

    private static int apprenticeInputBufferLimit() {
        try {
            return Math.min(MAX_MATERIAL_COUNT,
                    RSIntegrationConfig.APPRENTICE_CODEX_INPUT_BUFFER_LIMIT.get());
        } catch (IllegalStateException | NullPointerException ignored) {
            return MAX_MATERIAL_COUNT;
        }
    }

    @Override protected boolean isMachineCraftFinished(@Nonnull ServerLevel level, @Nonnull BlockEntity be) {
        return started && invokeBoolean(be, "isCompleted", new Class<?>[0]);
    }

    @Override public ItemStack collectResult(@Nonnull ServerPlayer player) {
        List<ItemStack> all = collectAllResults(player);
        return all.isEmpty() ? ItemStack.EMPTY : all.get(0);
    }

    @Override public List<ItemStack> collectAllResults(@Nonnull ServerPlayer player) {
        BlockEntity be = level.getBlockEntity(pos);
        if (be == null || !be.getClass().getName().equals(BE_CLASS)
                || !invokeBoolean(be, "isCompleted", new Class<?>[0])) return List.of();
        Object raw = invoke(be, "collectCompletedItems", new Class<?>[0]);
        if (!(raw instanceof List<?> stacks)) return List.of();
        results.clear();
        for (Object value : stacks) if (value instanceof ItemStack stack && !stack.isEmpty()) results.add(stack.copy());
        started = false;
        if (ledger != null && !usingSharedLedger && ledger.isCommitted()) ledger.settleAllCommitted();
        return results.stream().map(ItemStack::copy).toList();
    }

    @Override public boolean collectsPhysicalSecondaryOutputs() { return true; }
    @Override public ExpectedProduction getExpectedProduction() {
        if (expected.isEmpty()) return null;
        int count = Math.max(1, activeMaterialCount) * Math.max(1, expected.getCount());
        return new ExpectedProduction(expected, count);
    }
    @Override protected void clearMachineState(BlockEntity be, ServerPlayer player) {
        resetContents(be);
        results.clear(); started = false; activeMaterialCount = 0; resetState();
    }
    @Override public void onBatchFinished(@Nullable ServerPlayer player) {
        if (!markTerminalCleanup()) return;
        results.clear(); started = false; activeMaterialCount = 0;
        expected = ItemStack.EMPTY; resetState();
    }
    @Override public BlockPos getMachinePos() { return pos; }

    private static boolean isMachine(ServerLevel level, BlockPos pos) {
        if (!level.hasChunkAt(pos)) return false;
        ResourceLocation id = ForgeRegistries.BLOCKS.getKey(level.getBlockState(pos).getBlock());
        BlockEntity be = level.getBlockEntity(pos);
        return id != null && "apprenticecodex:essence_smoker".equals(id.toString())
                && be != null && be.getClass().getName().equals(BE_CLASS);
    }

    private static boolean invokeBoolean(Object target, String name, Class<?>[] types, Object... args) {
        Object value = invoke(target, name, types, args);
        return value instanceof Boolean bool && bool;
    }

    private static Object invoke(Object target, String name, Class<?>[] types, Object... args) {
        try {
            Method method = target.getClass().getMethod(name, types);
            return method.invoke(target, args);
        } catch (ReflectiveOperationException | LinkageError e) {
            RSIntegrationMod.LOGGER.warn("[RSI-ApprenticeCodex] Cannot invoke {} on {}",
                    name, target.getClass().getName(), e);
            return null;
        }
    }

    private static void resetContents(Object target) {
        try {
            Method method = target.getClass().getDeclaredMethod("resetContents");
            method.setAccessible(true);
            method.invoke(target);
        } catch (ReflectiveOperationException | LinkageError e) {
            RSIntegrationMod.LOGGER.error("[RSI-ApprenticeCodex] Cannot reset cancelled Essence Smoker at {}",
                    target, e);
        }
    }
}
