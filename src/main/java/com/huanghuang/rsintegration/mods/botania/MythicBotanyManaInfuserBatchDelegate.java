package com.huanghuang.rsintegration.mods.botania;

import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.batch.AbstractBatchDelegate;
import com.huanghuang.rsintegration.crafting.batch.BatchConcurrencyCapabilities;
import com.huanghuang.rsintegration.util.PlayerUtils;
import com.huanghuang.rsintegration.util.Reflect;
import com.refinedmods.refinedstorage.api.network.INetwork;
import com.refinedmods.refinedstorage.api.util.Action;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Executes one MythicBotany Mana Infuser recipe per physical plate. */
public final class MythicBotanyManaInfuserBatchDelegate extends AbstractBatchDelegate {
    private static final String TILE_CLASS = "mythicbotany.infuser.TileManaInfuser";
    private static final ResourceLocation SHIMMERROCK = new ResourceLocation("botania", "shimmerrock");
    private static final List<BlockPos> PLATFORM_OFFSETS = List.of(
            new BlockPos(0, -1, 0),
            new BlockPos(-1, -1, -1), new BlockPos(-1, -1, 1),
            new BlockPos(1, -1, -1), new BlockPos(1, -1, 1),
            new BlockPos(-1, -1, 0), new BlockPos(1, -1, 0),
            new BlockPos(0, -1, -1), new BlockPos(0, -1, 1));

    private ServerLevel level;
    private BlockPos infuserPos;
    private Recipe<?> recipe;
    private List<IngredientSpec> requiredMaterials;
    private ItemStack expected = ItemStack.EMPTY;
    private INetwork rsNetwork;
    private boolean started;
    private long startTick;
    private Set<UUID> entitiesBefore = Set.of();
    private final Set<UUID> inputEntityIds = new HashSet<>();

    @Override
    public boolean validateAndInit(@Nonnull ServerPlayer player, @Nonnull ResourceLocation recipeId,
                                   @Nullable ResourceLocation dim, @Nonnull BlockPos pos) {
        return prepareInternal(player, recipeId, dim, pos).state() == PreparationState.READY;
    }

    @Override
    public PreparationResult prepare(@Nonnull ServerPlayer player, @Nonnull ResourceLocation recipeId,
                                     @Nullable ResourceLocation dim, @Nonnull BlockPos pos) {
        return prepareInternal(player, recipeId, dim, pos);
    }

    private PreparationResult prepareInternal(ServerPlayer player, ResourceLocation recipeId,
                                              @Nullable ResourceLocation dim, BlockPos pos) {
        resetLocalState();
        this.machineDim = dim;
        this.machineServer = player.getServer();

        ServerLevel resolved = dim == null ? player.serverLevel() : player.getServer().getLevel(
                ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, dim));
        if (resolved == null || !resolved.isLoaded(pos)) {
            return PreparationResult.retry("Mana Infuser dimension or chunk is unavailable");
        }
        BlockEntity blockEntity = resolved.getBlockEntity(pos);
        if (blockEntity == null || !isClassOrSubclass(blockEntity.getClass(), TILE_CLASS)) {
            return PreparationResult.fatal("Bound block is not a MythicBotany Mana Infuser");
        }

        Recipe<?> found = resolved.getRecipeManager().byKey(recipeId).orElse(null);
        if (found == null || !isInfuserRecipe(found)) {
            return PreparationResult.fatal("Recipe is not a MythicBotany Mana Infuser recipe: " + recipeId);
        }
        List<IngredientSpec> specs = extractSpecs(found);
        ItemStack output = found.getResultItem(resolved.registryAccess());
        if (specs.isEmpty()) return PreparationResult.fatal("Mana Infuser recipe has no inputs: " + recipeId);
        if (output == null || output.isEmpty()) {
            return PreparationResult.fatal("Mana Infuser recipe has no output: " + recipeId);
        }
        if (!hasValidPlatform(blockEntity, resolved, pos)) {
            return PreparationResult.fatal("Mana Infuser platform is incomplete at " + pos);
        }
        if (machineHasActiveRecipe(blockEntity)
                || !resolved.getEntitiesOfClass(ItemEntity.class, itemRegion(pos)).isEmpty()) {
            return PreparationResult.retry("Mana Infuser is busy or contains world items");
        }

        this.level = resolved;
        this.infuserPos = pos.immutable();
        this.recipe = found;
        this.requiredMaterials = List.copyOf(specs);
        this.expected = output.copy();
        this.rsNetwork = CraftPacketUtils.resolveNetworkForCraft(
                player, resolved.dimension(), infuserPos);
        return PreparationResult.ready();
    }

    private static boolean isInfuserRecipe(Recipe<?> recipe) {
        ResourceLocation serializerId = BuiltInRegistries.RECIPE_SERIALIZER.getKey(recipe.getSerializer());
        return MythicBotanyInfuserRecipeHandler.isInfuserSerializerId(serializerId)
                || MythicBotanyInfuserRecipeHandler.isInfuserRecipeClass(recipe.getClass());
    }

    private static List<IngredientSpec> extractSpecs(Recipe<?> recipe) {
        List<IngredientSpec> specs = new ArrayList<>();
        for (Ingredient ingredient : recipe.getIngredients()) {
            if (!ingredient.isEmpty()) specs.add(new IngredientSpec(ingredient, 1));
        }
        return specs;
    }

    private static boolean isClassOrSubclass(Class<?> type, String expectedName) {
        for (Class<?> current = type;
             current != null && current != Object.class;
             current = current.getSuperclass()) {
            if (expectedName.equals(current.getName())) return true;
        }
        return false;
    }

    private static boolean hasValidPlatform(BlockEntity blockEntity, ServerLevel level, BlockPos pos) {
        java.util.Optional<Boolean> nativeResult = Reflect.invoke(blockEntity, "hasValidPlatform");
        if (nativeResult.isPresent()) return nativeResult.get();

        BlockPos base = pos.below();
        if (!isBlock(level, base, SHIMMERROCK)) return false;
        for (int x : new int[]{-1, 1}) {
            for (int z : new int[]{-1, 1}) {
                if (!isBlock(level, base.offset(x, 0, z), SHIMMERROCK)) return false;
            }
        }
        return level.getBlockState(base.north()).isAir()
                && level.getBlockState(base.south()).isAir()
                && level.getBlockState(base.east()).isAir()
                && level.getBlockState(base.west()).isAir();
    }

    private static boolean isBlock(ServerLevel level, BlockPos pos, ResourceLocation expectedId) {
        return expectedId.equals(BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()));
    }

    private static boolean machineHasActiveRecipe(BlockEntity blockEntity) {
        if (Reflect.getField(blockEntity, "recipe").isPresent()) return true;
        Object output = Reflect.getField(blockEntity, "output").orElse(null);
        return output instanceof ItemStack stack && !stack.isEmpty();
    }

    @Override
    public List<IngredientSpec> getRequiredMaterials() {
        return requiredMaterials;
    }

    @Override
    public boolean tryStartSingleCraft(@Nonnull ServerPlayer player) {
        if (recipe == null || level == null || infuserPos == null || requiredMaterials == null) return false;
        if (rsNetwork == null) {
            rsNetwork = CraftPacketUtils.resolveNetworkForCraft(player, level.dimension(), infuserPos);
        }
        if (rsNetwork == null) return false;

        this.ledger = new ExtractionLedger();
        List<ItemStack> materials = new ArrayList<>();
        for (IngredientSpec spec : requiredMaterials) {
            ItemStack reserved = CraftPacketUtils.ensureMaterialAvailable(
                    player, level.dimension(), infuserPos,
                    spec.ingredient(), spec.count(), ledger);
            if (reserved.isEmpty()) {
                ledger.reset();
                return false;
            }
            materials.add(reserved);
        }
        if (!ledger.commit(rsNetwork, player)) return false;
        if (!startEntities(materials)) {
            ledger.refundCommitted(rsNetwork, player);
            return false;
        }
        ledger.reset();
        return true;
    }

    @Override
    public boolean tryStartSingleCraft(@Nonnull ServerPlayer player,
                                       @Nonnull ExtractionLedger sharedLedger) {
        return false;
    }

    @Override
    public boolean tryStartWithMaterials(@Nonnull ServerPlayer player,
                                         @Nonnull List<ItemStack> materials,
                                         @Nonnull ExtractionLedger sharedLedger) {
        return startEntities(materials);
    }

    private boolean startEntities(List<ItemStack> materials) {
        if (level == null || infuserPos == null || requiredMaterials == null
                || !matchesMaterialLayout(requiredMaterials, materials)) return false;
        BlockEntity blockEntity = level.getBlockEntity(infuserPos);
        if (blockEntity == null || machineHasActiveRecipe(blockEntity)
                || !hasValidPlatform(blockEntity, level, infuserPos)
                || !level.getEntitiesOfClass(ItemEntity.class, itemRegion(infuserPos)).isEmpty()) {
            return false;
        }

        entitiesBefore = BotaniaDelegateSupport.snapshot(level, itemRegion(infuserPos));
        inputEntityIds.clear();
        for (ItemStack material : materials) {
            ItemEntity entity = new ItemEntity(level,
                    infuserPos.getX() + 0.5, infuserPos.getY() + 0.55,
                    infuserPos.getZ() + 0.5, material.copyWithCount(1));
            entity.setDeltaMovement(0, 0, 0);
            BotaniaDelegateSupport.protectOperationInput(entity);
            if (!level.addFreshEntity(entity)) {
                discardOwnedInputs();
                return false;
            }
            inputEntityIds.add(entity.getUUID());
        }
        started = true;
        startTick = level.getGameTime();
        markCraftStarted();
        return true;
    }

    static boolean acceptsMaterialLayout(int ingredientCount, List<ItemStack> materials) {
        if (ingredientCount <= 0 || materials == null || materials.size() != ingredientCount) return false;
        return materials.stream().allMatch(stack -> stack != null
                && !stack.isEmpty() && stack.getCount() == 1);
    }

    static boolean matchesMaterialLayout(List<IngredientSpec> specs, List<ItemStack> materials) {
        if (specs == null || !acceptsMaterialLayout(specs.size(), materials)) return false;
        for (int i = 0; i < specs.size(); i++) {
            IngredientSpec spec = specs.get(i);
            ItemStack material = materials.get(i);
            if (spec.count() != 1 || !spec.ingredient().test(material)) return false;
        }
        return true;
    }

    @Override
    protected boolean isMachineCraftFinished(@Nonnull ServerLevel level,
                                             @Nonnull BlockEntity blockEntity) {
        if (!started) return false;
        return level.getEntitiesOfClass(ItemEntity.class, itemRegion(infuserPos), this::isCraftOutput)
                .stream().mapToInt(entity -> entity.getItem().getCount()).sum() >= expected.getCount();
    }

    @Override
    public @Nonnull ItemStack collectResult(@Nonnull ServerPlayer player) {
        if (level == null || infuserPos == null) return ItemStack.EMPTY;
        int collected = 0;
        for (ItemEntity entity : level.getEntitiesOfClass(
                ItemEntity.class, itemRegion(infuserPos), this::isCraftOutput)) {
            collected += entity.getItem().getCount();
            entity.discard();
        }
        return collected <= 0 ? ItemStack.EMPTY : expected.copyWithCount(collected);
    }

    private boolean isCraftOutput(ItemEntity entity) {
        return entity.isAlive()
                && !inputEntityIds.contains(entity.getUUID())
                && BotaniaDelegateSupport.isNew(entity, entitiesBefore)
                && !entity.getItem().isEmpty()
                && ItemStack.isSameItemSameTags(entity.getItem(), expected)
                && level.getGameTime() >= startTick;
    }

    @Override
    protected void clearMachineState(BlockEntity blockEntity, @Nullable ServerPlayer player) {
        if (level == null) return;
        for (UUID id : List.copyOf(inputEntityIds)) {
            var entity = level.getEntity(id);
            if (!(entity instanceof ItemEntity item) || !item.isAlive()) continue;
            ItemStack refund = item.getItem().copy();
            item.discard();
            if (!usingSharedLedger) refundStandalone(player, refund);
        }
        inputEntityIds.clear();
        resetLocalState();
        resetState();
    }

    @Override
    protected void clearMissingMachineState(@Nullable ServerPlayer player) {
        if (level != null) {
            for (UUID id : List.copyOf(inputEntityIds)) {
                var entity = level.getEntity(id);
                if (!(entity instanceof ItemEntity item) || !item.isAlive()) continue;
                ItemStack refund = item.getItem().copy();
                item.discard();
                if (!usingSharedLedger) refundStandalone(player, refund);
            }
        }
        inputEntityIds.clear();
        resetLocalState();
        resetState();
    }

    private void discardOwnedInputs() {
        if (level == null) return;
        for (UUID id : List.copyOf(inputEntityIds)) {
            var entity = level.getEntity(id);
            if (entity != null && entity.isAlive()) entity.discard();
        }
        inputEntityIds.clear();
    }

    private void refundStandalone(@Nullable ServerPlayer player, ItemStack stack) {
        if (stack.isEmpty()) return;
        this.network = rsNetwork;
        ItemStack leftover = insertIntoStorage(player, stack, false);
        if (leftover.isEmpty()) return;
        if (player != null) {
            PlayerUtils.safeGiveToPlayer(player, leftover, rsNetwork);
        } else if (level != null && infuserPos != null) {
            level.addFreshEntity(new ItemEntity(level,
                    infuserPos.getX() + 0.5, infuserPos.getY() + 1.1,
                    infuserPos.getZ() + 0.5, leftover));
        }
    }

    @Override
    public BatchConcurrencyCapabilities concurrencyCapabilities() {
        return new BatchConcurrencyCapabilities(
                BatchConcurrencyCapabilities.MaterialOwnership.CHAIN_RESERVED,
                BatchConcurrencyCapabilities.OutputOwnership.OWNED_WORLD_CAPTURE,
                BatchConcurrencyCapabilities.CleanupContract.SEPARABLE_OFFLINE,
                BatchConcurrencyCapabilities.SideEffects.LOCAL_WORLD_ITEMS,
                BatchConcurrencyCapabilities.PreparationContract.RETRY_SAFE,
                PLATFORM_OFFSETS);
    }

    @Override
    public @Nullable ItemStack getExpectedOutput() {
        return expected.isEmpty() ? null : expected.copy();
    }

    @Override
    public @Nullable AABB getOutputCaptureRegion() {
        return infuserPos == null ? null : itemRegion(infuserPos);
    }

    static AABB itemRegion(BlockPos pos) {
        return new AABB(pos, pos.offset(1, 1, 1));
    }

    @Override
    public BlockPos getMachinePos() {
        return infuserPos;
    }

    @Override
    public void onBatchFinished(@Nullable ServerPlayer player) {
        if (!markTerminalCleanup()) return;
        inputEntityIds.clear();
        resetLocalState();
        resetState();
    }

    private void resetLocalState() {
        level = null;
        infuserPos = null;
        recipe = null;
        requiredMaterials = null;
        expected = ItemStack.EMPTY;
        rsNetwork = null;
        started = false;
        startTick = 0L;
        entitiesBefore = Set.of();
        inputEntityIds.clear();
    }
}
