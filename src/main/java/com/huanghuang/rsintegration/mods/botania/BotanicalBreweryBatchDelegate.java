package com.huanghuang.rsintegration.mods.botania;

import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.IngredientMatcher;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.TaskOutputReception;
import com.huanghuang.rsintegration.crafting.batch.AbstractBatchDelegate;
import com.huanghuang.rsintegration.crafting.batch.MaterialPlan;
import com.huanghuang.rsintegration.crafting.batch.NativeItemOutput;
import com.huanghuang.rsintegration.crafting.batch.OperationOutputSource;
import com.huanghuang.rsintegration.crafting.batch.OutputContract;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import vazkii.botania.api.recipe.BotanicalBreweryRecipe;
import vazkii.botania.common.block.block_entity.BreweryBlockEntity;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

public final class BotanicalBreweryBatchDelegate extends AbstractBatchDelegate {
    private ServerLevel level;
    private BlockPos pos;
    private BreweryBlockEntity brewery;
    private BotanicalBreweryRecipe recipe;
    private ItemStack expected = ItemStack.EMPTY;
    private TaskOutputReception.Writer production;
    private NativeItemOutput.Receiver receiver;
    private final List<ItemStack> uninstalled = new ArrayList<>();
    private final List<InstalledInput> installed = new ArrayList<>();
    private boolean received;

    @Override
    public PreparationResult prepare(ServerPlayer player, ResourceLocation id, @Nullable ResourceLocation dim, BlockPos at) {
        if (!validateAndInit(player, id, dim, at)) return PreparationResult.retry("brewery is unavailable");
        return brewery.recipe == null && brewery.getItemHandler().isEmpty()
                ? PreparationResult.ready() : PreparationResult.retry("brewery is occupied");
    }

    @Override
    public boolean validateAndInit(ServerPlayer player, ResourceLocation id, @Nullable ResourceLocation dim, BlockPos at) {
        level = CraftPacketUtils.resolveLevel(player.server, dim, player);
        if (level == null || !level.hasChunkAt(at) || !(level.getBlockEntity(at) instanceof BreweryBlockEntity tile)
                || !(tile instanceof NativeItemOutput)) return false;
        if (!(level.getRecipeManager().byKey(id).orElse(null) instanceof BotanicalBreweryRecipe candidate)) return false;
        pos = at.immutable();
        machineDim = level.dimension().location();
        machineServer = player.server;
        brewery = tile;
        recipe = candidate;
        expected = candidate.getOutput(vial());
        if (storageEndpoint() == null) {
            setStorageEndpoint(CraftPacketUtils.resolveEndpointForCraft(player, level.dimension(), pos).orElse(null));
        }
        return hasStorageAccess() && expected != null && !expected.isEmpty();
    }

    private static ItemStack vial() {
        return new ItemStack(BuiltInRegistries.ITEM.get(new ResourceLocation("botania", "vial")));
    }

    @Override
    public MaterialPlan materialPlan() { return MaterialPlan.graphSlots(recipeSlots()); }

    List<IngredientSpec> recipeSlots() {
        if (recipe == null) return null;
        List<IngredientSpec> specs = new ArrayList<>();
        specs.add(new IngredientSpec(Ingredient.of(vial()), 1));
        for (Ingredient ingredient : recipe.getIngredients()) {
            if (!ingredient.isEmpty()) specs.add(new IngredientSpec(ingredient, 1));
        }
        return specs;
    }

    @Override
    public void bindProduction(TaskOutputReception.Writer writer) { production = writer; }

    @Override
    public boolean startSingleOperation(ServerPlayer player) {
        if (storageEndpoint() == null || !canStart()) return false;
        ledger = materialAccess().newLedger();
        ledger.setStorageEndpoint(storageEndpoint());
        List<ItemStack> materials = new ArrayList<>();
        try {
            for (IngredientSpec spec : recipeSlots()) {
                ItemStack stack = CraftPacketUtils.ensureMaterialAvailable(
                        player, level.dimension(), pos, spec.ingredient(), spec.count(), ledger);
                if (stack.isEmpty()) return false;
                materials.add(stack);
            }
            if (!ledger.commitToEndpoint(storageEndpoint(), player)) return false;
        } finally {
            ledger.close();
        }
        try {
            if (startWithMaterials(materials)) return true;
        } catch (RuntimeException e) {
            recoverInputs(player);
            throw e;
        }
        recoverInputs(player);
        return false;
    }

    @Override
    public boolean startSingleOperation(ServerPlayer player, ExtractionLedger shared) { return false; }

    @Override
    public boolean startPreparedOperation(ServerPlayer player, List<ItemStack> materials, ExtractionLedger shared) {
        useSharedLedger(shared);
        return startWithMaterials(materials);
    }

    private boolean isCurrentMachine() {
        return brewery != null && !brewery.isRemoved() && level.hasChunkAt(pos) && level.getBlockEntity(pos) == brewery;
    }

    private boolean canStart() {
        return production != null && isCurrentMachine() && brewery.recipe == null
                && brewery.getItemHandler().isEmpty()
                && brewery instanceof NativeItemOutput hook && hook.rsi$getOutputReceiver() == null;
    }

    private boolean startWithMaterials(List<ItemStack> materials) {
        uninstalled.addAll(materials.stream().map(ItemStack::copy).toList());
        if (!canStart()) return false;
        Container inventory = brewery.getItemHandler();
        List<IngredientSpec> specs = recipeSlots();
        if (materials.size() != specs.size() || materials.size() > inventory.getContainerSize()) return false;
        for (int index = 0; index < materials.size(); index++) {
            ItemStack stack = materials.get(index);
            if (stack.getCount() != 1 || !IngredientMatcher.test(specs.get(index).ingredient(), stack)) return false;
        }
        for (int index = 0; index < materials.size(); index++) {
            ItemStack input = materials.get(index).copy();
            if (!inventory.getItem(index).isEmpty()) return false;
            boolean accepted;
            try {
                accepted = brewery.addItem(null, input, null);
            } finally {
                // addItem 满槽时也能返回 true，必须核实实际存放的物品。
                ItemStack actual = inventory.getItem(index);
                if (!actual.isEmpty()) {
                    installed.add(new InstalledInput(index, actual));
                    uninstalled.set(index, ItemStack.EMPTY);
                } else if (input.isEmpty()) {
                    uninstalled.set(index, ItemStack.EMPTY);
                }
            }
            ItemStack actual = inventory.getItem(index);
            if (!accepted || !input.isEmpty() || !ItemStack.matches(actual, materials.get(index))) return false;
        }
        if (brewery.recipe != recipe) return false;
        ItemStack output = recipe.getOutput(inventory.getItem(0).copy());
        if (output == null || output.isEmpty()) return false;
        expected = output.copy();
        receiver = (active, stack, primary) -> {
            if (received || !primary || active != recipe || !isCurrentMachine()
                    || !ownsInventory(inventory) || !matchesOutput(stack, expected)
                    || !production.tryHold(stack)) return false;
            received = true;
            return true;
        };
        ((NativeItemOutput) brewery).rsi$setOutputReceiver(receiver);
        markCraftStarted();
        return true;
    }

    static boolean matchesOutput(ItemStack actual, ItemStack expected) {
        if (actual.isEmpty() || !ItemStack.isSameItem(actual, expected)) return false;
        // 同一个药瓶物品承载多种酿造结果，只校验 brewKey，其他运行时标签照原样保留。
        return expected.getTag() == null || !expected.getTag().contains("brewKey")
                || actual.getTag() != null && expected.getTag().get("brewKey").equals(actual.getTag().get("brewKey"));
    }

    private boolean ownsInventory(Container inventory) {
        for (InstalledInput input : installed) {
            if (inventory.getItem(input.slot()) != input.stack()) return false;
        }
        for (int index = installed.size(); index < inventory.getContainerSize(); index++) {
            if (!inventory.getItem(index).isEmpty()) return false;
        }
        return true;
    }

    @Override
    protected CraftObservation observeMachineCraft(ServerLevel level, BlockEntity block) {
        if (block != brewery) return failObservation("brewery was replaced");
        if (received) {
            // 原生 tick 先生成产物再清槽，下一 tick 才清除旧配方。
            return brewery.recipe == recipe && brewery.getItemHandler().isEmpty()
                    ? workingObservation() : doneObservation();
        }
        if (brewery.recipe != recipe || !ownsInventory(brewery.getItemHandler())) {
            return failObservation("brewery inputs or recipe changed");
        }
        return workingObservation();
    }

    @Override
    public OperationOutputSource outputSource() {
        return OperationOutputSource.single(player -> ItemStack.EMPTY, outputContract());
    }

    @Override
    public ExpectedProduction getExpectedProduction() {
        return expected == null || expected.isEmpty() ? null : new ExpectedProduction(expected, expected.getCount());
    }

    @Override
    public OutputContract outputContract() {
        ExpectedProduction output = getExpectedProduction();
        if (output == null) return OutputContract.none();
        return new OutputContract(List.of(new OutputContract.Port("botania:brewery", null,
                new ItemStack(output.item().getItem()), output.count(),
                OutputContract.Kind.PRIMARY, OutputContract.Source.WORLD)));
    }

    @Override public AABB getOutputCaptureRegion() { return null; }
    @Override public BlockPos getMachinePos() { return pos; }

    @Override
    public void releasePreparationResources() {
        if (receiver != null && brewery instanceof NativeItemOutput hook) hook.rsi$clearOutputReceiver(receiver);
        receiver = null;
    }

    private void recoverInputs(@Nullable ServerPlayer player) {
        releasePreparationResources();
        List<ItemStack> recovered = new ArrayList<>(uninstalled);
        if (isCurrentMachine()) {
            Container inventory = brewery.getItemHandler();
            for (InstalledInput input : installed) {
                if (inventory.getItem(input.slot()) == input.stack()) {
                    inventory.setItem(input.slot(), ItemStack.EMPTY);
                    if (inventory.getItem(input.slot()).isEmpty()) recovered.add(input.stack());
                }
            }
            brewery.setChanged();
        }
        installed.clear();
        uninstalled.clear();
        settleFailureRecoveredInputs(recovered, player);
    }

    @Override protected void clearMachineState(BlockEntity block, ServerPlayer player) { recoverInputs(player); }
    @Override protected void clearMissingMachineState(@Nullable ServerPlayer player) { recoverInputs(player); }
    @Override protected void clearUnavailableMachineState(@Nullable ServerPlayer player) { recoverInputs(player); }

    @Override
    public void cleanup(ServerPlayer player) {
        releasePreparationResources();
        installed.clear();
        uninstalled.clear();
        resetState();
    }

    private record InstalledInput(int slot, ItemStack stack) {}
}
