package com.huanghuang.rsintegration.mods.vanilla;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.batch.AbstractBatchDelegate;
import com.huanghuang.rsintegration.network.RSIntegrationNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.*;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.common.ForgeHooks;
import net.minecraftforge.common.world.ForgeChunkManager;
import net.minecraftforge.items.ItemHandlerHelper;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/** Batch delegate for vanilla machine cooking (Furnace, Blast Furnace, Smoker). */
public final class VanillaMachineBatchDelegate extends AbstractBatchDelegate {

    private ServerPlayer player;
    private ServerLevel myLevel;
    private ResourceKey<Level> myDim;
    private BlockPos myPos;
    private Recipe<?> recipe;
    private ItemStack pendingResult;
    private boolean craftDone;

    // FURNACE path state
    private AbstractFurnaceBlockEntity furnaceBE;
    private MachineKind kind;
    private List<ItemStack> fuelStacks; // track fuel for refund decisions

    private enum MachineKind {
        FURNACE,
        CAMPFIRE,
        VIRTUAL
    }

    // CAMPFIRE path state
    private int campfireSlot = -1;
    private Object campfireBE;
    private boolean campfireChunkForced;
    private static final java.lang.reflect.Field CAMPFIRE_ITEMS;
    private static final java.lang.reflect.Field CAMPFIRE_COOKING_PROGRESS;
    private static final java.lang.reflect.Field CAMPFIRE_COOKING_TIME;

    static {
        java.lang.reflect.Field items = null;
        java.lang.reflect.Field cookingProgress = null;
        java.lang.reflect.Field cookingTime = null;
        try {
            Class<?> cfb = Class.forName(
                    "net.minecraft.world.level.block.entity.CampfireBlockEntity");
            items = resolveCampfireField(cfb, "items", "f_59042_");
            cookingProgress = resolveCampfireField(cfb, "cookingProgress", "f_59043_");
            cookingTime = resolveCampfireField(cfb, "cookingTime", "f_59044_");
        } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI-Vanilla] Campfire class probe failed", e); }
        CAMPFIRE_ITEMS = items;
        CAMPFIRE_COOKING_PROGRESS = cookingProgress;
        CAMPFIRE_COOKING_TIME = cookingTime;
    }

    private static java.lang.reflect.Field resolveCampfireField(Class<?> clazz, String official, String srg) {
        for (String name : new String[]{official, srg}) {
            try {
                java.lang.reflect.Field f = clazz.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException e) { RSIntegrationMod.LOGGER.debug("[RSI-Vanilla] campfire field not found", e); }
        }
        return null;
    }

    @Override
    public boolean validateAndInit(ServerPlayer player, ResourceLocation recipeId,
                                   @Nullable ResourceLocation dim, BlockPos pos) {
        this.player = player;
        this.myPos = pos;
        this.pendingResult = ItemStack.EMPTY;
        this.craftDone = false;
        this.furnaceBE = null;
        this.fuelStacks = null;

        ServerLevel level = CraftPacketUtils.resolveLevel(player.server, dim, player);
        if (level == null) {
            player.sendSystemMessage(Component.translatable("rsi.generic.error.dim_not_found"));
            return false;
        }
        com.huanghuang.rsintegration.util.ChunkUtils.loadChunk(level, pos);

        this.myLevel = level;
        this.myDim = level.dimension();

        Recipe<?> found = level.getRecipeManager().byKey(recipeId).orElse(null);
        // CraftTweaker wraps vanilla recipes with IDs like
        //   crafttweaker:minecraft.coast_armor_trim_smithing_template
        // Recover the original key: replace first dot in path with colon.
        if (found == null && "crafttweaker".equals(recipeId.getNamespace())) {
            String path = recipeId.getPath();
            int dot = path.indexOf('.');
            if (dot > 0) {
                ResourceLocation vanillaKey = new ResourceLocation(
                        path.substring(0, dot), path.substring(dot + 1));
                found = level.getRecipeManager().byKey(vanillaKey).orElse(null);
            }
        }
        if (found == null) {
            player.sendSystemMessage(Component.translatable("rsi.generic.error.recipe_not_found", recipeId.toString()));
            return false;
        }
        this.recipe = found;

        BlockEntity be = level.getBlockEntity(pos);
        if (be == null) {
            String blockId = ForgeRegistries.BLOCKS
                    .getKey(level.getBlockState(pos).getBlock()).toString();
            // Smithing Table and Stonecutter have no BE (or BE is unloaded)
            // — always VIRTUAL. Campfire also falls back to VIRTUAL when
            // the chunk is not loaded.
            if (blockId.contains("smithing_table") || blockId.contains("campfire")
                    || blockId.contains("stonecutter")) {
                this.kind = MachineKind.VIRTUAL;
            } else if (recipe instanceof AbstractCookingRecipe
                    && blockMatchesCooking(blockId, recipe)) {
                // VIRTUAL fallback for furnace/blast/smoker when the chunk isn't
                // fully loaded. Only accept if the block ID matches the recipe type
                // so a smithing table doesn't steal smelting recipes.
                this.kind = MachineKind.VIRTUAL;
            } else {
                // Wrong machine for this recipe — return false so AsyncChain
                // tries the next bound machine.
                return false;
            }
        } else if (be instanceof AbstractFurnaceBlockEntity fbe) {
            // Non-cooking recipes (smithing, stonecutting) can't run on a furnace,
            // but the furnace binding can still serve as a VIRTUAL proxy — the
            // craft is computed directly without machine interaction.
            if (!(recipe instanceof AbstractCookingRecipe)) {
                this.kind = MachineKind.VIRTUAL;
                return true;
            }
            // Validate furnace type matches recipe type (safety net —
            // classification should route recipes to the correct ModType,
            // but mismatched bindings could still exist from older data).
            String beClassName = be.getClass().getName().toLowerCase();
            if (recipe instanceof BlastingRecipe && !beClassName.contains("blast")) {
                RSIntegrationMod.LOGGER.debug("[RSI-Vanilla] Type mismatch: BlastingRecipe on non-blast machine {} at {}",
                        beClassName, myPos);
                return false;
            }
            if (recipe instanceof SmokingRecipe && !beClassName.contains("smoker")) {
                RSIntegrationMod.LOGGER.debug("[RSI-Vanilla] Type mismatch: SmokingRecipe on non-smoker machine {} at {}",
                        beClassName, myPos);
                return false;
            }
            if (recipe instanceof SmeltingRecipe
                    && (beClassName.contains("blast") || beClassName.contains("smoker"))) {
                if (!beClassName.contains("furnace") || beClassName.contains("blast")) {
                    RSIntegrationMod.LOGGER.debug("[RSI-Vanilla] Type mismatch: SmeltingRecipe on wrong furnace type {} at {}",
                            beClassName, myPos);
                    return false;
                }
            }

            // Check furnace is idle
            ItemStack slot0 = fbe.getItem(0);
            if (!slot0.isEmpty()) {
                List<Ingredient> ingredients = recipe.getIngredients();
                boolean matches = false;
                for (Ingredient ing : ingredients) {
                    if (ing.test(slot0)) { matches = true; break; }
                }
                if (!matches) {
                    player.sendSystemMessage(Component.translatable("rsi.vanilla.error.furnace_occupied"));
                    return false;
                }
            }

            this.furnaceBE = fbe;
            this.kind = MachineKind.FURNACE;
        } else if (CAMPFIRE_ITEMS != null
                && be instanceof net.minecraft.world.level.block.entity.CampfireBlockEntity) {
            if (!(recipe instanceof CampfireCookingRecipe)) {
                this.kind = MachineKind.VIRTUAL;
                return true;
            }
            try {
                @SuppressWarnings("unchecked")
                net.minecraft.core.NonNullList<ItemStack> items =
                        (net.minecraft.core.NonNullList<ItemStack>) CAMPFIRE_ITEMS.get(be);
                // Find an empty slot
                int slot = -1;
                for (int i = 0; i < items.size(); i++) {
                    if (items.get(i).isEmpty()) { slot = i; break; }
                }
                if (slot < 0) {
                    player.sendSystemMessage(Component.translatable(
                            "rsi.vanilla.error.campfire_full"));
                    return false;
                }
                this.campfireBE = be;
                this.campfireSlot = slot;
                this.kind = MachineKind.CAMPFIRE;
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.error("[RSI-Vanilla] Campfire reflection failed", e);
                return false;
            }
        } else {
            // Other BE-backed machines: Stonecutter (has BE)
            this.kind = MachineKind.VIRTUAL;
        }

        return true;
    }

    // ── Private ledger (direct path) ───────────────────────────────

    @Override
    public boolean tryStartSingleCraft(ServerPlayer player) {
        this.player = player;
        this.ledger = new ExtractionLedger();
        this.usingSharedLedger = false;
        this.network = CraftPacketUtils.resolveNetworkForCraft(player, myDim, myPos);
        if (this.network == null) {
            this.network = RSIntegrationNetwork.resolveNetworkFromPlayer(player);
        }
        this.craftDone = false;
        this.fuelStacks = null;

        if (kind == MachineKind.FURNACE) {
            return tryStartFurnace(player);
        } else if (kind == MachineKind.CAMPFIRE) {
            return tryStartCampfire(player);
        } else {
            return tryStartVirtual(player);
        }
    }

    // ── Shared ledger (chain path, getRequiredMaterials returns null/empty) ─

    @Override
    public boolean tryStartSingleCraft(ServerPlayer player, ExtractionLedger sharedLedger) {
        this.player = player;
        this.sharedLedger = sharedLedger;
        this.usingSharedLedger = true;
        this.network = CraftPacketUtils.resolveNetworkForCraft(player, myDim, myPos);
        if (this.network == null) {
            this.network = RSIntegrationNetwork.resolveNetworkFromPlayer(player);
        }
        this.craftDone = false;
        this.fuelStacks = null;

        if (kind == MachineKind.FURNACE) {
            return tryStartFurnace(player);
        } else if (kind == MachineKind.CAMPFIRE) {
            return tryStartCampfire(player);
        } else {
            return tryStartVirtual(player);
        }
    }

    // ── Pre-reserved materials (chain path, getRequiredMaterials returns specs) ─

    @Nullable
    @Override
    public List<IngredientSpec> getRequiredMaterials() {
        return CraftPacketUtils.extractIngredientSpecs(recipe);
    }

    @Override
    public boolean tryStartWithMaterials(ServerPlayer player,
                                         List<ItemStack> materials,
                                         ExtractionLedger sharedLedger) {
        this.player = player;
        this.sharedLedger = sharedLedger;
        this.usingSharedLedger = true;
        this.network = CraftPacketUtils.resolveNetworkForCraft(player, myDim, myPos);
        if (this.network == null) {
            this.network = RSIntegrationNetwork.resolveNetworkFromPlayer(player);
        }
        this.craftDone = false;
        this.fuelStacks = null;

        if (kind == MachineKind.FURNACE) {
            return tryStartFurnaceWithMaterials(materials);
        } else if (kind == MachineKind.CAMPFIRE) {
            return tryStartCampfireWithMaterials(materials);
        } else {
            // Virtual: materials already committed by chain, compute result directly
            this.pendingResult = computeResult();
            if (this.pendingResult.isEmpty()) return false;
            this.craftDone = true;
            return true;
        }
    }

    // ── FURNACE path ──────────────────────────────────────────────

    private boolean tryStartFurnace(ServerPlayer player) {
        List<Ingredient> ingredients = recipe.getIngredients();
        if (ingredients.isEmpty()) return false;

        // Extract and place input ingredient
        Ingredient input = ingredients.get(0);
        if (!input.isEmpty()) {
            ExtractionLedger activeLedger = usingSharedLedger ? sharedLedger : ledger;
            ItemStack extracted = CraftPacketUtils.ensureMaterialAvailable(
                    player, myDim, myPos, input, 1, activeLedger);
            if (extracted.isEmpty()) {
                player.sendSystemMessage(Component.translatable(
                        "rsi.generic.error.missing_materials",
                        CraftPacketUtils.describeIngredient(input)));
                return false;
            }
            furnaceBE.setItem(0, extracted.copy());
        }

        // Auto-supply fuel (extracts directly from RS, outside ledger)
        if (!ensureFuel(player)) {
            // Refund the input
            ItemStack refund = furnaceBE.getItem(0);
            if (!refund.isEmpty()) {
                furnaceBE.setItem(0, ItemStack.EMPTY);
                if (usingSharedLedger) {
                    // Chain will handle refund via refundCommitted; just clear slot
                } else if (ledger != null && !ledger.isCommitted()) {
                    ledger.rollback(player);
                }
            }
            player.sendSystemMessage(Component.translatable("rsi.vanilla.error.no_fuel"));
            return false;
        }

        // Commit private ledger
        if (!usingSharedLedger && ledger != null && !ledger.isCommitted()) {
            if (!ledger.commit(network, player)) {
                // Refund input
                ItemStack refund = furnaceBE.getItem(0);
                if (!refund.isEmpty()) {
                    furnaceBE.setItem(0, ItemStack.EMPTY);
                    ItemHandlerHelper.giveItemToPlayer(player, refund);
                }
                // Refund fuel (extracted outside ledger)
                ItemStack fuelRefund = furnaceBE.getItem(1);
                if (!fuelRefund.isEmpty()) {
                    furnaceBE.setItem(1, ItemStack.EMPTY);
                    ItemHandlerHelper.giveItemToPlayer(player, fuelRefund);
                }
                player.sendSystemMessage(Component.translatable(
                        "rsi.generic.error.craft_failed", "Extraction commit failed"));
                return false;
            }
        }

        furnaceBE.setChanged();
        myLevel.sendBlockUpdated(myPos,
                myLevel.getBlockState(myPos), myLevel.getBlockState(myPos), 3);
        return true;
    }

    private boolean tryStartFurnaceWithMaterials(List<ItemStack> materials) {
        if (materials.isEmpty()) return false;

        // Place pre-reserved input (chain already committed the ledger)
        furnaceBE.setItem(0, materials.get(0).copy());

        // Auto-supply fuel (extracts directly from RS, outside ledger)
        if (!ensureFuel(player)) {
            ItemStack refund = furnaceBE.getItem(0);
            if (!refund.isEmpty()) {
                furnaceBE.setItem(0, ItemStack.EMPTY);
            }
            player.sendSystemMessage(Component.translatable("rsi.vanilla.error.no_fuel"));
            return false;
        }

        furnaceBE.setChanged();
        myLevel.sendBlockUpdated(myPos,
                myLevel.getBlockState(myPos), myLevel.getBlockState(myPos), 3);
        return true;
    }

    private boolean ensureFuel(ServerPlayer player) {
        // Check if furnace already has litTime > 0
        try {
            java.lang.reflect.Field litTimeField = AbstractFurnaceBlockEntity.class
                    .getDeclaredField("litTime");
            litTimeField.setAccessible(true);
            if (litTimeField.getInt(furnaceBE) > 0) return true;
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-Vanilla] litTime probe failed", e);
        }

        // Check existing fuel in slot 1
        ItemStack existingFuel = furnaceBE.getItem(1);
        if (!existingFuel.isEmpty()) {
            int burnTime = ForgeHooks.getBurnTime(existingFuel, recipe.getType());
            if (burnTime > 0) return true;
        }

        // Try to extract fuel from RS network
        if (network == null) return false;

        int cookingTime;
        if (recipe instanceof AbstractCookingRecipe acr) {
            cookingTime = acr.getCookingTime();
        } else {
            cookingTime = 200; // default
        }

        // Scan RS storage for burnable items
        this.fuelStacks = new ArrayList<>();
        var stacks = new ArrayList<>(network.getItemStorageCache().getList().getStacks());
        for (var entry : stacks) {
            ItemStack candidate = entry.getStack();
            if (candidate.isEmpty()) continue;
            int singleBurnTime = ForgeHooks.getBurnTime(candidate, recipe.getType());
            if (singleBurnTime <= 0) continue;
            int needed = Math.max(1, (cookingTime + singleBurnTime - 1) / singleBurnTime);
            int available = Math.min(needed, candidate.getCount());

            ItemStack extracted = network.extractItem(
                    candidate.copyWithCount(1), available,
                    com.refinedmods.refinedstorage.api.util.Action.PERFORM);
            if (!extracted.isEmpty()) {
                fuelStacks.add(extracted.copy());
                furnaceBE.setItem(1, extracted.copy());
                player.displayClientMessage(
                        Component.translatable("rsi.vanilla.info.fuel_supplied", extracted.getCount()), true);
                return true;
            }
        }

        return false;
    }

    // ── VIRTUAL path ──────────────────────────────────────────────

    private boolean tryStartVirtual(ServerPlayer player) {
        List<IngredientSpec> specs = CraftPacketUtils.extractIngredientSpecs(recipe);
        if (specs == null || specs.isEmpty()) return false;

        this.pendingResult = computeResult();
        if (this.pendingResult.isEmpty()) return false;

        ExtractionLedger activeLedger = usingSharedLedger ? sharedLedger : ledger;

        // Reserve all ingredients
        List<ItemStack> extracted = new ArrayList<>();
        for (IngredientSpec spec : specs) {
            if (spec.isEmpty()) continue;
            ItemStack stack = CraftPacketUtils.ensureMaterialAvailable(
                    player, myDim, myPos, spec.ingredient(), spec.count(), activeLedger);
            if (stack.isEmpty()) {
                if (!usingSharedLedger) {
                    ledger.rollback(player);
                }
                return false;
            }
            extracted.add(stack);
        }

        // Commit private ledger
        if (!usingSharedLedger && ledger != null && !ledger.isCommitted()) {
            if (!ledger.commit(network, player)) {
                return false;
            }
        }

        this.craftDone = true;
        return true;
    }

    private ItemStack computeResult() {
        try {
            return recipe.getResultItem(myLevel.registryAccess()).copy();
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.error("[RSI-Vanilla] computeResult failed for {}",
                    recipe.getId(), e);
        }
        return ItemStack.EMPTY;
    }

    // ── CAMPFIRE path ──────────────────────────────────────────────

    private void ensureCampfireLit() {
        net.minecraft.world.level.block.state.BlockState state = myLevel.getBlockState(myPos);
        if (state.hasProperty(BlockStateProperties.LIT) && !state.getValue(BlockStateProperties.LIT)) {
            myLevel.setBlock(myPos, state.setValue(BlockStateProperties.LIT, true), 3);
        }
    }

    private boolean tryStartCampfire(ServerPlayer player) {
        List<Ingredient> ingredients = recipe.getIngredients();
        if (ingredients.isEmpty()) return false;

        Ingredient input = ingredients.get(0);
        if (!input.isEmpty()) {
            ExtractionLedger activeLedger = usingSharedLedger ? sharedLedger : ledger;
            ItemStack extracted = CraftPacketUtils.ensureMaterialAvailable(
                    player, myDim, myPos, input, 1, activeLedger);
            if (extracted.isEmpty()) {
                player.sendSystemMessage(Component.translatable(
                        "rsi.generic.error.missing_materials",
                        CraftPacketUtils.describeIngredient(input)));
                return false;
            }
            int cookTime = recipe instanceof CampfireCookingRecipe ccr ? ccr.getCookingTime() : 600;
            try {
                @SuppressWarnings("unchecked")
                net.minecraft.core.NonNullList<ItemStack> items =
                        (net.minecraft.core.NonNullList<ItemStack>) CAMPFIRE_ITEMS.get(campfireBE);
                items.set(campfireSlot, extracted.copy());
                int[] prog = (int[]) CAMPFIRE_COOKING_PROGRESS.get(campfireBE);
                prog[campfireSlot] = 0;
                int[] times = (int[]) CAMPFIRE_COOKING_TIME.get(campfireBE);
                times[campfireSlot] = cookTime;
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.error("[RSI-Vanilla] Campfire placement failed", e);
                return false;
            }
        }

        if (!usingSharedLedger && ledger != null && !ledger.isCommitted()) {
            if (!ledger.commit(network, player)) {
                clearCampfireSlot();
                return false;
            }
        }

        ensureCampfireLit();
        if (campfireBE instanceof BlockEntity be) be.setChanged();
        myLevel.sendBlockUpdated(myPos,
                myLevel.getBlockState(myPos), myLevel.getBlockState(myPos), 3);
        campfireForceLoad(true);
        return true;
    }

    private boolean tryStartCampfireWithMaterials(List<ItemStack> materials) {
        if (materials.isEmpty()) return false;

        int cookTime = recipe instanceof CampfireCookingRecipe ccr ? ccr.getCookingTime() : 600;
        try {
            @SuppressWarnings("unchecked")
            net.minecraft.core.NonNullList<ItemStack> items =
                    (net.minecraft.core.NonNullList<ItemStack>) CAMPFIRE_ITEMS.get(campfireBE);
            items.set(campfireSlot, materials.get(0).copy());
            int[] prog = (int[]) CAMPFIRE_COOKING_PROGRESS.get(campfireBE);
            prog[campfireSlot] = 0;
            int[] times = (int[]) CAMPFIRE_COOKING_TIME.get(campfireBE);
            times[campfireSlot] = cookTime;
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.error("[RSI-Vanilla] Campfire placement failed", e);
            return false;
        }

        ensureCampfireLit();
        if (campfireBE instanceof BlockEntity be) be.setChanged();
        myLevel.sendBlockUpdated(myPos,
                myLevel.getBlockState(myPos), myLevel.getBlockState(myPos), 3);
        campfireForceLoad(true);
        return true;
    }

    private boolean isCampfireComplete() {
        // Vanilla CampfireBlockEntity.cookTick increments cookingProgress
        // each tick; when it reaches cookingTime the result is spawned as an
        // ItemEntity and the slot is cleared.  Detect completion by the slot
        // being empty.
        try {
            @SuppressWarnings("unchecked")
            net.minecraft.core.NonNullList<ItemStack> items =
                    (net.minecraft.core.NonNullList<ItemStack>) CAMPFIRE_ITEMS.get(campfireBE);
            return items.get(campfireSlot).isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    private ItemStack collectCampfireResult() {
        campfireForceLoad(false);

        // Vanilla spawned the result as an ItemEntity above the campfire.
        // Capture it for full NBT fidelity.
        if (myLevel != null && myLevel.isLoaded(myPos)) {
            List<net.minecraft.world.entity.item.ItemEntity> entities =
                    myLevel.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                            new net.minecraft.world.phys.AABB(myPos).inflate(2.0));
            for (net.minecraft.world.entity.item.ItemEntity entity : entities) {
                if (!entity.isRemoved()) {
                    ItemStack stack = entity.getItem();
                    if (!stack.isEmpty()) {
                        entity.discard();
                        return stack.copy();
                    }
                }
            }
        }

        // Fallback: entity already despawned or chunk unloaded
        return computeResult();
    }

    private void clearCampfireSlot() {
        try {
            @SuppressWarnings("unchecked")
            net.minecraft.core.NonNullList<ItemStack> items =
                    (net.minecraft.core.NonNullList<ItemStack>) CAMPFIRE_ITEMS.get(campfireBE);
            ItemStack slotItem = items.get(campfireSlot);
            if (!slotItem.isEmpty()) {
                refundToRSNetwork(slotItem.copy());
            }
            items.set(campfireSlot, ItemStack.EMPTY);
            int[] prog = (int[]) CAMPFIRE_COOKING_PROGRESS.get(campfireBE);
            prog[campfireSlot] = 0;
            int[] times = (int[]) CAMPFIRE_COOKING_TIME.get(campfireBE);
            times[campfireSlot] = 0;
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.error("[RSI-Vanilla] Campfire clear failed", e);
        }
    }

    private static boolean blockMatchesCooking(String blockId, Recipe<?> recipe) {
        if (recipe instanceof BlastingRecipe) return blockId.contains("blast_furnace");
        if (recipe instanceof SmokingRecipe) return blockId.contains("smoker");
        if (recipe instanceof CampfireCookingRecipe) return blockId.contains("campfire");
        return blockId.contains("furnace") && !blockId.contains("blast");
    }

    // ── polling / collection ──────────────────────────────────────

    @Override
    protected boolean isMachineCraftFinished(ServerLevel level, BlockEntity be) {
        if (kind == MachineKind.VIRTUAL) return craftDone;
        if (kind == MachineKind.CAMPFIRE) return isCampfireComplete();

        if (furnaceBE == null) return true;

        // Check result slot has output
        ItemStack result = furnaceBE.getItem(2);
        return !result.isEmpty();
    }

    @Override
    public ItemStack collectResult(ServerPlayer player) {
        if (kind == MachineKind.VIRTUAL) {
            ItemStack r = pendingResult.copy();
            pendingResult = ItemStack.EMPTY;
            craftDone = false;
            return r;
        }
        if (kind == MachineKind.CAMPFIRE) {
            return collectCampfireResult();
        }

        if (furnaceBE == null) return ItemStack.EMPTY;

        ItemStack result = furnaceBE.getItem(2).copy();
        if (!result.isEmpty()) {
            furnaceBE.setItem(2, ItemStack.EMPTY);
            // Clear input slot too (it was consumed)
            furnaceBE.setItem(0, ItemStack.EMPTY);
            furnaceBE.setChanged();
        }
        return result;
    }

    // ── lifecycle ─────────────────────────────────────────────────

    @Override
    protected void clearMachineState(BlockEntity be, ServerPlayer player) {
        if (kind == MachineKind.FURNACE && furnaceBE != null) {
            // Refund unprocessed input from slot 0
            ItemStack slot0 = furnaceBE.getItem(0);
            if (!slot0.isEmpty()) {
                furnaceBE.setItem(0, ItemStack.EMPTY);
                refundToRSNetwork(slot0);
            }
            // Refund any result from slot 2
            ItemStack slot2 = furnaceBE.getItem(2);
            if (!slot2.isEmpty()) {
                furnaceBE.setItem(2, ItemStack.EMPTY);
                refundToRSNetwork(slot2);
            }
            // Do NOT refund fuel from slot 1 (already partially consumed)
            furnaceBE.setChanged();
        }
        if (kind == MachineKind.CAMPFIRE && campfireBE != null) {
            campfireForceLoad(false);
            clearCampfireSlot();
        }

        // Rollback uncommitted private ledger
        if (ledger != null && !ledger.isCommitted()) {
            ledger.rollback(player);
        }

        pendingResult = ItemStack.EMPTY;
        craftDone = false;
        fuelStacks = null;
        resetState();
    }

    @Override
    public void onBatchFinished(ServerPlayer player) {
        if (kind == MachineKind.CAMPFIRE) {
            campfireForceLoad(false);
        }
        pendingResult = ItemStack.EMPTY;
        craftDone = false;
        fuelStacks = null;
        resetState();
    }

    @Override
    public BlockPos getMachinePos() {
        return myPos;
    }

    // ── helpers ───────────────────────────────────────────────────

    private void campfireForceLoad(boolean load) {
        if (campfireChunkForced == load) return;
        try {
            int cx = myPos.getX() >> 4;
            int cz = myPos.getZ() >> 4;
            ForgeChunkManager.forceChunk(myLevel, RSIntegrationMod.MOD_ID,
                    myPos, cx, cz, load, true);
            campfireChunkForced = load;
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-Vanilla] Campfire chunk force-load failed", e);
        }
    }

    private void refundToRSNetwork(ItemStack stack) {
        if (network != null) {
            ItemStack leftover = network.insertItem(stack.copy(), stack.getCount(),
                    com.refinedmods.refinedstorage.api.util.Action.PERFORM);
            if (!leftover.isEmpty() && player != null) {
                ItemHandlerHelper.giveItemToPlayer(player, leftover);
            }
        } else if (player != null) {
            ItemHandlerHelper.giveItemToPlayer(player, stack.copy());
        }
    }
}
