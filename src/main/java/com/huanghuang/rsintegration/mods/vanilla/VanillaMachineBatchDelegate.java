package com.huanghuang.rsintegration.mods.vanilla;

import com.huanghuang.rsintegration.util.ChunkUtils;

import com.huanghuang.rsintegration.network.RSIntegrationNetwork;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.batch.AbstractBatchDelegate;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
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
import net.minecraft.world.level.block.entity.BlastFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;
import net.minecraft.world.level.block.entity.SmokerBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.common.ForgeHooks;
import net.minecraftforge.common.world.ForgeChunkManager;
import net.minecraftforge.items.ItemHandlerHelper;
import org.jetbrains.annotations.NotNull;

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

    private static final java.lang.reflect.Field LIT_TIME_FIELD = resolveLitTimeField();

    private static java.lang.reflect.Field resolveLitTimeField() {
        for (String name : new String[]{"litTime", "f_58315_"}) {
            try {
                java.lang.reflect.Field f = AbstractFurnaceBlockEntity.class.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {}
        }
        RSIntegrationMod.LOGGER.warn("[RSI-Vanilla] litTime field not found (no SRG match)");
        return null;
    }

    @Override
    public boolean acceptsMachineWithoutBlockEntity(ServerLevel level, BlockPos pos) {
        ResourceLocation blockId = ForgeRegistries.BLOCKS.getKey(level.getBlockState(pos).getBlock());
        if (blockId == null) return false;
        String path = blockId.getPath();
        return path.contains("stonecutter")
                || path.contains("smithing_table")
                || path.contains("campfire");
    }

    @Override
    public boolean validateAndInit(ServerPlayer player, ResourceLocation recipeId,
                                   @Nullable ResourceLocation dim, BlockPos pos) {
        this.player = player;
        this.myPos = pos;
        this.pendingResult = ItemStack.EMPTY;
        this.craftDone = false;
        this.furnaceBE = null;

        ServerLevel level = CraftPacketUtils.resolveLevel(player.server, dim, player);
        if (level == null) {
            player.sendSystemMessage(Component.translatable("rsi.generic.error.dim_not_found"));
            return false;
        }
        ChunkUtils.loadChunk(level, pos);

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
                    && CookingMachineFamily.fromRecipe(recipe).matches(
                            level.getBlockState(pos).getBlock())) {
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
            CookingMachineFamily expectedFamily = CookingMachineFamily.fromRecipe(recipe);
            CookingMachineFamily actualFamily = CookingMachineFamily.fromBlock(
                    level.getBlockState(pos).getBlock());
            if (expectedFamily == CookingMachineFamily.UNKNOWN || expectedFamily != actualFamily) {
                RSIntegrationMod.LOGGER.debug("[RSI-Vanilla] Cooking family mismatch: recipe={} expected={} actual={} at {}",
                        recipe.getId(), expectedFamily, actualFamily, myPos);
                return false;
            }
            BrickFurnaceCompat.Eligibility brickEligibility = BrickFurnaceCompat.canExecute(be, recipe);
            if (!brickEligibility.allowed()) {
                RSIntegrationMod.LOGGER.debug("[RSI-BrickFurnace] Rejected recipe {} at {}: {}",
                        recipe.getId(), myPos, brickEligibility.detail());
                player.sendSystemMessage(Component.translatable(
                        "rsi.brickfurnace.error.recipe_rejected", brickEligibility.detail()));
                return false;
            }

            // The chain owns only an idle machine. Existing output cannot be
            // distinguished from this craft's product after automation starts.
            if (!fbe.getItem(2).isEmpty()) {
                player.sendSystemMessage(Component.translatable("rsi.vanilla.error.furnace_occupied"));
                return false;
            }

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
    public boolean supportsConcurrentNodeExecution() {
        return true;
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

        // Phase 1: Reserve input in ledger (template — not yet extracted from RS)
        Ingredient input = ingredients.get(0);
        ItemStack inputTemplate = ItemStack.EMPTY;
        if (!input.isEmpty()) {
            ExtractionLedger activeLedger = usingSharedLedger ? sharedLedger : ledger;
            inputTemplate = CraftPacketUtils.ensureMaterialAvailable(
                    player, myDim, myPos, input, 1, activeLedger);
            if (inputTemplate.isEmpty()) {
                player.sendSystemMessage(Component.translatable(
                        "rsi.generic.error.missing_materials",
                        CraftPacketUtils.describeIngredient(input)));
                return false;
            }
        }

        // Phase 2: Commit BEFORE placing — commit failure leaves the furnace untouched
        if (!usingSharedLedger && ledger != null && !ledger.isCommitted()) {
            if (!ledger.commit(network, player)) {
                player.sendSystemMessage(Component.translatable(
                        "rsi.generic.error.craft_failed", "Extraction commit failed"));
                return false;
            }
        }

        // Phase 3: Place REAL input (post-commit) on furnace
        if (!inputTemplate.isEmpty()) {
            furnaceBE.setItem(0, inputTemplate.copy());
            BrickFurnaceCompat.invalidateRecipeCache(furnaceBE);
        }

        // Phase 4: Supply fuel (real extraction + placement, outside ledger)
        if (!ensureFuel(player)) {
            // Discard input from furnace — abort() refunds the ledger, so
            // refunding the physical item here would double-refund.
            furnaceBE.setItem(0, ItemStack.EMPTY);
            player.sendSystemMessage(Component.translatable("rsi.vanilla.error.no_fuel"));
            return false;
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
        BrickFurnaceCompat.invalidateRecipeCache(furnaceBE);

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
        int cookingTime = recipe instanceof AbstractCookingRecipe acr
                ? BrickFurnaceCompat.effectiveCookTicks(furnaceBE, acr) : 200;

        // Burn time already banked in litTime counts toward this item's cook.
        int litTime = 0;
        try {
            if (LIT_TIME_FIELD != null) {
                litTime = LIT_TIME_FIELD.getInt(furnaceBE);
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-Vanilla] litTime probe failed", e);
        }
        int remainingCook = Math.max(0, cookingTime - litTime);
        if (remainingCook == 0) return true; // current burn already covers the whole cook

        // Existing fuel in slot 1: top up with more of the SAME type until it covers
        // the remaining cook. A single stick (burn 100) can't finish a 200-tick smelt,
        // so we must ensure enough units are present rather than trusting burnTime > 0.
        ItemStack existing = furnaceBE.getItem(1);
        if (!existing.isEmpty()) {
            int singleBurn = BrickFurnaceCompat.effectiveBurnTicks(
                    furnaceBE, existing, fuelRecipeType());
            if (singleBurn <= 0) return false; // non-fuel item blocking the slot
            int needed = VanillaFurnaceFuelPolicy.requiredAmount(remainingCook, singleBurn);
            int slotLimit = Math.min(existing.getMaxStackSize(), furnaceBE.getMaxStackSize());
            if (needed > slotLimit) return false;
            if (existing.getCount() >= needed) return true;
            if (network == null) return false; // can't top up — insufficient fuel
            int topUp = needed - existing.getCount();
            ItemStack extra = extractExactFuel(existing, topUp);
            if (extra.isEmpty()) return false;
            ItemStack merged = existing.copy();
            merged.grow(extra.getCount());
            furnaceBE.setItem(1, merged);
            return true;
        }

        // Slot empty: apply the shared deterministic policy (configured fuels
        // first, then other safe fuels, then the best safe partial coverage).
        if (network == null) return false;
        List<ItemStack> candidates = new ArrayList<>();
        for (var entry : network.getItemStorageCache().getList().getStacks()) {
            candidates.add(entry.getStack());
        }
        VanillaFurnaceFuelPolicy.Selection selection = VanillaFurnaceFuelPolicy.select(
                candidates, RSIntegrationConfig.VANILLA_FURNACE_FUEL_PRIORITY.get(),
                remainingCook,
                stack -> BrickFurnaceCompat.effectiveBurnTicks(furnaceBE, stack, fuelRecipeType()));
        return selection != null && supplyFuel(player, selection.fuel(), selection.amount());
    }

    private RecipeType<?> fuelRecipeType() {
        return switch (CookingMachineFamily.fromRecipe(recipe)) {
            case BLAST_FURNACE -> RecipeType.BLASTING;
            case SMOKER -> RecipeType.SMOKING;
            default -> RecipeType.SMELTING;
        };
    }

    /** Extract {@code amount} of {@code fuelType} from RS into the fuel slot. */
    private boolean supplyFuel(ServerPlayer player, ItemStack fuelType, int amount) {
        ItemStack extracted = extractExactFuel(fuelType, amount);
        if (extracted.isEmpty()) return false;
        furnaceBE.setItem(1, extracted.copy());
        player.displayClientMessage(
                Component.translatable("rsi.vanilla.info.fuel_supplied", extracted.getCount()), true);
        return true;
    }

    /** Extract exactly the requested count, refunding a concurrent partial result. */
    private ItemStack extractExactFuel(ItemStack fuelType, int amount) {
        ItemStack extracted = network.extractItem(fuelType.copyWithCount(1), amount,
                com.refinedmods.refinedstorage.api.util.Action.PERFORM);
        if (extracted.getCount() == amount) return extracted;
        if (!extracted.isEmpty()) {
            network.insertItem(extracted, extracted.getCount(),
                    com.refinedmods.refinedstorage.api.util.Action.PERFORM);
        }
        return ItemStack.EMPTY;
    }

    /** Refund any whole (unburned) fuel items left in slot 1 back to RS. */
    private void refundLeftoverFuel() {
        if (furnaceBE == null) return;
        ItemStack fuel = furnaceBE.getItem(1);
        if (fuel.isEmpty() || BrickFurnaceCompat.effectiveBurnTicks(
                furnaceBE, fuel, fuelRecipeType()) <= 0) return;
        furnaceBE.setItem(1, ItemStack.EMPTY);
        furnaceBE.setChanged();
        refundToRSNetwork(fuel);
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
        ItemStack inputTemplate = ItemStack.EMPTY;
        if (!input.isEmpty()) {
            ExtractionLedger activeLedger = usingSharedLedger ? sharedLedger : ledger;
            inputTemplate = CraftPacketUtils.ensureMaterialAvailable(
                    player, myDim, myPos, input, 1, activeLedger);
            if (inputTemplate.isEmpty()) {
                player.sendSystemMessage(Component.translatable(
                        "rsi.generic.error.missing_materials",
                        CraftPacketUtils.describeIngredient(input)));
                return false;
            }
        }

        // Commit BEFORE placing — commit failure leaves campfire untouched
        if (!usingSharedLedger && ledger != null && !ledger.isCommitted()) {
            if (!ledger.commit(network, player)) {
                player.sendSystemMessage(Component.translatable(
                        "rsi.generic.error.craft_failed", "Extraction commit failed"));
                return false;
            }
        }

        // Place REAL item (post-commit) on campfire
        if (!inputTemplate.isEmpty()) {
            int cookTime = recipe instanceof CampfireCookingRecipe ccr ? ccr.getCookingTime() : 600;
            try {
                @SuppressWarnings("unchecked")
                net.minecraft.core.NonNullList<ItemStack> items =
                        (net.minecraft.core.NonNullList<ItemStack>) CAMPFIRE_ITEMS.get(campfireBE);
                items.set(campfireSlot, inputTemplate.copy());
                int[] prog = (int[]) CAMPFIRE_COOKING_PROGRESS.get(campfireBE);
                prog[campfireSlot] = 0;
                int[] times = (int[]) CAMPFIRE_COOKING_TIME.get(campfireBE);
                times[campfireSlot] = cookTime;
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.error("[RSI-Vanilla] Campfire placement failed", e);
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

    private void clearCampfireSlot(boolean refundToRS) {
        try {
            @SuppressWarnings("unchecked")
            net.minecraft.core.NonNullList<ItemStack> items =
                    (net.minecraft.core.NonNullList<ItemStack>) CAMPFIRE_ITEMS.get(campfireBE);
            ItemStack slotItem = items.get(campfireSlot);
            if (!slotItem.isEmpty()) {
                if (refundToRS) refundToRSNetwork(slotItem.copy());
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

    // ── polling / collection ──────────────────────────────────────

    @NotNull
    @Override
    protected CraftObservation observeMachineCraft(@NotNull ServerLevel level, @NotNull BlockEntity be) {
        if (kind == MachineKind.FURNACE && BrickFurnaceCompat.isBrickFurnace(be)) {
            BrickFurnaceCompat.Eligibility eligibility = BrickFurnaceCompat.canExecute(be, recipe);
            if (!eligibility.allowed()) return failObservation(eligibility.detail());
            // Very fast configurations can finish between dispatch and the first
            // observation. Once output exists, an empty input and recipe cache are
            // normal completion state, not evidence that the recipe was rejected.
            if (!furnaceBE.getItem(2).isEmpty()) return doneObservation();
            AbstractCookingRecipe actual = BrickFurnaceCompat.resolvedRecipe(be);
            if (actual == null) return failObservation("Brick Furnace did not accept the input recipe");
            if (!actual.getId().equals(recipe.getId())) {
                return failObservation("Brick Furnace resolved a different recipe: " + actual.getId());
            }
            if (phase == CraftPhase.WAITING_FOR_START) return workingObservation();
            if (isMachineCraftFinished(level, be)) return doneObservation();
            return workingObservation();
        }
        return super.observeMachineCraft(level, be);
    }

    @Override
    protected CraftObservation observeMissingMachineCraft(ServerLevel level, BlockPos pos) {
        // Stonecutters and smithing tables are intentionally executed virtually and
        // have no BlockEntity. Their result is ready as soon as the pre-reserved
        // materials have been committed and computeResult() succeeds.
        if (kind == MachineKind.VIRTUAL) {
            return craftDone ? doneObservation() : workingObservation();
        }
        return super.observeMissingMachineCraft(level, pos);
    }

    @Override
    protected boolean isMachineCraftFinished(ServerLevel level, BlockEntity be) {
        if (kind == MachineKind.VIRTUAL) return craftDone;
        if (kind == MachineKind.CAMPFIRE) return isCampfireComplete();

        if (furnaceBE == null) return true;

        ItemStack result = furnaceBE.getItem(2);
        // Once AbstractBatchDelegate has observed WORKING, consumed input is
        // sufficient proof of completion even if automation already took output.
        return !result.isEmpty() || furnaceBE.getItem(0).isEmpty();
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
            // When player != null, abort() will refund the ledger — don't double-refund
            // physical items from the machine slots. Only refund to RS when the player
            // is offline (abortSilently path, ledger is NOT refunded).
            boolean refundToRS = player == null;

            ItemStack slot0 = furnaceBE.getItem(0);
            if (!slot0.isEmpty()) {
                furnaceBE.setItem(0, ItemStack.EMPTY);
                if (refundToRS) refundToRSNetwork(slot0);
            }
            ItemStack slot2 = furnaceBE.getItem(2);
            if (!slot2.isEmpty()) {
                furnaceBE.setItem(2, ItemStack.EMPTY);
                if (refundToRS) refundToRSNetwork(slot2);
            }
            // Fuel is outside the ledger, always refund unburned fuel
            refundLeftoverFuel();
            furnaceBE.setChanged();
        }
        if (kind == MachineKind.CAMPFIRE && campfireBE != null) {
            campfireForceLoad(false);
            clearCampfireSlot(player == null);
        }

        // Rollback uncommitted private ledger
        if (ledger != null && !ledger.isCommitted()) {
            ledger.rollback(player);
        }

        pendingResult = ItemStack.EMPTY;
        craftDone = false;
        resetState();
    }

    @Override
    public void onBatchFinished(@NotNull ServerPlayer player) {
        if (kind == MachineKind.FURNACE) {
            refundLeftoverFuel();
        }
        if (kind == MachineKind.CAMPFIRE) {
            campfireForceLoad(false);
        }
        pendingResult = ItemStack.EMPTY;
        craftDone = false;
        resetState();
    }

    @Override
    public BlockPos getMachinePos() {
        return myPos;
    }

    @Nullable
    @Override
    public ExpectedProduction getExpectedProduction() {
        if (kind != MachineKind.FURNACE) return null;
        ItemStack result = computeResult();
        return result.isEmpty() ? null : new ExpectedProduction(result, result.getCount());
    }

    @Nullable
    @Override
    public ItemStack getExpectedOutput() {
        return kind == MachineKind.CAMPFIRE ? computeResult() : null;
    }

    @Nullable
    @Override
    public net.minecraft.world.phys.AABB getOutputCaptureRegion() {
        return kind == MachineKind.CAMPFIRE && myPos != null
                ? new net.minecraft.world.phys.AABB(myPos).inflate(2.0) : null;
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
