package com.huanghuang.rsintegration.mods.crockpot;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.batch.AbstractBatchDelegate;
import com.huanghuang.rsintegration.crafting.batch.BatchConcurrencyCapabilities;
import com.huanghuang.rsintegration.crafting.batch.IBatchDelegate;
import com.huanghuang.rsintegration.recipe.ModRecipeHandlers;
import com.huanghuang.rsintegration.recipe.ParrotFeedingRecipeHandler;
import com.huanghuang.rsintegration.reflection.probes.CrockPotReflection;
import com.mojang.datafixers.util.Pair;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.Parrot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Queue;

/** Executes one CrockPot parrot-feeding operation on a bound birdcage. */
public final class BirdcageBatchDelegate extends AbstractBatchDelegate {
    private static final long OUTPUT_DELAY_TICKS = 40L;

    private ServerLevel level;
    private ResourceKey<Level> dimension;
    private BlockPos pos;
    private Recipe<?> recipe;
    private BirdcageEggRecipe eggRecipe;
    private ItemStack expectedOutput = ItemStack.EMPTY;
    private long outputReadyAt = Long.MAX_VALUE;
    private boolean started;
    private boolean noOutputExpected;

    @Override
    public BatchConcurrencyCapabilities concurrencyCapabilities() {
        // The output is emitted into the world and may be picked up by another actor.
        // Keep one operation per cage; this also preserves the mod's 10-tick feed cooldown.
        return new BatchConcurrencyCapabilities(
                BatchConcurrencyCapabilities.MaterialOwnership.CHAIN_RESERVED,
                BatchConcurrencyCapabilities.OutputOwnership.OWNED_WORLD_CAPTURE,
                BatchConcurrencyCapabilities.CleanupContract.SEPARABLE_OFFLINE,
                BatchConcurrencyCapabilities.SideEffects.LOCAL_WORLD_ITEMS,
                BatchConcurrencyCapabilities.PreparationContract.RETRY_SAFE,
                List.of());
    }

    @Override
    public PreparationResult prepare(ServerPlayer player, ResourceLocation recipeId,
                                     @Nullable ResourceLocation dim, BlockPos pos) {
        if (!validateAndInit(player, recipeId, dim, pos)) {
            return PreparationResult.retry("birdcage is unavailable");
        }
        if (!hasCagedParrot()) {
            return PreparationResult.retry("birdcage has no parrot");
        }
        if (isOnCooldown()) {
            return PreparationResult.retry("birdcage feeding cooldown");
        }
        if (hasPendingOutput()) {
            // An egg queued by a manual feed has not reached the world yet.
            // Starting now would make its identical item indistinguishable from
            // this order's result when world-output capture begins.
            return PreparationResult.retry("birdcage pending output");
        }
        return PreparationResult.ready();
    }

    @Override
    public boolean validateAndInit(ServerPlayer player, ResourceLocation recipeId,
                                   @Nullable ResourceLocation dim, BlockPos pos) {
        ServerLevel resolved = CraftPacketUtils.resolveLevel(player.server, dim, player);
        if (resolved == null || !resolved.hasChunkAt(pos)) return false;
        Recipe<?> found = resolved.getRecipeManager().byKey(recipeId).orElse(null);
        if (found == null) found = BirdcageEggCatalog.resolve(resolved, recipeId);
        BlockEntity be = resolved.getBlockEntity(pos);
        if (found == null || be == null || CrockPotReflection.parrotFeedingRecipeClass == null
                || CrockPotReflection.birdcageBlockEntityClass == null
                || (!CrockPotReflection.parrotFeedingRecipeClass.isInstance(found)
                && !(found instanceof BirdcageEggRecipe))
                || !CrockPotReflection.birdcageBlockEntityClass.isInstance(be)) return false;
        this.level = resolved;
        this.dimension = resolved.dimension();
        this.pos = pos.immutable();
        this.recipe = found;
        this.eggRecipe = found instanceof BirdcageEggRecipe value ? value : null;
        this.expectedOutput = ItemStack.EMPTY;
        this.outputReadyAt = Long.MAX_VALUE;
        this.started = false;
        this.noOutputExpected = false;
        if (storageEndpoint() == null) this.network = CraftPacketUtils.resolveNetworkForCraft(player, dimension, pos);
        return true;
    }

    @Override
    @Nullable
    public List<IngredientSpec> getRequiredMaterials() {
        return recipe == null ? null : ModRecipeHandlers.handlerFor(recipe).getIngredients(recipe);
    }

    @Override
    public boolean tryStartSingleCraft(ServerPlayer player) {
        if (recipe == null || !hasCagedParrot() || isOnCooldown() || hasPendingOutput()) return false;
        this.ledger = new ExtractionLedger();
        ledger.setStorageEndpoint(storageEndpoint());
        List<IngredientSpec> specs = getRequiredMaterials();
        if (specs == null || specs.size() != 1) return false;
        ItemStack input = CraftPacketUtils.ensureMaterialAvailable(
                player, dimension, pos, specs.get(0).ingredient(), 1, ledger);
        if (input.isEmpty() || !ledger.commit(network, player)) return false;
        if (!feed(player, input.copyWithCount(1))) {
            ledger.refundCommitted(network, player);
            return false;
        }
        return true;
    }

    @Override
    public boolean tryStartWithMaterials(ServerPlayer player, List<ItemStack> materials,
                                         ExtractionLedger sharedLedger) {
        useSharedLedger(sharedLedger);
        return materials.size() == 1 && !materials.get(0).isEmpty()
                && hasCagedParrot() && !isOnCooldown() && !hasPendingOutput()
                && feed(player, materials.get(0).copyWithCount(1));
    }

    private boolean feed(ServerPlayer player, ItemStack input) {
        BlockEntity be = level.getBlockEntity(pos);
        if (be == null || !CrockPotReflection.birdcageBlockEntityClass.isInstance(be)) return false;
        int queueSize = outputQueue(be).size();
        try {
            Parrot parrot = findCagedParrot();
            if (parrot == null) return false;
            boolean fed;
            if (eggRecipe != null) {
                if (!eggRecipe.ingredient().test(input)) return false;
                Object foodValues = BirdcageEggCatalog.foodValuesFor(input, level);
                if (foodValues == null || CrockPotReflection.foodValuesClass == null) return false;
                Method feed = CrockPotReflection.birdcageBlockEntityClass.getMethod(
                        "fedByMeat", ItemStack.class, CrockPotReflection.foodValuesClass, Parrot.class);
                fed = Boolean.TRUE.equals(feed.invoke(be, input, foodValues, parrot));
            } else {
                Method feed = CrockPotReflection.birdcageBlockEntityClass.getMethod(
                        "fedByRecipe", ItemStack.class, CrockPotReflection.parrotFeedingRecipeClass,
                        net.minecraft.core.RegistryAccess.class, Parrot.class);
                fed = Boolean.TRUE.equals(feed.invoke(be, input, recipe, level.registryAccess(), parrot));
            }
            if (!fed) return false;
            Pair<ItemStack, Long> queued = newestOutput(outputQueue(be), queueSize);
            if (queued == null || queued.getFirst().isEmpty()) {
                // Some CrockPot feeds intentionally use a 0..N result range.
                // The input has already been consumed, so a zero roll is a
                // completed no-output operation, not a failed craft.
                if (!ParrotFeedingRecipeHandler.canProduceNoOutput(recipe)) return false;
                noOutputExpected = true;
                outputReadyAt = level.getGameTime() + 10L;
                started = true;
                markCraftStarted();
                return true;
            }
            expectedOutput = queued.getFirst().copy();
            outputReadyAt = queued.getSecond();
            started = true;
            markCraftStarted();
            return true;
        } catch (ReflectiveOperationException e) {
            RSIntegrationMod.LOGGER.error("[RSI-Birdcage] Failed to feed caged parrot", e);
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private Queue<Pair<ItemStack, Long>> outputQueue(BlockEntity be) {
        try {
            return (Queue<Pair<ItemStack, Long>>) CrockPotReflection.birdcageBlockEntityClass
                    .getMethod("getOutputBuffer").invoke(be);
        } catch (ReflectiveOperationException e) {
            return new java.util.ArrayDeque<>();
        }
    }

    @Nullable
    private static Pair<ItemStack, Long> newestOutput(Queue<Pair<ItemStack, Long>> queue, int previousSize) {
        if (queue.size() <= previousSize) return null;
        Pair<ItemStack, Long> latest = null;
        for (Pair<ItemStack, Long> entry : queue) latest = entry;
        return latest;
    }

    @Override
    protected boolean isMachineCraftFinished(ServerLevel current, BlockEntity be) {
        if (!started || current.getGameTime() <= outputReadyAt) return false;
        if (noOutputExpected) return !isOnCooldown();
        return !current.getEntitiesOfClass(ItemEntity.class, outputRegion(), this::isExpectedNewOutput).isEmpty();
    }

    @Override
    public ItemStack collectResult(ServerPlayer player) {
        if (level == null || level.getGameTime() <= outputReadyAt) return ItemStack.EMPTY;
        for (ItemEntity entity : level.getEntitiesOfClass(ItemEntity.class, outputRegion(), this::isExpectedNewOutput)) {
            ItemStack result = entity.getItem().copy();
            entity.discard();
            return result;
        }
        return ItemStack.EMPTY;
    }

    private boolean isExpectedNewOutput(ItemEntity entity) {
        return entity.isAlive() && !entity.getItem().isEmpty()
                && IBatchDelegate.matchesProducedItem(entity.getItem(), expectedOutput);
    }

    private boolean hasCagedParrot() {
        return findCagedParrot() != null;
    }

    @Nullable
    private Parrot findCagedParrot() {
        if (level == null || pos == null) return null;
        for (Parrot parrot : level.getEntitiesOfClass(Parrot.class, birdcageRegion())) {
            Entity vehicle = parrot.getVehicle();
            if (vehicle != null && vehicle.getClass().getName().equals("com.sihenzhang.crockpot.entity.Birdcage")) {
                return parrot;
            }
        }
        return null;
    }

    private boolean isOnCooldown() {
        if (level == null || pos == null) return true;
        BlockEntity be = level.getBlockEntity(pos);
        try {
            return be != null && Boolean.TRUE.equals(CrockPotReflection.birdcageBlockEntityClass
                    .getMethod("isOnCooldown").invoke(be));
        } catch (ReflectiveOperationException e) {
            return true;
        }
    }

    private boolean hasPendingOutput() {
        if (level == null || pos == null) return true;
        BlockEntity be = level.getBlockEntity(pos);
        return be == null || !outputQueue(be).isEmpty();
    }

    private AABB birdcageRegion() {
        return new AABB(pos.getX(), pos.getY(), pos.getZ(), pos.getX() + 1, pos.getY() + 2, pos.getZ() + 1).inflate(0.1);
    }

    private AABB outputRegion() {
        // BirdcageBlockEntity uses Containers.dropContents(), whose random spawn
        // point is inside the block but whose first movement can immediately put
        // the item in an adjacent cell. Keep the world-output capture and magnet
        // protection around the complete cage footprint plus its neighbours.
        return new AABB(pos).inflate(1.5);
    }

    @Override
    public BlockPos getMachinePos() {
        return pos;
    }

    @Override
    public ExpectedProduction getExpectedProduction() {
        return expectedOutput.isEmpty() ? null : new ExpectedProduction(expectedOutput, expectedOutput.getCount());
    }

    @Override
    public ItemStack getExpectedOutput() {
        // This is intentionally separate from getExpectedProduction(): the
        // generic world-output interceptor only arms when this method is non-null.
        // Without it, item collectors and magnet upgrades can claim the egg
        // before the chain observes its delayed drop.
        return expectedOutput.isEmpty() ? null : expectedOutput.copy();
    }

    @Override
    public AABB getOutputCaptureRegion() {
        return pos == null ? null : outputRegion();
    }

    @Override
    public boolean allowsOverlappingOutputCaptureOrigins() {
        return true;
    }

    @Override
    public void onBatchFinished(ServerPlayer player) {
        if (!markTerminalCleanup()) return;
        resetState();
    }
}
