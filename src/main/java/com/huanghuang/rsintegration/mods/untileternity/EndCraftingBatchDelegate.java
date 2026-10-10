package com.huanghuang.rsintegration.mods.untileternity;

import com.carrot123.until_eternity.block.ModBlocks;
import com.carrot123.until_eternity.recipe.EndCraftingIngredient;
import com.carrot123.until_eternity.recipe.EndCraftingRecipe;
import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.batch.AbstractBatchDelegate;
import com.huanghuang.rsintegration.crafting.batch.BatchConcurrencyCapabilities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.TransientCraftingContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/** 终末工作台没有持久输入槽；在已绑定方块处校验真实 5×5 配方并结算材料。 */
public final class EndCraftingBatchDelegate extends AbstractBatchDelegate {
    private ServerLevel level;
    private BlockPos pos;
    private EndCraftingRecipe recipe;
    private List<IngredientSpec> specs;
    private CraftedOutputs pending;
    private boolean craftDone;
    private boolean collected;
    private int graphExecutions = 1;

    @Override
    public boolean validateAndInit(ServerPlayer player, ResourceLocation recipeId,
                                   @Nullable ResourceLocation dim, BlockPos pos) {
        ServerLevel resolved = CraftPacketUtils.resolveLevel(player.server, dim, player);
        if (resolved == null || !resolved.hasChunkAt(pos)
                || resolved.getBlockState(pos).getBlock() != ModBlocks.END_CRAFTING_TABLE.get()) {
            return false;
        }
        if (!(resolved.getRecipeManager().byKey(recipeId).orElse(null)
                instanceof EndCraftingRecipe endRecipe)) return false;
        List<IngredientSpec> required = new EndCraftingRecipeHandler().getIngredients(endRecipe);
        if (required == null || required.isEmpty()) return false;
        this.level = resolved;
        this.pos = pos;
        this.machineDim = resolved.dimension().location();
        this.machineServer = player.server;
        this.recipe = endRecipe;
        this.specs = required;
        this.pending = null;
        this.craftDone = false;
        this.collected = false;
        this.graphExecutions = 1;
        return true;
    }

    @Override
    public boolean validateExecutionContext(@Nullable ServerPlayer player) {
        return level != null && pos != null && recipe != null
                && level.hasChunkAt(pos)
                && level.getBlockState(pos).getBlock() == ModBlocks.END_CRAFTING_TABLE.get();
    }

    @Override public List<IngredientSpec> getRequiredMaterials() { return specs; }
    @Override public BatchConcurrencyCapabilities concurrencyCapabilities() {
        return BatchConcurrencyCapabilities.delegateResult();
    }
    @Override public void prepareGraphBatch(int executions) {
        graphExecutions = Math.max(1, executions);
    }

    @Override
    public boolean tryStartSingleCraft(ServerPlayer player) {
        if (!validateExecutionContext(player)) return false;
        ExtractionLedger ownLedger = new ExtractionLedger();
        if (storageEndpoint() == null) {
            network = CraftPacketUtils.resolveNetworkForCraft(player, level.dimension(), pos);
        }
        if (network == null && !hasStorageAccess()) return false;
        ownLedger.setStorageEndpoint(storageEndpoint());
        List<ItemStack> materials = new ArrayList<>(specs.size());
        for (IngredientSpec spec : specs) {
            if (spec.isEmpty()) {
                materials.add(ItemStack.EMPTY);
                continue;
            }
            ItemStack reserved = CraftPacketUtils.ensureMaterialAvailable(
                    player, level.dimension(), pos, spec.ingredient(), spec.count(), ownLedger);
            if (reserved.isEmpty()) {
                ownLedger.rollback(player);
                return false;
            }
            materials.add(reserved);
        }
        CraftedOutputs outputs = craft(recipe, materials, player.serverLevel().registryAccess(), 1);
        if (outputs == null || !validateExecutionContext(player)
                || !ownLedger.commit(network, player)) {
            ownLedger.rollback(player);
            return false;
        }
        ledger = ownLedger;
        publish(outputs);
        return true;
    }

    @Override
    public boolean tryStartWithMaterials(ServerPlayer player, List<ItemStack> materials,
                                         ExtractionLedger sharedLedger) {
        if (!validateExecutionContext(player)) return false;
        CraftedOutputs outputs = craft(recipe, materials,
                player.serverLevel().registryAccess(), graphExecutions);
        if (outputs == null || !validateExecutionContext(player)) return false;
        publish(outputs);
        return true;
    }

    private void publish(CraftedOutputs outputs) {
        pending = outputs;
        collected = false;
        craftDone = true;
        forceMachineChunk(level, pos, true);
    }

    @Nullable
    static CraftedOutputs craft(EndCraftingRecipe recipe, List<ItemStack> materials,
                                RegistryAccess access, int executions) {
        List<EndCraftingIngredient> inputs = recipe.endIngredients();
        if (executions <= 0) return null;
        if (materials.size() != inputs.size()) {
            // 链式预留会省略空槽；按配方顺序补回空槽，再进行原配方的 5×5 校验。
            List<ItemStack> expanded = new ArrayList<>(inputs.size());
            int materialIndex = 0;
            for (EndCraftingIngredient input : inputs) {
                if (input.isEmpty()) {
                    expanded.add(ItemStack.EMPTY);
                } else {
                    if (materialIndex >= materials.size()) return null;
                    expanded.add(materials.get(materialIndex++));
                }
            }
            if (materialIndex != materials.size()) return null;
            materials = expanded;
        }
        AbstractContainerMenu menu = new AbstractContainerMenu(null, -1) {
            @Override public ItemStack quickMoveStack(Player player, int slot) { return ItemStack.EMPTY; }
            @Override public boolean stillValid(Player player) { return false; }
        };
        TransientCraftingContainer grid = new TransientCraftingContainer(menu, 5, 5);
        for (int i = 0; i < inputs.size(); i++) {
            EndCraftingIngredient input = inputs.get(i);
            ItemStack material = materials.get(i);
            if (input.isEmpty()) {
                if (material != null && !material.isEmpty()) return null;
                continue;
            }
            if (material == null || material.isEmpty() || material.getCount() != executions) return null;
            ItemStack one = material.copyWithCount(1);
            if (!input.test(one)) return null;
            grid.setItem(recipe.displayGridIndex(i), one);
        }
        if (recipe.findMatch(grid) == null) return null;
        ItemStack result = recipe.assemble(grid, access);
        if (result.isEmpty()) return null;
        try {
            result.setCount(Math.multiplyExact(result.getCount(), executions));
            NonNullList<ItemStack> remainders = recipe.getRemainingItems(grid);
            List<ItemStack> secondary = new ArrayList<>();
            for (ItemStack remainder : remainders) {
                if (remainder.isEmpty()) continue;
                ItemStack repeated = remainder.copy();
                repeated.setCount(Math.multiplyExact(repeated.getCount(), executions));
                secondary.add(repeated);
            }
            return new CraftedOutputs(result.copy(), List.copyOf(secondary));
        } catch (ArithmeticException overflow) {
            return null;
        }
    }

    @Override protected boolean isMachineCraftFinished(ServerLevel level, BlockEntity be) {
        return craftDone && level.getBlockState(pos).getBlock() == ModBlocks.END_CRAFTING_TABLE.get();
    }

    @Override public ItemStack collectResult(ServerPlayer player) {
        if (!craftDone || pending == null) return ItemStack.EMPTY;
        ItemStack result = pending.result().copy();
        pending = null;
        craftDone = false;
        collected = true;
        return result;
    }

    @Override public List<ItemStack> collectAllResults(ServerPlayer player) {
        if (!craftDone || pending == null) return List.of();
        List<ItemStack> results = new ArrayList<>(pending.secondary().size() + 1);
        results.add(pending.result().copy());
        for (ItemStack stack : pending.secondary()) results.add(stack.copy());
        pending = null;
        craftDone = false;
        collected = true;
        return List.copyOf(results);
    }

    @Override public boolean collectsPhysicalSecondaryOutputs() { return true; }
    @Override public BlockPos getMachinePos() { return pos; }

    @Override protected void clearMachineState(BlockEntity be, ServerPlayer player) {
        clearPending(player);
    }

    @Override protected void clearMissingMachineState(ServerPlayer player) {
        clearPending(player);
    }

    private void clearPending(@Nullable ServerPlayer player) {
        forceMachineChunk(level, pos, false);
        if (!collected && !usingSharedLedger && ledger != null && ledger.isCommitted()) {
            ledger.rollback(player);
        }
        pending = null;
        craftDone = false;
    }

    @Override public void onBatchFinished(@NotNull ServerPlayer player) {
        forceMachineChunk(level, pos, false);
        pending = null;
        craftDone = false;
        ledger = null;
    }

    record CraftedOutputs(ItemStack result, List<ItemStack> secondary) {}
}
