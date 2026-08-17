package com.huanghuang.rsintegration.mods.arsnouveau;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.batch.AbstractBatchDelegate;
import com.huanghuang.rsintegration.crafting.batch.BatchConcurrencyCapabilities;
import com.huanghuang.rsintegration.util.Reflect;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Executes one Ars Nouveau glyph recipe on one physical Scribes' Table. */
public final class ArsScribesTableBatchDelegate extends AbstractBatchDelegate {
    private static final String GLYPH_RECIPE_CLASS =
            "com.hollingsworth.arsnouveau.common.crafting.recipes.GlyphRecipe";
    private static final String SCRIBES_TILE_CLASS =
            "com.hollingsworth.arsnouveau.common.block.tile.ScribesTile";
    private static final long CRAFT_TIMEOUT_TICKS = 400L;

    private ServerLevel level;
    private BlockPos machinePos;
    private Recipe<?> recipe;
    private List<IngredientSpec> requiredMaterials;
    private ItemStack expectedOutput = ItemStack.EMPTY;
    private int experienceCost;
    private int paidExperience;
    private boolean started;
    private long startTick;
    private Set<UUID> entitiesBefore = Set.of();
    private List<BlockPos> supportOffsets = List.of();

    @Override
    public boolean validateAndInit(@Nonnull ServerPlayer player,
                                   @Nonnull ResourceLocation recipeId,
                                   @Nullable ResourceLocation dim,
                                   @Nonnull BlockPos pos) {
        return prepareInternal(player, recipeId, dim, pos).state() == PreparationState.READY;
    }

    @Override
    public PreparationResult prepare(@Nonnull ServerPlayer player,
                                     @Nonnull ResourceLocation recipeId,
                                     @Nullable ResourceLocation dim,
                                     @Nonnull BlockPos pos) {
        return prepareInternal(player, recipeId, dim, pos);
    }

    private PreparationResult prepareInternal(ServerPlayer player,
                                              ResourceLocation recipeId,
                                              @Nullable ResourceLocation dim,
                                              BlockPos boundPos) {
        resetLocalState();
        this.machineDim = dim;
        this.machineServer = player.getServer();

        ServerLevel resolved = CraftPacketUtils.resolveLevel(player.server, dim, player);
        if (resolved == null || !resolved.isLoaded(boundPos)) {
            return PreparationResult.retry("Scribes' Table dimension or chunk is unavailable");
        }

        BlockEntity logicTile = resolveLogicTile(resolved, boundPos);
        if (logicTile == null) {
            return PreparationResult.fatal("Bound block is not a complete Scribes' Table");
        }

        Recipe<?> found = resolved.getRecipeManager().byKey(recipeId).orElse(null);
        if (found == null || !isClassOrSubclass(found.getClass(), GLYPH_RECIPE_CLASS)
                || !ArsRecipeClassifier.isGlyph(ArsTileAccess.recipeTypeId(found))) {
            return PreparationResult.fatal("Recipe is not an Ars Nouveau glyph recipe: " + recipeId);
        }

        List<Ingredient> inputs = Reflect.<List<Ingredient>>getField(found, "inputs")
                .orElse(List.of());
        List<IngredientSpec> specs = ArsGlyphMaterials.build(inputs);
        ItemStack output = Reflect.<ItemStack>getField(found, "output")
                .map(ItemStack::copy)
                .orElse(ItemStack.EMPTY);
        if (specs.isEmpty()) {
            return PreparationResult.fatal("Glyph recipe has no item inputs: " + recipeId);
        }
        if (output.isEmpty()) {
            return PreparationResult.fatal("Glyph recipe has no item output: " + recipeId);
        }
        if (!isIdle(logicTile)) {
            return PreparationResult.retry("Scribes' Table is busy or contains an item");
        }

        int cost = Math.max(0, Reflect.getIntField(found, "exp").orElse(0));
        int available = totalExperience(player);
        if (!player.isCreative() && available < cost) {
            return PreparationResult.fatal(
                    "Insufficient player experience for glyph: have=" + available + ", need=" + cost,
                    Component.translatable("rsi.ars_nouveau.error.insufficient_experience",
                            available, cost));
        }

        this.level = resolved;
        this.machinePos = logicTile.getBlockPos().immutable();
        this.recipe = found;
        this.requiredMaterials = List.copyOf(specs);
        this.expectedOutput = output;
        this.experienceCost = cost;
        this.supportOffsets = findTablePartOffsets(resolved, logicTile);
        return PreparationResult.ready();
    }

    @Override
    public boolean validateExecutionContext(@Nullable ServerPlayer player) {
        return player != null && (player.isCreative() || totalExperience(player) >= experienceCost);
    }

    @Nullable
    @Override
    public List<IngredientSpec> getRequiredMaterials() {
        return requiredMaterials;
    }

    @Override
    public boolean tryStartSingleCraft(@Nonnull ServerPlayer player) {
        return false;
    }

    @Override
    public boolean tryStartWithMaterials(@Nonnull ServerPlayer player,
                                         @Nonnull List<ItemStack> materials,
                                         @Nonnull ExtractionLedger sharedLedger) {
        if (level == null || machinePos == null || recipe == null
                || requiredMaterials == null || !matchesMaterials(materials)) {
            return false;
        }

        BlockEntity tile = resolveLogicTile(level, machinePos);
        if (tile == null || !machinePos.equals(tile.getBlockPos()) || !isIdle(tile)
                || !validateExecutionContext(player)) {
            return false;
        }

        entitiesBefore = snapshotEntities(level, outputRegion(machinePos));
        Class<?> glyphRecipeClass = Reflect.forName(GLYPH_RECIPE_CLASS).orElse(null);
        if (glyphRecipeClass == null) return false;
        Reflect.invokeExact(tile, "setRecipe",
                new Class<?>[]{glyphRecipeClass, Player.class}, recipe, player);
        if (activeRecipe(tile) != recipe) {
            return false;
        }
        paidExperience = player.isCreative() ? 0 : experienceCost;

        for (int i = 0; i < materials.size(); i++) {
            ItemStack offered = materials.get(i);
            boolean consumed = Reflect.<Boolean>invokeExact(tile, "consumeStack",
                    new Class<?>[]{ItemStack.class}, offered.copyWithCount(1)).orElse(false);
            if (!consumed) {
                rollbackRejectedStart(tile, player);
                return false;
            }
        }

        List<Ingredient> remaining = Reflect.<List<Ingredient>>invoke(tile, "getRemainingRequired")
                .orElse(List.of());
        if (!remaining.isEmpty()) {
            rollbackRejectedStart(tile, player);
            return false;
        }

        started = true;
        startTick = level.getGameTime();
        markCraftStarted();
        RSIntegrationMod.LOGGER.debug(
                "[RSI-ArsScribes] Started glyph recipe {} at {}, exp={}",
                recipe.getId(), machinePos, paidExperience);
        return true;
    }

    private boolean matchesMaterials(List<ItemStack> materials) {
        if (materials == null || materials.size() != requiredMaterials.size()) return false;
        for (int i = 0; i < materials.size(); i++) {
            ItemStack stack = materials.get(i);
            IngredientSpec spec = requiredMaterials.get(i);
            if (stack == null || stack.isEmpty() || stack.getCount() < 1
                    || spec.count() != 1 || !spec.ingredient().test(stack)) {
                return false;
            }
        }
        return true;
    }

    @Nonnull
    @Override
    protected CraftObservation observeMachineCraft(@Nonnull ServerLevel currentLevel,
                                                    @Nonnull BlockEntity blockEntity) {
        if (!started) return failObservation("Scribes' Table craft was not started");
        BlockEntity tile = resolveLogicTile(currentLevel, machinePos);
        if (tile == null || !machinePos.equals(tile.getBlockPos())) {
            return failObservation("Scribes' Table structure is incomplete");
        }
        if (hasPhysicalOutput(currentLevel)) return doneObservation();

        long elapsed = currentLevel.getGameTime() - startTick;
        if (elapsed > CRAFT_TIMEOUT_TICKS) {
            return failObservation("Scribes' Table craft timeout");
        }
        return workingObservation();
    }

    @Override
    protected boolean isMachineCraftFinished(@Nonnull ServerLevel currentLevel,
                                             @Nonnull BlockEntity blockEntity) {
        return started && hasPhysicalOutput(currentLevel);
    }

    private boolean hasPhysicalOutput(ServerLevel currentLevel) {
        return !currentLevel.getEntitiesOfClass(
                ItemEntity.class, outputRegion(machinePos), this::isOwnedOutput).isEmpty();
    }

    private boolean isOwnedOutput(ItemEntity entity) {
        return entity.isAlive()
                && !entitiesBefore.contains(entity.getUUID())
                && !entity.getItem().isEmpty()
                && ItemStack.isSameItemSameTags(entity.getItem(), expectedOutput)
                && level != null && level.getGameTime() >= startTick;
    }

    @Nonnull
    @Override
    public ItemStack collectResult(@Nonnull ServerPlayer player) {
        if (level == null || machinePos == null) return ItemStack.EMPTY;
        for (ItemEntity entity : level.getEntitiesOfClass(
                ItemEntity.class, outputRegion(machinePos), this::isOwnedOutput)) {
            ItemStack result = entity.getItem().copy();
            entity.discard();
            return result;
        }
        return ItemStack.EMPTY;
    }

    @Override
    protected void clearMachineState(BlockEntity blockEntity, @Nullable ServerPlayer player) {
        BlockEntity tile = level == null ? null : resolveLogicTile(level, machinePos);
        if (tile != null) {
            boolean nativeActive = hasNativeState(tile);
            if (nativeActive) {
                clearNativeStateWithoutDrops(tile);
                refundPaidExperience(player);
            }
        }
        resetLocalState();
        resetState();
    }

    @Override
    protected void clearMissingMachineState(@Nullable ServerPlayer player) {
        // Native block teardown calls refundConsumed(). Avoid issuing a second
        // experience refund when the table was broken while the chain was active.
        resetLocalState();
        resetState();
    }

    private void rollbackRejectedStart(BlockEntity tile, ServerPlayer player) {
        clearNativeStateWithoutDrops(tile);
        refundPaidExperience(player);
        started = false;
        startTick = 0L;
        entitiesBefore = Set.of();
    }

    private void clearNativeStateWithoutDrops(BlockEntity tile) {
        List<?> consumed = Reflect.<List<?>>getField(tile, "consumedStacks").orElse(null);
        if (consumed != null) consumed.clear();
        Reflect.setField(tile, "recipe", null);
        Reflect.setField(tile, "recipeID", null);
        Reflect.setField(tile, "crafting", false);
        Reflect.setField(tile, "craftingTicks", 0);
        tile.setChanged();
        if (level != null) {
            level.sendBlockUpdated(tile.getBlockPos(), tile.getBlockState(), tile.getBlockState(), 3);
        }
    }

    private void refundPaidExperience(@Nullable ServerPlayer player) {
        int refund = paidExperience;
        paidExperience = 0;
        if (refund <= 0) return;
        if (player != null) {
            player.giveExperiencePoints(refund);
        } else if (level != null && machinePos != null) {
            ExperienceOrb.award(level, Vec3.atCenterOf(machinePos).add(0.0, 0.6, 0.0), refund);
        }
    }

    @Override
    public void onBatchFinished(@Nullable ServerPlayer player) {
        if (!markTerminalCleanup()) return;
        paidExperience = 0;
        resetLocalState();
        resetState();
    }

    @Nullable
    @Override
    public ItemStack getExpectedOutput() {
        return expectedOutput.isEmpty() ? null : expectedOutput.copy();
    }

    @Nullable
    @Override
    public AABB getOutputCaptureRegion() {
        return machinePos == null ? null : outputRegion(machinePos);
    }

    @Nonnull
    @Override
    public BlockPos getMachinePos() {
        return machinePos;
    }

    @Nullable
    @Override
    public BatchConcurrencyCapabilities concurrencyCapabilities() {
        return new BatchConcurrencyCapabilities(
                BatchConcurrencyCapabilities.MaterialOwnership.CHAIN_RESERVED,
                BatchConcurrencyCapabilities.OutputOwnership.OWNED_WORLD_CAPTURE,
                BatchConcurrencyCapabilities.CleanupContract.SEPARABLE_OFFLINE,
                BatchConcurrencyCapabilities.SideEffects.LOCAL_WORLD_ITEMS,
                BatchConcurrencyCapabilities.PreparationContract.RETRY_SAFE,
                supportOffsets
        );
    }

    private static int totalExperience(ServerPlayer player) {
        int level = player.experienceLevel;
        int base;
        if (level <= 16) {
            base = level * level + 6 * level;
        } else if (level <= 31) {
            base = (int) (2.5D * level * level - 40.5D * level + 360.0D);
        } else {
            base = (int) (4.5D * level * level - 162.5D * level + 2220.0D);
        }
        return base + (int) (player.experienceProgress * player.getXpNeededForNextLevel());
    }

    @Nullable
    private static BlockEntity resolveLogicTile(ServerLevel level, BlockPos pos) {
        if (level == null || pos == null || !level.isLoaded(pos)) return null;
        BlockEntity tile = level.getBlockEntity(pos);
        if (tile == null || !isClassOrSubclass(tile.getClass(), SCRIBES_TILE_CLASS)) return null;
        Object rawLogic = Reflect.invoke(tile, "getLogicTile").orElse(null);
        if (!(rawLogic instanceof BlockEntity logic) || logic.isRemoved()) return null;
        boolean master = Reflect.<Boolean>invoke(logic, "isMasterTile").orElse(false);
        return master ? logic : null;
    }

    private static List<BlockPos> findTablePartOffsets(ServerLevel level, BlockEntity logicTile) {
        List<BlockPos> offsets = new ArrayList<>();
        BlockPos root = logicTile.getBlockPos();
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockEntity adjacent = level.getBlockEntity(root.relative(direction));
            if (adjacent == null || !isClassOrSubclass(adjacent.getClass(), SCRIBES_TILE_CLASS)) continue;
            Object adjacentLogic = Reflect.invoke(adjacent, "getLogicTile").orElse(null);
            if (adjacentLogic == logicTile) {
                offsets.add(adjacent.getBlockPos().subtract(root));
            }
        }
        return List.copyOf(offsets);
    }

    private static boolean isIdle(BlockEntity tile) {
        ItemStack stack = Reflect.<ItemStack>invoke(tile, "getStack").orElse(ItemStack.EMPTY);
        return !hasNativeState(tile) && stack.isEmpty();
    }

    private static boolean hasNativeState(BlockEntity tile) {
        if (activeRecipe(tile) != null) return true;
        if (Reflect.<Boolean>getField(tile, "crafting").orElse(false)) return true;
        if (Reflect.getIntField(tile, "craftingTicks").orElse(0) > 0) return true;
        List<?> consumed = Reflect.<List<?>>getField(tile, "consumedStacks").orElse(List.of());
        return !consumed.isEmpty();
    }

    @Nullable
    private static Object activeRecipe(BlockEntity tile) {
        return Reflect.getField(tile, "recipe").orElse(null);
    }

    private static boolean isClassOrSubclass(Class<?> type, String expectedName) {
        for (Class<?> current = type;
             current != null && current != Object.class;
             current = current.getSuperclass()) {
            if (expectedName.equals(current.getName())) return true;
        }
        return false;
    }

    private static Set<UUID> snapshotEntities(ServerLevel level, AABB region) {
        Set<UUID> result = new HashSet<>();
        for (ItemEntity entity : level.getEntitiesOfClass(ItemEntity.class, region)) {
            result.add(entity.getUUID());
        }
        return Set.copyOf(result);
    }

    static AABB outputRegion(BlockPos pos) {
        return new AABB(
                pos.getX() - 0.25, pos.getY() + 0.85, pos.getZ() - 0.25,
                pos.getX() + 1.25, pos.getY() + 1.75, pos.getZ() + 1.25);
    }

    private void resetLocalState() {
        level = null;
        machinePos = null;
        recipe = null;
        requiredMaterials = null;
        expectedOutput = ItemStack.EMPTY;
        experienceCost = 0;
        paidExperience = 0;
        started = false;
        startTick = 0L;
        entitiesBefore = Set.of();
        supportOffsets = List.of();
    }
}
