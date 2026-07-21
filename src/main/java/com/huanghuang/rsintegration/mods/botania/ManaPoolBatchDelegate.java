package com.huanghuang.rsintegration.mods.botania;

import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.batch.AbstractBatchDelegate;
import com.refinedmods.refinedstorage.api.network.INetwork;
import com.refinedmods.refinedstorage.api.util.Action;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import vazkii.botania.api.recipe.ManaInfusionRecipe;
import vazkii.botania.common.block.block_entity.mana.ManaPoolBlockEntity;

import javax.annotation.Nonnull;
import java.util.List;

/** Real single-input Mana Pool operation. Catalyst matching is performed by Botania. */
public final class ManaPoolBatchDelegate extends AbstractBatchDelegate {
    private ServerLevel level;
    private BlockPos pos;
    private BlockPos poolPos;
    private ManaInfusionRecipe recipe;
    private INetwork rsNetwork;
    private ItemStack expected = ItemStack.EMPTY;
    private boolean started;
    private long startTick;
    private java.util.UUID inputEntityId;
    private java.util.Set<java.util.UUID> entitiesBefore = java.util.Set.of();

    @Override public boolean validateAndInit(@Nonnull ServerPlayer player, @Nonnull ResourceLocation recipeId,
                                              ResourceLocation dim, @Nonnull BlockPos pos) {
        this.pos = pos; this.machineDim = dim; this.machineServer = player.getServer();
        ServerLevel resolved = dim == null ? player.serverLevel() : player.getServer().getLevel(
                net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, dim));
        if (resolved == null) return false;
        this.poolPos = resolved.getBlockEntity(pos) instanceof ManaPoolBlockEntity ? pos : pos.above();
        if (!(resolved.getBlockEntity(poolPos) instanceof ManaPoolBlockEntity)) return false;
        this.level = resolved;
        var found = resolved.getRecipeManager().byKey(recipeId).orElse(null);
        if (!(found instanceof ManaInfusionRecipe r)) return false;
        this.recipe = r; this.expected = r.getResultItem(resolved.registryAccess()).copy();
        this.rsNetwork = CraftPacketUtils.resolveNetworkForCraft(player, resolved.dimension(), poolPos);
        if (rsNetwork == null || expected.isEmpty() || r.getIngredients().isEmpty()) return false;
        // Plain recipes belong only to a directly-bound pool. Catalyst recipes belong
        // only to a binding on the declared catalyst below that pool.
        var catalyst = r.getRecipeCatalyst();
        boolean directlyBoundPool = pos.equals(poolPos);
        if (catalyst == null) return directlyBoundPool;
        return !directlyBoundPool && catalyst.test(resolved.getBlockState(pos));
    }

    @Override
    public boolean acceptsMachineWithoutBlockEntity(@Nonnull ServerLevel level, @Nonnull BlockPos pos) {
        // Catalyst bindings point at the catalyst block, which intentionally has no
        // block entity. The actual leased machine is the Mana Pool directly above it.
        return level.getBlockEntity(pos.above()) instanceof ManaPoolBlockEntity;
    }
    @Override public List<IngredientSpec> getRequiredMaterials() {
        return recipe == null ? null : List.of(new IngredientSpec(recipe.getIngredients().get(0), 1));
    }

    @Override public boolean tryStartSingleCraft(@Nonnull ServerPlayer player) {
        if (recipe == null || rsNetwork == null || level == null) return false;
        List<ItemStack> extracted = BotaniaDelegateSupport.extractAtomically(rsNetwork, getRequiredMaterials());
        return !extracted.isEmpty() && startEntity(extracted.get(0));
    }

    @Override public boolean tryStartSingleCraft(@Nonnull ServerPlayer player, @Nonnull ExtractionLedger sharedLedger) {
        return false;
    }

    @Override public boolean tryStartWithMaterials(@Nonnull ServerPlayer player, @Nonnull List<ItemStack> materials,
                                                    @Nonnull ExtractionLedger sharedLedger) {
        return materials.size() == 1 && !materials.get(0).isEmpty() && startEntity(materials.get(0).copy());
    }

    private boolean startEntity(ItemStack input) {
        if (!(level.getBlockEntity(poolPos) instanceof ManaPoolBlockEntity pool)) return false;
        ItemEntity entity = new ItemEntity(level, poolPos.getX()+0.5, poolPos.getY()+1.15, poolPos.getZ()+0.5, input);
        entity.setDeltaMovement(0, 0, 0);
        // This operation owns the entity; nearby players and collectors must not steal it.
        BotaniaDelegateSupport.protectOperationInput(entity);
        entitiesBefore = BotaniaDelegateSupport.snapshot(level, new AABB(poolPos).inflate(1.5));
        if (!level.addFreshEntity(entity)) return false;
        inputEntityId = entity.getUUID();
        started = true; startTick = level.getGameTime(); markCraftStarted(); return true;
    }

    @Override protected boolean isMachineCraftFinished(@Nonnull ServerLevel level, @Nonnull BlockEntity be) {
        if (!started) return false;
        AABB box = new AABB(poolPos).inflate(1.5);
        return level.getEntitiesOfClass(ItemEntity.class, box, this::isCraftOutput).stream().findAny().isPresent();
    }

    @Override public ItemStack collectResult(@Nonnull ServerPlayer player) {
        if (level == null) return ItemStack.EMPTY;
        AABB box = new AABB(poolPos).inflate(1.5);
        for (ItemEntity e : level.getEntitiesOfClass(ItemEntity.class, box, this::isCraftOutput)) {
            ItemStack result = e.getItem().copy(); e.discard(); return result;
        }
        return ItemStack.EMPTY;
    }

    private boolean isCraftOutput(ItemEntity entity) {
        return entity.isAlive()
                && !entity.getUUID().equals(inputEntityId)
                && BotaniaDelegateSupport.isNew(entity, entitiesBefore)
                && !entity.getItem().isEmpty()
                && ItemStack.isSameItemSameTags(entity.getItem(), expected)
                && entity.getItem().getCount() >= expected.getCount()
                && level.getGameTime() >= startTick;
    }

    @Override protected void clearMachineState(BlockEntity be, ServerPlayer player) {
        if (level == null || inputEntityId == null) return;
        var entity = level.getEntity(inputEntityId);
        if (entity instanceof ItemEntity item && item.isAlive()) {
            ItemStack stack = item.getItem().copy();
            item.discard();
            if (!usingSharedLedger && rsNetwork != null && !stack.isEmpty()) {
                rsNetwork.insertItem(stack, stack.getCount(), Action.PERFORM);
            }
        }
    }
    @Override
    public com.huanghuang.rsintegration.crafting.batch.BatchConcurrencyCapabilities concurrencyCapabilities() {
        return new com.huanghuang.rsintegration.crafting.batch.BatchConcurrencyCapabilities(
                com.huanghuang.rsintegration.crafting.batch.BatchConcurrencyCapabilities.MaterialOwnership.CHAIN_RESERVED,
                com.huanghuang.rsintegration.crafting.batch.BatchConcurrencyCapabilities.OutputOwnership.OWNED_WORLD_CAPTURE,
                com.huanghuang.rsintegration.crafting.batch.BatchConcurrencyCapabilities.CleanupContract.SEPARABLE_OFFLINE,
                com.huanghuang.rsintegration.crafting.batch.BatchConcurrencyCapabilities.SideEffects.LOCAL_WORLD_ITEMS,
                com.huanghuang.rsintegration.crafting.batch.BatchConcurrencyCapabilities.PreparationContract.RETRY_SAFE,
                java.util.List.of());
    }
    @Override public ItemStack getExpectedOutput() { return expected.isEmpty() ? null : expected; }
    @Override public AABB getOutputCaptureRegion() { return poolPos == null ? null : new AABB(poolPos).inflate(1.5); }
    @Override public BlockPos getMachinePos() { return pos; }
    @Override public void onBatchFinished(@Nonnull ServerPlayer player) { resetState(); }
}