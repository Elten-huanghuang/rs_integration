package com.huanghuang.rsintegration.mods.goety;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.batch.AbstractBatchDelegate;
import com.huanghuang.rsintegration.crafting.batch.BatchConcurrencyCapabilities;
import com.huanghuang.rsintegration.recipe.ModRecipeHandlers;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;
import java.util.List;

public final class CursedInfuserBatchDelegate extends AbstractBatchDelegate {
    private static final String BE = "com.Polarice3.Goety.common.blocks.entities.CursedInfuserBlockEntity";
    private static final String RECIPE = "com.Polarice3.Goety.common.crafting.CursedInfuserRecipes";
    private ServerLevel level;
    private BlockPos pos;
    private Recipe<?> recipe;
    private ItemStack expected = ItemStack.EMPTY;

    @Override
    public boolean validateAndInit(ServerPlayer player, ResourceLocation recipeId,
                                   @Nullable ResourceLocation dim, BlockPos pos) {
        ServerLevel resolved = CraftPacketUtils.resolveLevel(player.server, dim, player);
        if (resolved == null) return false;
        BlockEntity be = resolved.getBlockEntity(pos);
        Recipe<?> found = resolved.getRecipeManager().byKey(recipeId).orElse(null);
        if (be == null || !BE.equals(be.getClass().getName()) || found == null
                || !RECIPE.equals(found.getClass().getName()) || isGrim(found)) return false;
        if (!hasSpawner(resolved, pos)) {
            player.sendSystemMessage(Component.translatable("rsi.goety.cursed_infuser.spawner_required"));
            return false;
        }
        this.level = resolved;
        this.pos = pos.immutable();
        this.recipe = found;
        this.expected = found.getResultItem(resolved.registryAccess()).copy();
        this.machineDim = resolved.dimension().location();
        this.machineServer = player.server;
        return !expected.isEmpty();
    }

    private static boolean isGrim(Recipe<?> recipe) {
        try { return (boolean) recipe.getClass().getMethod("isGrim").invoke(recipe); }
        catch (ReflectiveOperationException e) { return true; }
    }

    private static boolean hasSpawner(ServerLevel level, BlockPos pos) {
        return level.getBlockState(pos.below()).is(Blocks.SPAWNER);
    }

    @Nullable
    @Override
    public List<IngredientSpec> getRequiredMaterials() {
        var handler = ModRecipeHandlers.handlerFor(recipe);
        return handler == null ? null : handler.getIngredients(recipe);
    }

    @Override public boolean tryStartSingleCraft(ServerPlayer player) { return false; }

    @Override
    public boolean tryStartWithMaterials(ServerPlayer player, List<ItemStack> materials,
                                         ExtractionLedger sharedLedger) {
        if (level == null || pos == null || materials.isEmpty() || !hasSpawner(level, pos)) return false;
        BlockEntity be = level.getBlockEntity(pos);
        if (be == null || !BE.equals(be.getClass().getName())) return false;
        try {
            Container container = (Container) be;
            if (!container.getItem(0).isEmpty()) return false;
            ItemStack input = materials.get(0).copyWithCount(1);
            if (recipe.getIngredients().isEmpty() || !recipe.getIngredients().get(0).test(input)) return false;
            container.setItem(0, input);
            be.setChanged();
            level.sendBlockUpdated(pos, level.getBlockState(pos), level.getBlockState(pos), 3);
            usingSharedLedger = true;
            markCraftStarted();
            return true;
        } catch (RuntimeException e) {
            RSIntegrationMod.LOGGER.error("[RSI-CursedInfuser] Failed to place input", e);
            return false;
        }
    }

    @Override
    protected boolean isMachineCraftFinished(ServerLevel level, BlockEntity be) {
        return hasSpawner(level, pos) && ((Container) be).getItem(0).isEmpty();
    }

    @Override
    protected CraftObservation observeMachineCraft(ServerLevel level, BlockEntity be) {
        if (!hasSpawner(level, pos)) {
            return failObservation("spawner below cursed infuser is missing");
        }
        return super.observeMachineCraft(level, be);
    }

    @Override public ItemStack collectResult(ServerPlayer player) { return ItemStack.EMPTY; }

    @Override
    protected void clearMachineState(BlockEntity be, @Nullable ServerPlayer player) {
        ((Container) be).removeItemNoUpdate(0);
        be.setChanged();
    }

    @Override
    public void onBatchFinished(ServerPlayer player) {
        level = null;
        pos = null;
        recipe = null;
        expected = ItemStack.EMPTY;
        usingSharedLedger = false;
    }

    @Override public BlockPos getMachinePos() { return pos; }
    @Override public ItemStack getExpectedOutput() { return expected.isEmpty() ? null : expected.copy(); }
    @Override public AABB getOutputCaptureRegion() { return pos == null ? null : new AABB(pos).inflate(1.25); }

    @Override
    public BatchConcurrencyCapabilities concurrencyCapabilities() {
        return new BatchConcurrencyCapabilities(
                BatchConcurrencyCapabilities.MaterialOwnership.CHAIN_RESERVED,
                BatchConcurrencyCapabilities.OutputOwnership.OWNED_WORLD_CAPTURE,
                BatchConcurrencyCapabilities.CleanupContract.SEPARABLE_OFFLINE,
                BatchConcurrencyCapabilities.SideEffects.LOCAL_WORLD_ITEMS,
                BatchConcurrencyCapabilities.PreparationContract.RETRY_SAFE,
                List.of(BlockPos.ZERO.below()));
    }
}
