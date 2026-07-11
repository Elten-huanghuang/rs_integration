package com.huanghuang.rsintegration.mods.goety;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.batch.AbstractBatchDelegate;

import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.RecipeIndex;
import com.huanghuang.rsintegration.reflection.probes.GoetyReflection;
import com.huanghuang.rsintegration.util.ModIds;
import com.huanghuang.rsintegration.util.Reflect;
import com.refinedmods.refinedstorage.api.network.INetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.Level;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemHandlerHelper;

import javax.annotation.Nullable;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Batch delegate for Goety Dark Altar and Necro Brazier rituals. */
public final class GoetyBatchDelegate extends AbstractBatchDelegate {

    // ── Instance state ───────────────────────────────────────────
    private ServerPlayer player;
    private ResourceKey<Level> myDim;
    private BlockPos myPos;
    private Object altar;                 // DarkAltarBlockEntity
    private Object ritualRecipe;          // RitualRecipe
    private List<Object> filledPedestals;
    private int soulCost;
    private boolean ritualEverSeenActive;
    private ItemStack activationExtractedFromPlayer;

    // Brazier-mode state
    private boolean isBrazier;
    private Object brazier;               // NecroBrazierBlockEntity
    private Object brazierRecipeObj;      // BrazierRecipe
    private int brazierSoulCost;
    private boolean brazierCraftStarted;

    // ── IBatchDelegate impl ───────────────────────────────────────

    @Override
    public boolean validateAndInit(ServerPlayer player, ResourceLocation recipeId,
                                   @Nullable ResourceLocation dim, BlockPos pos) {

        RSIntegrationMod.LOGGER.debug("[RSI-Batch-Goety] validateAndInit [1/9] entry: recipe={} dim={} pos={}",
                recipeId, dim, pos);

        ServerLevel level = CraftPacketUtils.resolveLevel(player.server, dim, player);
        if (level == null) {
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-Goety] validateAndInit [FAIL-1] dim resolution failed for dim={}", dim);
            player.sendSystemMessage(Component.translatable("rsi.generic.error.dim_not_found"));
            return false;
        }
        this.myDim = level.dimension();
        this.myPos = pos;
        this.player = player;

        if (!level.isLoaded(pos)) {
            RSIntegrationMod.LOGGER.debug("[RSI-Batch-Goety] validateAndInit [2/9] chunk unloaded at {} — force-loading", pos);
            level.getChunk(pos);
        }
        RSIntegrationMod.LOGGER.debug("[RSI-Batch-Goety] validateAndInit [2/9] chunk ready, getting BE at {} dim={}", pos, myDim);

        BlockEntity be = level.getBlockEntity(pos);
        if (be == null) {
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-Goety] validateAndInit [FAIL-3] no BE at pos={} dim={}", pos, myDim);
            player.sendSystemMessage(Component.translatable("rsi.goety.error.altar_not_found"));
            return false;
        }
        RSIntegrationMod.LOGGER.debug("[RSI-Batch-Goety] validateAndInit [3/9] BE found: class={} at {}",
                be.getClass().getSimpleName(), pos);

        // ── Brazier path ───────────────────────────────────
        if (GoetyReflection.necroBrazierBEClass != null && GoetyReflection.necroBrazierBEClass.isInstance(be)) {
            RSIntegrationMod.LOGGER.debug("[RSI-Batch-Goety] validateAndInit [BRANCH] Brazier path: recipe={}", recipeId);
            boolean ok = validateAndInitBrazier(be, recipeId, level);
            if (!ok) {
                isBrazier = false;
                brazier = null;
                brazierRecipeObj = null;
            }
            return ok;
        }

        // ── Dark Altar path (original) ─────────────────────
        if (GoetyReflection.darkAltarBEClass == null || !GoetyReflection.darkAltarBEClass.isInstance(be)) {
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-Goety] validateAndInit [FAIL-4] BE is not DarkAltar (got {} class={}) at {}",
                    be.getClass().getSimpleName(), be.getClass().getName(), pos);
            player.sendSystemMessage(Component.translatable("rsi.goety.error.altar_not_found"));
            return false;
        }
        this.altar = be;
        RSIntegrationMod.LOGGER.debug("[RSI-Batch-Goety] validateAndInit [4/9] Dark Altar confirmed");

        Recipe<?> foundRecipe = level.getRecipeManager().byKey(recipeId).orElse(null);
        if (GoetyReflection.ritualRecipeClass == null || !GoetyReflection.ritualRecipeClass.isInstance(foundRecipe)) {
            if (foundRecipe == null) {
                RSIntegrationMod.LOGGER.warn("[RSI-Batch-Goety] validateAndInit [FAIL-5] recipe not found: {}", recipeId);
                player.sendSystemMessage(Component.translatable("rsi.generic.error.recipe_not_found", recipeId.toString()));
            } else {
                RSIntegrationMod.LOGGER.debug("[RSI-Batch-Goety] validateAndInit [FAIL-5] Dark Altar: recipe {} is not a RitualRecipe (got {}) — trying next machine",
                        recipeId, foundRecipe.getClass().getSimpleName());
            }
            return false;
        }
        this.ritualRecipe = foundRecipe;
        this.ritualEverSeenActive = false;
        RSIntegrationMod.LOGGER.debug("[RSI-Batch-Goety] validateAndInit [5/9] recipe verified as RitualRecipe");

        this.soulCost = Reflect.<Integer>invoke(ritualRecipe, GoetyReflection.M_GET_SOUL_COST).orElse(0);
        RSIntegrationMod.LOGGER.debug("[RSI-Batch-Goety] validateAndInit [6/9] soulCost={}", soulCost);

        Object ritualObj = Reflect.invoke(ritualRecipe, GoetyReflection.M_GET_RITUAL).orElse(null);
        if (ritualObj == null) {
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-Goety] validateAndInit [WARN-6] ritual is null for recipe {} — deferring to tryStartSingleCraft check", recipeId);
            // Old code tolerated null here; tryStartSingleCraft has the real null guard.
            // Skip ritual-type checks and pedestal scan — nothing to derive from.
            // Still do the altar-idle check (independent of ritualObj).
        } else {
            if (GoetyReflection.convertRitualClass != null && GoetyReflection.convertRitualClass.isInstance(ritualObj)) {
                RSIntegrationMod.LOGGER.debug("[RSI-Batch-Goety] validateAndInit [FAIL-7] convert ritual blocked: recipe={}", recipeId);
                player.sendSystemMessage(Component.translatable("rsi.goety.error.unsupported_ritual_type", "convert"));
                return false;
            }
            if (GoetyReflection.teleportRitualClass != null && GoetyReflection.teleportRitualClass.isInstance(ritualObj)) {
                RSIntegrationMod.LOGGER.debug("[RSI-Batch-Goety] validateAndInit [FAIL-7] teleport ritual blocked: recipe={}", recipeId);
                player.sendSystemMessage(Component.translatable("rsi.goety.error.unsupported_ritual_type", "teleport"));
                return false;
            }
            RSIntegrationMod.LOGGER.debug("[RSI-Batch-Goety] validateAndInit [7/9] ritual type OK: {}", ritualObj.getClass().getSimpleName());
        }

        Object currentRitual = Reflect.getField(altar, GoetyReflection.F_CURRENT_RITUAL_RECIPE).orElse(null);
        if (currentRitual != null) {
            RSIntegrationMod.LOGGER.debug("[RSI-Batch-Goety] validateAndInit [FAIL-8] altar busy: currentRitualRecipe={}",
                    currentRitual.getClass().getSimpleName());
            player.sendSystemMessage(Component.translatable("rsi.goety.warn.altar_busy"));
            return false;
        }
        RSIntegrationMod.LOGGER.debug("[RSI-Batch-Goety] validateAndInit [8/9] altar idle confirmed");

        // Verify all pedestals are empty.
        if (ritualObj != null) {
            try {
                @SuppressWarnings("unchecked")
                List<Object> allPedestals = (List<Object>)
                        ritualObj.getClass().getMethod(GoetyReflection.M_GET_PEDESTALS, Level.class, BlockPos.class)
                                .invoke(ritualObj, level, myPos);
                RSIntegrationMod.LOGGER.debug("[RSI-Batch-Goety] validateAndInit [9/9] pedestal scan: {} pedestals found",
                        allPedestals != null ? allPedestals.size() : 0);
                if (allPedestals != null) {
                    for (Object ped : allPedestals) {
                        ItemStack pedItem = readPedestalItem(ped);
                        if (!pedItem.isEmpty()) {
                            RSIntegrationMod.LOGGER.debug("[RSI-Batch-Goety] validateAndInit [FAIL-9] pedestal occupied with {} x{}",
                                    pedItem.getDisplayName().getString(), pedItem.getCount());
                            player.sendSystemMessage(Component.translatable("rsi.goety.warn.altar_busy"));
                            return false;
                        }
                    }
                }
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.warn("[RSI-Batch-Goety] validateAndInit [9/9] pedestal scan failed — proceeding without pedestal check", e);
            }
        } else {
            RSIntegrationMod.LOGGER.debug("[RSI-Batch-Goety] validateAndInit [9/9] pedestal scan skipped (ritual is null)");
        }

        RSIntegrationMod.LOGGER.debug("[RSI-Batch-Goety] validateAndInit [OK] all checks passed: recipe={} altar at {}", recipeId, pos);
        return true;
    }

    private boolean validateAndInitBrazier(Object be, ResourceLocation recipeId, ServerLevel level) {
        this.isBrazier = true;
        this.brazier = be;
        this.altar = null;
        this.ritualRecipe = null;

        Recipe<?> foundRecipe = level.getRecipeManager().byKey(recipeId).orElse(null);
        if (GoetyReflection.brazierRecipeClass == null || !GoetyReflection.brazierRecipeClass.isInstance(foundRecipe)) {
            if (foundRecipe == null)
                player.sendSystemMessage(Component.translatable("rsi.generic.error.recipe_not_found", recipeId.toString()));
            else {
                RSIntegrationMod.LOGGER.debug("[RSI-Batch-Goety] Brazier: recipe {} is not a BrazierRecipe (got {}) — trying next machine",
                        recipeId, foundRecipe.getClass().getSimpleName());
            }
            return false;
        }
        this.brazierRecipeObj = foundRecipe;
        this.brazierSoulCost = Reflect.<Integer>invoke(brazierRecipeObj, GoetyReflection.M_GET_SOUL_COST).orElse(0);

        // Idle check: currentTime must be 0 and no active recipe
        int ct = Reflect.getIntField(brazier, GoetyReflection.F_CURRENT_TIME).orElse(1);
        Object recipe = Reflect.getField(brazier, GoetyReflection.F_RECIPE).orElse(null);
        if (ct > 0 || recipe != null) {
            player.sendSystemMessage(Component.translatable("rsi.goety.warn.altar_busy"));
            return false;
        }

        // Inventory must be empty
        if (!Reflect.<Boolean>invoke(brazier, GoetyReflection.M_IS_EMPTY).orElse(true)) {
            player.sendSystemMessage(Component.translatable("rsi.goety.warn.altar_busy"));
            return false;
        }

        // Deep Dark biome check
        ResourceKey<Biome> deepDark = ResourceKey.create(Registries.BIOME,
                new ResourceLocation("minecraft", "deep_dark"));
        if (!level.getBiome(myPos).is(deepDark)) {
            player.sendSystemMessage(Component.translatable("rsi.goety.error.brazier_wrong_biome"));
            return false;
        }

        // Soul candlestick check
        List<Object> candlesticks = findNearbyCandlesticks(level, myPos);
        if (candlesticks.isEmpty()) {
            player.sendSystemMessage(Component.translatable("rsi.goety.error.brazier_no_candlesticks"));
            return false;
        }
        int totalSouls = candlesticks.stream()
                .mapToInt(cs -> Reflect.<Integer>invoke(cs, GoetyReflection.M_GET_SOULS).orElse(0))
                .sum();
        if (totalSouls < brazierSoulCost) {
            player.sendSystemMessage(Component.translatable("rsi.goety.error.insufficient_souls",
                    brazierSoulCost, totalSouls));
            return false;
        }

        RSIntegrationMod.LOGGER.debug("[RSI-Batch-Goety] validateAndInit brazier OK: recipe={} soulCost={} candlesticks={} totalSouls={}",
                recipeId, brazierSoulCost, candlesticks.size(), totalSouls);
        return true;
    }

    @Override
    public boolean tryStartSingleCraft(ServerPlayer player) {
        if (!initCraftState(player)) return false;
        if (isBrazier) return tryStartBrazierCraft();
        return tryStartDarkAltarCraft();
    }

    private boolean initCraftState(ServerPlayer player) {
        this.player = player;
        this.ledger = new ExtractionLedger();
        this.network = CraftPacketUtils.resolveNetworkForCraft(player, myDim, myPos);
        this.activationExtractedFromPlayer = null;

        ServerLevel machineLevel = resolveMachineLevel(player);
        if (myPos != null && machineLevel != null && machineLevel.isLoaded(myPos)) {
            BlockEntity current = machineLevel.getBlockEntity(myPos);
            if (current == null || current.isRemoved()) {
                player.sendSystemMessage(Component.translatable("rsi.error.machine_missing"));
                if (ledger != null && ledger.isCommitted()) {
                    ledger.refundCommitted(network, player);
                }
                return false;
            }
        }
        return true;
    }

    private boolean tryStartDarkAltarCraft() {
        if (Reflect.getField(altar, GoetyReflection.F_CURRENT_RITUAL_RECIPE).orElse(null) != null) return false;

        this.filledPedestals = new ArrayList<>();

        if (!validateSoulsAvailable(soulCost)) return false;

        Object ritual = Reflect.invoke(ritualRecipe, GoetyReflection.M_GET_RITUAL).orElse(null);
        if (ritual == null) {
            RSIntegrationMod.LOGGER.error("[RSI-Batch-Goety] Ritual is null for recipe {}",
                    ((Recipe<?>) ritualRecipe).getId());
            return false;
        }

        boolean isSummon = ritual.getClass().getName()
                .endsWith(".SummonRitual");

        if (!checkRitualPrerequisites(ritualRecipe, ritual)) return false;

        List<IngredientSpec> specList = collectIngredients(ritualRecipe);
        Ingredient activationIng = Reflect.<Ingredient>invoke(ritualRecipe, GoetyReflection.M_GET_ACTIVATION_ITEM).orElse(Ingredient.EMPTY);
        if (activationIng != null && !activationIng.isEmpty()) {
            ItemStack[] actItems = activationIng.getItems();
            if (actItems.length > 0) {
                Item actBase = actItems[0].getItem();
                for (int i = 0; i < specList.size(); i++) {
                    IngredientSpec spec = specList.get(i);
                    ItemStack[] specItems = spec.ingredient().getItems();
                    if (specItems.length > 0 && specItems[0].getItem() == actBase) {
                        if (spec.count() > 1) {
                            specList.set(i, new IngredientSpec(spec.ingredient(), spec.count() - 1));
                        } else {
                            specList.remove(i);
                        }
                        break;
                    }
                }
            }
        }

        // Summon rituals: fill pedestals from RS, give activation item to player.
        // The player must manually right-click the altar to start the ritual.
        if (isSummon) {
            return prepareSummonRitual(activationIng, specList);
        }

        ItemStack activationItemStack = extractActivation(activationIng);
        if (activationItemStack == null) return false;

        if (specList.isEmpty()) {
            return startRitualDirectly(ritual, activationItemStack);
        }
        return startRitualWithPedestals(ritual, activationItemStack, specList);
    }

    /** Fill pedestals from RS and hand the activation item to the player.
     *  Summon rituals produce entities, so we let the player trigger the ritual manually. */
    private boolean prepareSummonRitual(Ingredient activationIng, List<IngredientSpec> specList) {
        // 1. Reserve activation item in ledger (template — not yet extracted from RS)
        ItemStack activationItem = extractActivation(activationIng);
        if (activationItem == null) {
            ledger.rollback(player);
            return false;
        }

        // 2. Reserve pedestal items in ledger (templates — no physical placement yet).
        //    Commit BEFORE placing so a commit failure leaves the machine untouched.
        List<Object> availablePedestals = null;
        List<ItemStack> reservedStacks = new ArrayList<>();
        if (!specList.isEmpty()) {
            Object ritual = Reflect.invoke(ritualRecipe, GoetyReflection.M_GET_RITUAL).orElse(null);
            availablePedestals = findAvailablePedestals(resolveMachineLevel(player), ritual);
            if (availablePedestals.size() < specList.size()) {
                player.sendSystemMessage(Component.translatable(
                        "rsi.wr.error.pedestals_insufficient", specList.size(), availablePedestals.size()));
                ledger.rollback(player);
                return false;
            }
            for (int i = 0; i < specList.size(); i++) {
                IngredientSpec spec = specList.get(i);
                if (spec.isEmpty()) continue;
                ItemStack stack = CraftPacketUtils.ensureMaterialAvailable(
                        player, myDim, myPos, spec.ingredient(), spec.count(), ledger);
                if (stack.isEmpty()) {
                    player.sendSystemMessage(Component.translatable("rsi.goety.error.one_click_failed"));
                    ledger.rollback(player);
                    return false;
                }
                reservedStacks.add(stack);
            }
        }

        // 3. Commit all extractions together — items are now physically extracted
        if (!ledger.commit(network, player)) {
            RSIntegrationMod.LOGGER.error("[RSI-Batch-Goety] Ledger commit failed for summon prep");
            refundActivationToPlayer();
            return false;
        }

        // 4. Place REAL items (post-commit) on pedestals
        if (availablePedestals != null) {
            for (int i = 0; i < reservedStacks.size(); i++) {
                Object ped = availablePedestals.get(i);
                writePedestalItem(ped, reservedStacks.get(i));
                filledPedestals.add(ped);
            }
        }

        // 5. Give activation item to player
        if (!activationItem.isEmpty()) {
            ItemHandlerHelper.giveItemToPlayer(player, activationItem);
        }

        // 6. Notify
        Component itemName = CraftPacketUtils.describeIngredient(activationIng);
        player.sendSystemMessage(Component.translatable("rsi.goety.summon_prepared", itemName));
        return true;
    }

    /** Extract the activation item, trying RS first then player inventory. Returns null on failure. */
    private ItemStack extractActivation(Ingredient activationIng) {
        if (activationIng == null || activationIng.isEmpty()) return ItemStack.EMPTY;
        ItemStack stack = CraftPacketUtils.ensureMaterialAvailable(
                player, myDim, myPos, activationIng, 1, ledger);
        if (stack.isEmpty()) {
            RSIntegrationMod.LOGGER.debug("[RSI-Batch-Goety] Activation item not in RS, extracting from player");
            stack = extractActivationFromPlayer(activationIng);
            if (stack.isEmpty()) {
                player.sendSystemMessage(Component.translatable("rsi.goety.error.missing_activation",
                        CraftPacketUtils.describeIngredient(activationIng)));
                return null;
            }
            this.activationExtractedFromPlayer = stack.copy();
        }
        return stack;
    }

    private boolean startRitualDirectly(Object ritual, ItemStack activationItemStack) {
        try {
            if (!ledger.commit(network, player)) {
                RSIntegrationMod.LOGGER.error("[RSI-Batch-Goety] Ledger commit failed (no ingredients)");
                refundActivationToPlayer();
                return false;
            }
            if (!checkRitualIsValid(ritual, activationItemStack)) {
                player.sendSystemMessage(Component.translatable("rsi.goety.error.ritual_invalid"));
                refundActivationToPlayer();
                ledger.refundCommitted(network, player);
                return false;
            }
            if (!startAltarRitual(altar, player, activationItemStack, ritualRecipe)) {
                player.sendSystemMessage(Component.translatable("rsi.goety.error.ritual_start_failed"));
                refundActivationToPlayer();
                ledger.refundCommitted(network, player);
                return false;
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.error("[RSI-Batch-Goety] Failed to start ritual", e);
            refundActivationToPlayer();
            ledger.refundCommitted(network, player);
            return false;
        }
        return true;
    }

    private boolean startRitualWithPedestals(Object ritual, ItemStack activationItemStack,
                                             List<IngredientSpec> specList) {
        List<Object> availablePedestals = findAvailablePedestals(resolveMachineLevel(player), ritual);
        if (availablePedestals.size() < specList.size()) {
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-Goety] Insufficient pedestals: need {}, found {}",
                    specList.size(), availablePedestals.size());
            player.sendSystemMessage(Component.translatable(
                    "rsi.wr.error.pedestals_insufficient", specList.size(), availablePedestals.size()));
            refundActivationToPlayer();
            ledger.rollback(player);
            return false;
        }

        List<ItemStack> templates = new ArrayList<>();
        for (int i = 0; i < specList.size(); i++) {
            IngredientSpec spec = specList.get(i);
            if (spec.isEmpty()) {
                templates.add(ItemStack.EMPTY);
                continue;
            }
            ItemStack stack = CraftPacketUtils.ensureMaterialAvailable(player, myDim, myPos, spec.ingredient(), spec.count(), ledger);
            if (stack.isEmpty()) {
                refundActivationToPlayer();
                ledger.rollback(player);
                return false;
            }
            templates.add(stack);
        }

        if (!ledger.commit(network, player)) {
            RSIntegrationMod.LOGGER.error("[RSI-Batch-Goety] Ledger commit failed before ritual start");
            refundActivationToPlayer();
            return false;
        }

        try {
            for (int i = 0; i < templates.size(); i++) {
                ItemStack stack = templates.get(i);
                if (stack.isEmpty()) continue;
                Object ped = availablePedestals.get(i);
                writePedestalItem(ped, stack);
                filledPedestals.add(ped);
            }

            if (!checkRitualIsValid(ritual, activationItemStack)) {
                player.sendSystemMessage(Component.translatable("rsi.goety.error.ritual_invalid"));
                refundActivationToPlayer();
                recoverFromPedestals();
                return false;
            }
            if (!startAltarRitual(altar, player, activationItemStack, ritualRecipe)) {
                player.sendSystemMessage(Component.translatable("rsi.goety.error.ritual_start_failed"));
                refundActivationToPlayer();
                recoverFromPedestals();
                return false;
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.error("[RSI-Batch-Goety] Placement/start failed:", e);
            refundActivationToPlayer();
            recoverFromPedestals();
            return false;
        }

        return true;
    }

    private boolean tryStartBrazierCraft() {
        ServerLevel level = resolveMachineLevel(player);
        if (!level.isLoaded(myPos)) {
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-Goety] Chunk unloaded at {} — aborting brazier craft", myPos);
            return false;
        }

        // Re-verify idle
        int ct = Reflect.getIntField(brazier, GoetyReflection.F_CURRENT_TIME).orElse(1);
        Object recipe = Reflect.getField(brazier, GoetyReflection.F_RECIPE).orElse(null);
        boolean empty = Reflect.<Boolean>invoke(brazier, GoetyReflection.M_IS_EMPTY).orElse(false);
        if (ct > 0 || recipe != null || !empty) {
            player.sendSystemMessage(Component.translatable("rsi.goety.warn.altar_busy"));
            return false;
        }

        List<Ingredient> ingredients = ((Recipe<?>) brazierRecipeObj).getIngredients();
        Object container = Reflect.invoke(brazier, GoetyReflection.M_GET_CONTAINER).orElse(null);
        int containerSize = container != null
                ? Reflect.<Integer>invoke(container, GoetyReflection.M_GET_CONTAINER_SIZE).orElse(3)
                : 3;
        NonNullList<ItemStack> toPlace = NonNullList.withSize(containerSize, ItemStack.EMPTY);

        int slot = 0;
        for (Ingredient ing : ingredients) {
            if (ing.isEmpty()) continue;
            ItemStack taken = CraftPacketUtils.ensureMaterialAvailable(player, myDim, myPos, ing, 1, ledger);
            if (taken.isEmpty()) {
                ledger.rollback(player);
                return false;
            }
            toPlace.set(slot++, taken.copy());
        }

        if (!ledger.commit(network, player)) {
            RSIntegrationMod.LOGGER.error("[RSI-Batch-Goety] Ledger commit failed for brazier craft");
            return false;
        }

        // Physical placement AFTER commit — refund if setup fails
        Reflect.invoke(brazier, GoetyReflection.M_SET_ITEMS, toPlace);
        // Set recipe directly (bypass updateRecipe) — yzzzfix overrides
        // updateRecipe to call stopBrazier(false) when recipe is null,
        // which ejects items prematurely.
        Reflect.setField(brazier, GoetyReflection.F_RECIPE_ID, ((Recipe<?>) brazierRecipeObj).getId());
        Reflect.setField(brazier, GoetyReflection.F_RECIPE, brazierRecipeObj);
        if (brazier instanceof BlockEntity be) {
            be.setChanged();
            Level lvl = be.getLevel();
            if (lvl != null && !lvl.isClientSide()) {
                lvl.sendBlockUpdated(myPos, be.getBlockState(), be.getBlockState(), 3);
            }
        }

        Object bzrRecipe = Reflect.getField(brazier, GoetyReflection.F_RECIPE).orElse(null);
        if (bzrRecipe == null) {
            RSIntegrationMod.LOGGER.error("[RSI-Batch-Goety] Brazier recipe not set: {}",
                    ((Recipe<?>) brazierRecipeObj).getId());
            Reflect.invoke(brazier, GoetyReflection.M_SET_ITEMS,
                    NonNullList.withSize(containerSize, ItemStack.EMPTY));
            Reflect.setField(brazier, GoetyReflection.F_RECIPE_ID, null);
            ledger.refundCommitted(network, player);
            return false;
        }

        // Start the brazier — activate() validates soul energy and begins processing
        boolean activated = Reflect.<Boolean>invoke(brazier, GoetyReflection.M_ACTIVATE, level).orElse(false);
        if (!activated) {
            RSIntegrationMod.LOGGER.error("[RSI-Batch-Goety] Brazier activate returned false for recipe: {}",
                    ((Recipe<?>) brazierRecipeObj).getId());
            Reflect.invoke(brazier, GoetyReflection.M_SET_ITEMS,
                    NonNullList.withSize(containerSize, ItemStack.EMPTY));
            Reflect.setField(brazier, GoetyReflection.F_RECIPE, null);
            Reflect.setField(brazier, GoetyReflection.F_RECIPE_ID, null);
            Reflect.setField(brazier, GoetyReflection.F_CURRENT_TIME, 0);
            ledger.refundCommitted(network, player);
            return false;
        }

        this.brazierCraftStarted = true;
        RSIntegrationMod.LOGGER.debug("[RSI-Batch-Goety] Brazier craft started: recipe={} soulCost={}",
                ((Recipe<?>) brazierRecipeObj).getId(), brazierSoulCost);
        return true;
    }

    @Override
    @Nullable
    public List<IngredientSpec> getRequiredMaterials() {
        if (isBrazier) {
            if (brazierRecipeObj == null) return null;
            List<IngredientSpec> specs = new ArrayList<>();
            for (Ingredient ing : ((Recipe<?>) brazierRecipeObj).getIngredients()) {
                if (!ing.isEmpty()) specs.add(new IngredientSpec(ing, 1));
            }
            return specs.isEmpty() ? null : specs;
        }
        if (ritualRecipe == null) return null;
        List<IngredientSpec> specs = collectIngredients(ritualRecipe);
        return specs.isEmpty() ? null : specs;
    }

    @Override
    public boolean tryStartWithMaterials(ServerPlayer player, List<ItemStack> materials,
                                         ExtractionLedger sharedLedger) {
        this.player = player;
        this.sharedLedger = sharedLedger;
        this.usingSharedLedger = true;
        this.network = CraftPacketUtils.resolveNetworkForCraft(player, myDim, myPos);
        this.activationExtractedFromPlayer = null;

        // Verify the cached BlockEntity is still valid
        ServerLevel machineLevel = resolveMachineLevel(player);
        if (myPos != null && machineLevel != null && machineLevel.isLoaded(myPos)) {
            BlockEntity current = machineLevel.getBlockEntity(myPos);
            if (current == null || current.isRemoved()) {
                player.sendSystemMessage(Component.translatable("rsi.error.machine_missing"));
                // abort() in the chain framework refunds the shared ledger
                return false;
            }
        }

        if (isBrazier) return tryStartBrazierWithMaterials(materials);

        // ── Dark Altar path ────────────────────────────────
        if (Reflect.getField(altar, GoetyReflection.F_CURRENT_RITUAL_RECIPE).orElse(null) != null) {
            player.sendSystemMessage(Component.translatable("rsi.goety.warn.altar_busy"));
            return false;
        }

        this.filledPedestals = new ArrayList<>();

        if (!validateSoulsAvailable(soulCost)) return false;

        Object ritual = Reflect.invoke(ritualRecipe, GoetyReflection.M_GET_RITUAL).orElse(null);
        if (ritual == null) {
            RSIntegrationMod.LOGGER.error("[RSI-Batch-Goety] tryStartWithMaterials: Ritual is null for recipe {}",
                    ((Recipe<?>) ritualRecipe).getId());
            player.sendSystemMessage(Component.translatable("rsi.goety.error.one_click_failed"));
            return false;
        }

        if (!checkRitualPrerequisites(ritualRecipe, ritual)) return false;

        ItemStack activationItem = ItemStack.EMPTY;
        Ingredient activationIng = Reflect.<Ingredient>invoke(ritualRecipe, GoetyReflection.M_GET_ACTIVATION_ITEM).orElse(Ingredient.EMPTY);
        if (activationIng != null && !activationIng.isEmpty()) {
            activationItem = removeActivationFromMaterials(materials, activationIng);
            if (activationItem.isEmpty()) {
                RSIntegrationMod.LOGGER.debug("[RSI-Batch-Goety] Activation item not in materials, extracting from player");
                activationItem = extractActivationFromPlayer(activationIng);
                if (activationItem.isEmpty()) {
                    player.sendSystemMessage(Component.translatable("rsi.goety.error.missing_activation",
                            CraftPacketUtils.describeIngredient(activationIng)));
                    return false;
                }
                this.activationExtractedFromPlayer = activationItem.copy();
            }
        }

        if (materials.isEmpty()) {
            try {
                if (!checkRitualIsValid(ritual, activationItem)) {
                    player.sendSystemMessage(Component.translatable("rsi.goety.error.ritual_invalid"));
                    refundActivationToPlayer();
                    return false;
                }
                if (startAltarRitual(altar, player, activationItem, ritualRecipe)) {
                    return true;
                }
                player.sendSystemMessage(Component.translatable("rsi.goety.error.one_click_failed"));
                refundActivationToPlayer();
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.error("[RSI-Batch-Goety] tryStartWithMaterials: Failed to start ritual (no materials)", e);
                player.sendSystemMessage(Component.translatable("rsi.goety.error.one_click_failed"));
                refundActivationToPlayer();
                return false;
            }
            return true;
        }

        List<Object> availablePedestals = findAvailablePedestals(resolveMachineLevel(player), ritual);
        if (availablePedestals.size() < materials.size()) {
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-Goety] tryStartWithMaterials: insufficient pedestals (need {}, found {})",
                    materials.size(), availablePedestals.size());
            player.sendSystemMessage(Component.translatable("rsi.goety.error.one_click_failed"));
            refundActivationToPlayer();
            return false;
        }

        try {
            for (int i = 0; i < materials.size(); i++) {
                ItemStack stack = materials.get(i);
                if (stack.isEmpty()) continue;
                Object ped = availablePedestals.get(i);
                writePedestalItem(ped, stack);
                filledPedestals.add(ped);
            }

            if (!checkRitualIsValid(ritual, activationItem)) {
                player.sendSystemMessage(Component.translatable("rsi.goety.error.ritual_invalid"));
                refundActivationToPlayer();
                recoverFromPedestals();
                return false;
            }
            if (!startAltarRitual(altar, player, activationItem, ritualRecipe)) {
                player.sendSystemMessage(Component.translatable("rsi.goety.error.one_click_failed"));
                refundActivationToPlayer();
                recoverFromPedestals();
                return false;
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.error("[RSI-Batch-Goety] Material placement/start failed:", e);
            player.sendSystemMessage(Component.translatable("rsi.goety.error.one_click_failed"));
            refundActivationToPlayer();
            recoverFromPedestals();
            return false;
        }

        return true;
    }

    private boolean tryStartBrazierWithMaterials(List<ItemStack> materials) {
        ServerLevel level = resolveMachineLevel(player);
        if (!level.isLoaded(myPos)) {
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-Goety] Chunk unloaded at {} — aborting brazier materials", myPos);
            return false;
        }

        int ct = Reflect.getIntField(brazier, GoetyReflection.F_CURRENT_TIME).orElse(1);
        Object recipe = Reflect.getField(brazier, GoetyReflection.F_RECIPE).orElse(null);
        boolean empty = Reflect.<Boolean>invoke(brazier, GoetyReflection.M_IS_EMPTY).orElse(false);
        if (ct > 0 || recipe != null || !empty) {
            player.sendSystemMessage(Component.translatable("rsi.goety.warn.altar_busy"));
            return false;
        }

        Object container = Reflect.invoke(brazier, GoetyReflection.M_GET_CONTAINER).orElse(null);
        int containerSize = container != null
                ? Reflect.<Integer>invoke(container, GoetyReflection.M_GET_CONTAINER_SIZE).orElse(3)
                : 3;
        NonNullList<ItemStack> toPlace = NonNullList.withSize(containerSize, ItemStack.EMPTY);
        int slot = 0;
        for (ItemStack mat : materials) {
            if (mat.isEmpty()) continue;
            toPlace.set(slot++, mat.copy());
        }

        Reflect.invoke(brazier, GoetyReflection.M_SET_ITEMS, toPlace);
        // Set recipe directly (bypass updateRecipe) — yzzzfix overrides
        // updateRecipe to call stopBrazier(false) when recipe is null,
        // which ejects items prematurely.
        Reflect.setField(brazier, GoetyReflection.F_RECIPE_ID, ((Recipe<?>) brazierRecipeObj).getId());
        Reflect.setField(brazier, GoetyReflection.F_RECIPE, brazierRecipeObj);
        if (brazier instanceof BlockEntity be) {
            be.setChanged();
            Level lvl = be.getLevel();
            if (lvl != null && !lvl.isClientSide()) {
                lvl.sendBlockUpdated(myPos, be.getBlockState(), be.getBlockState(), 3);
            }
        }

        Object bzrRecipe = Reflect.getField(brazier, GoetyReflection.F_RECIPE).orElse(null);
        if (bzrRecipe == null) {
            RSIntegrationMod.LOGGER.error("[RSI-Batch-Goety] Brazier recipe not set in tryStartWithMaterials: {}",
                    ((Recipe<?>) brazierRecipeObj).getId());
            Reflect.invoke(brazier, GoetyReflection.M_SET_ITEMS,
                    NonNullList.withSize(containerSize, ItemStack.EMPTY));
            Reflect.setField(brazier, GoetyReflection.F_RECIPE_ID, null);
            player.sendSystemMessage(Component.translatable("rsi.goety.error.one_click_failed"));
            return false;
        }

        // Start the brazier — activate() validates soul energy and begins processing
        boolean activated = Reflect.<Boolean>invoke(brazier, GoetyReflection.M_ACTIVATE, level).orElse(false);
        if (!activated) {
            RSIntegrationMod.LOGGER.error("[RSI-Batch-Goety] Brazier activate returned false in tryStartWithMaterials: {}",
                    ((Recipe<?>) brazierRecipeObj).getId());
            Reflect.invoke(brazier, GoetyReflection.M_SET_ITEMS,
                    NonNullList.withSize(containerSize, ItemStack.EMPTY));
            Reflect.setField(brazier, GoetyReflection.F_RECIPE, null);
            Reflect.setField(brazier, GoetyReflection.F_RECIPE_ID, null);
            Reflect.setField(brazier, GoetyReflection.F_CURRENT_TIME, 0);
            player.sendSystemMessage(Component.translatable("rsi.goety.error.one_click_failed"));
            return false;
        }

        this.brazierCraftStarted = true;
        return true;
    }

    @Override
    protected boolean isMachineCraftFinished(ServerLevel level, BlockEntity be) {
        if (isBrazier) return isBrazierCraftComplete(level);

        // Use the public field directly — getCurrentRitualRecipe() has a side
        // effect: it re-populates currentRitualRecipe from currentRitualRecipeId
        // via recipe-manager lookup, which defeats null-after-completion detection.
        if (Reflect.getField(altar, GoetyReflection.F_CURRENT_RITUAL_RECIPE).orElse(null) != null) {
            ritualEverSeenActive = true;
            return false;
        }
        if (!ritualEverSeenActive && player != null) {
            ItemStack expected = RecipeIndex.tryGetResultItem((Recipe<?>) ritualRecipe, level.registryAccess());
            if (!expected.isEmpty()) {
                BlockPos pos = be.getBlockPos();
                if (pos != null && level.isLoaded(pos)) {
                    var entities = level.getEntitiesOfClass(
                            net.minecraft.world.entity.item.ItemEntity.class,
                            new net.minecraft.world.phys.AABB(pos).inflate(3),
                            e -> ItemStack.isSameItemSameTags(e.getItem(), expected));
                    if (!entities.isEmpty()) return true;
                }
                var inv = player.getInventory();
                for (ItemStack stack : inv.items) {
                    if (ItemStack.isSameItemSameTags(stack, expected)) return true;
                }
                for (ItemStack stack : inv.offhand) {
                    if (ItemStack.isSameItemSameTags(stack, expected)) return true;
                }
            }
        }
        return ritualEverSeenActive;
    }

    private boolean isBrazierCraftComplete(ServerLevel level) {
        if (!brazierCraftStarted) return false;
        // Recipe was non-null (processing), now null (finished — success or failure)
        Object recipe = Reflect.getField(brazier, GoetyReflection.F_RECIPE).orElse(null);
        int ct = Reflect.getIntField(brazier, GoetyReflection.F_CURRENT_TIME).orElse(1);
        boolean empty = Reflect.<Boolean>invoke(brazier, GoetyReflection.M_IS_EMPTY).orElse(true);
        if (recipe == null && ct == 0 && empty) {
            return true;
        }
        return false;
    }

    @Override
    public ItemStack collectResult(ServerPlayer player) {
        if (isBrazier) return collectBrazierResult(player);

        ItemStack expected = RecipeIndex.tryGetResultItem((Recipe<?>) ritualRecipe, player.serverLevel().registryAccess());
        if (expected.isEmpty()) return ItemStack.EMPTY;

        // 1. Check altar's own inventory (slot 0 is where Ritual.finish() places the result)
        try {
            var handlerOpt = Reflect.<Object>getField(altar, "itemStackHandler");
            if (handlerOpt.isPresent()) {
                Object lazyObj = handlerOpt.get();
                LazyOptional<?> lazy = (LazyOptional<?>) lazyObj;
                var resolved = lazy.resolve();
                if (resolved.isPresent()) {
                    var handler = (IItemHandler) resolved.get();
                    ItemStack inAltar = handler.getStackInSlot(0);
                    if (!inAltar.isEmpty()) {
                        boolean matches = ItemStack.isSameItemSameTags(inAltar, expected)
                                || ItemStack.isSameItem(inAltar, expected);
                        if (matches) {
                            ItemStack collected = inAltar.copy();
                            if (collected.getCount() > expected.getCount()) {
                                collected.setCount(expected.getCount());
                            }
                            handler.extractItem(0, collected.getCount(), false);
                            return collected;
                        }
                    }
                }
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-Batch-Goety] Altar inventory read failed", e);
        }

        // 2. Scan for ItemEntity near the altar
        ServerLevel machineLevel = resolveMachineLevel(player);
        if (myPos != null && machineLevel != null && machineLevel.isLoaded(myPos)) {
            var entities = machineLevel.getEntitiesOfClass(
                    net.minecraft.world.entity.item.ItemEntity.class,
                    new net.minecraft.world.phys.AABB(myPos).inflate(3),
                    e -> ItemStack.isSameItemSameTags(e.getItem(), expected)
                            || ItemStack.isSameItem(e.getItem(), expected));
            for (var entity : entities) {
                ItemStack collected = entity.getItem().copy();
                if (collected.getCount() > expected.getCount()) {
                    collected.setCount(expected.getCount());
                }
                entity.getItem().shrink(collected.getCount());
                entity.setItem(entity.getItem().copy());
                if (entity.getItem().isEmpty()) entity.discard();
                return collected;
            }
        }

        // 3. Fallback: check player inventory
        var inv = player.getInventory();
        List<ItemStack> all = new ArrayList<>();
        all.addAll(inv.items);
        all.addAll(inv.offhand);
        all.addAll(inv.armor);
        try {
            var opt = top.theillusivec4.curios.api.CuriosApi.getCuriosInventory(player).resolve();
            if (opt.isPresent()) {
                for (var handler : opt.get().getCurios().values()) {
                    var stacks = handler.getStacks();
                    for (int s = 0; s < stacks.getSlots(); s++) {
                        all.add(stacks.getStackInSlot(s));
                    }
                }
            }
        } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI] Reflection probe failed", e); }

        for (ItemStack stack : all) {
            if (ItemStack.isSameItemSameTags(stack, expected)) {
                ItemStack collected = stack.split(expected.getCount());
                return collected;
            }
        }

        return ItemStack.EMPTY;
    }

    @SuppressWarnings("unchecked")
    private ItemStack collectBrazierResult(ServerPlayer player) {
        ItemStack expected = Reflect.<ItemStack>invoke(brazierRecipeObj, "getResultItem", player.serverLevel().registryAccess())
                .orElse(ItemStack.EMPTY);
        if (expected.isEmpty()) return ItemStack.EMPTY;

        // stopBrazier(true) drops the result at (x, y+1, z)
        // including an offset for the random scatter in dropItemStack
        ServerLevel brazierLevel = resolveMachineLevel(player);
        if (myPos != null && brazierLevel != null && brazierLevel.isLoaded(myPos)) {
            var entities = brazierLevel.getEntitiesOfClass(
                    net.minecraft.world.entity.item.ItemEntity.class,
                    new net.minecraft.world.phys.AABB(myPos).inflate(3),
                    e -> ItemStack.isSameItemSameTags(e.getItem(), expected));
            for (var entity : entities) {
                ItemStack collected = entity.getItem().copy();
                if (collected.getCount() > expected.getCount()) {
                    collected.setCount(expected.getCount());
                }
                entity.getItem().shrink(collected.getCount());
                entity.setItem(entity.getItem().copy());
                if (entity.getItem().isEmpty()) entity.discard();
                RSIntegrationMod.LOGGER.debug("[RSI-Batch-Goety] Collected brazier result: {} x{}",
                        collected.getDisplayName().getString(), collected.getCount());
                return collected;
            }
        }

        // Fallback: check player inventory
        var inv = player.getInventory();
        for (ItemStack stack : inv.items) {
            if (ItemStack.isSameItemSameTags(stack, expected)) {
                return stack.split(expected.getCount());
            }
        }
        for (ItemStack stack : inv.offhand) {
            if (ItemStack.isSameItemSameTags(stack, expected)) {
                return stack.split(expected.getCount());
            }
        }

        RSIntegrationMod.LOGGER.debug("[RSI-Batch-Goety] Brazier result not found: {}", expected.getDisplayName().getString());
        return ItemStack.EMPTY;
    }

    @Override
    protected void clearMachineState(BlockEntity be, ServerPlayer player) {
        if (isBrazier) {
            recoverBrazierItems();
        } else {
            ritualEverSeenActive = false;
            refundActivationToPlayer();
            recoverFromPedestals();
        }
        resetState();
        activationExtractedFromPlayer = null;
    }

    @Override
    public void onBatchFinished(ServerPlayer player) {
        if (isBrazier) {
            brazierCraftStarted = false;
        } else {
            ritualEverSeenActive = false;
            clearFilledPedestals();
        }
        resetState();
        activationExtractedFromPlayer = null;
    }

    @SuppressWarnings("unchecked")
    private void recoverBrazierItems() {
        if (brazier == null) return;
        NonNullList<ItemStack> items = Reflect.<NonNullList<ItemStack>>invoke(brazier, "getItems")
                .orElse(NonNullList.withSize(0, ItemStack.EMPTY));
        boolean hasItems = false;
        for (ItemStack stack : items) {
            if (!stack.isEmpty()) {
                hasItems = true;
                break;
            }
        }
        if (!hasItems) return;

        // When player != null, abort() refunds the ledger — don't double-refund
        // physical items from the brazier to RS. Only refund when player is offline
        // (abortSilently does NOT refund the ledger).
        if (player == null) {
            for (ItemStack stack : items) {
                if (!stack.isEmpty() && network != null) {
                    network.insertItem(stack.copy(), stack.getCount(),
                            com.refinedmods.refinedstorage.api.util.Action.PERFORM);
                }
            }
        }
        Object container = Reflect.invoke(brazier, GoetyReflection.M_GET_CONTAINER).orElse(null);
        int containerSize = container != null
                ? Reflect.<Integer>invoke(container, GoetyReflection.M_GET_CONTAINER_SIZE).orElse(3)
                : 3;
        Reflect.invoke(brazier, GoetyReflection.M_SET_ITEMS,
                NonNullList.withSize(containerSize, ItemStack.EMPTY));
        Reflect.setField(brazier, GoetyReflection.F_RECIPE, null);
        Reflect.setField(brazier, GoetyReflection.F_RECIPE_ID, null);
        Reflect.setField(brazier, GoetyReflection.F_CURRENT_TIME, 0);
        brazierCraftStarted = false;
    }

    @Override
    public BlockPos getMachinePos() {
        return myPos;
    }

    // ── Brazier helpers ─────────────────────────────────────────

    private static final int CANDLESTICK_SEARCH_RANGE = 8;

    private static List<Object> findNearbyCandlesticks(ServerLevel level, BlockPos pos) {
        List<Object> result = new ArrayList<>();

        if (GoetyReflection.soulCandlestickBEClass == null) return result;
        int r = CANDLESTICK_SEARCH_RANGE;
        for (BlockPos cp : BlockPos.betweenClosed(
                pos.offset(-r, -r, -r), pos.offset(r, r, r))) {
            if (!level.isLoaded(cp)) continue;
            BlockEntity be = level.getBlockEntity(cp);
            if (GoetyReflection.soulCandlestickBEClass.isInstance(be)) {
                int souls = Reflect.<Integer>invoke(be, GoetyReflection.M_GET_SOULS).orElse(0);
                if (souls > 0) {
                    result.add(be);
                }
            }
        }
        return result;
    }

    // ── Soul energy ──────────────────────────────────────────────

    private boolean validateSoulsAvailable(int cost) {
        if (cost <= 0) return true;
        try {
            var cageOpt = Reflect.getField(altar, GoetyReflection.F_CURSED_CAGE_TILE);
            if (cageOpt.isEmpty() || cageOpt.get() == null) {
                RSIntegrationMod.LOGGER.debug("[RSI-Batch-Goety] No cursedCageTile — assuming link gem/Arca, skipping soul check");
                return true;
            }
            Object cage = cageOpt.get();
            if (GoetyReflection.cursedCageBEClass == null || !GoetyReflection.cursedCageBEClass.isInstance(cage)) return true;

            int available = (int) GoetyReflection.cursedCageBEClass.getMethod(GoetyReflection.M_GET_SOULS).invoke(cage);
            if (available < cost) {
                player.sendSystemMessage(Component.translatable(
                        "rsi.goety.error.insufficient_souls", cost, available));
                return false;
            }
            return true;
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-Batch-Goety] Soul validation failed, skipping", e);
            return true;
        }
    }

    // ── Recipe ingredient collection ─────────────────────────────

    @SuppressWarnings("unchecked")
    private static List<IngredientSpec> collectIngredients(Object recipe) {
        Recipe<?> r = (Recipe<?>) recipe;
        List<IngredientSpec> specs = CraftPacketUtils.extractIngredientSpecs(r);
        if (specs != null) return specs;

        var ingredients = r.getIngredients();
        if (!ingredients.isEmpty()) {
            List<IngredientSpec> result = new ArrayList<>();
            for (Ingredient ing : ingredients) {
                if (!ing.isEmpty()) {
                    result.add(new IngredientSpec(ing, 1));
                }
            }
            if (!result.isEmpty()) return result;
        }
        return new ArrayList<>();
    }

    // ── Pedestal helpers ─────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static ItemStack readPedestalItem(Object pedestal) {
        try {
            var opt = Reflect.<Object>getField(pedestal, "itemStackHandler");
            if (opt.isEmpty()) return ItemStack.EMPTY;
            return ((LazyOptional<IItemHandler>) opt.get())
                    .map(h -> h.getStackInSlot(0)).orElse(ItemStack.EMPTY);
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.error("[RSI-Goety] Failed to read pedestal stack", e);
            return ItemStack.EMPTY;
        }
    }

    @SuppressWarnings("unchecked")
    private static void writePedestalItem(Object pedestal, ItemStack stack) {
        try {
            var opt = Reflect.<Object>getField(pedestal, "itemStackHandler");
            if (opt.isEmpty()) return;
            var lazy = (LazyOptional<IItemHandler>) opt.get();
            lazy.ifPresent(handler -> {
                handler.extractItem(0, 64, false);
                if (!stack.isEmpty()) {
                    handler.insertItem(0, stack.copy(), false);
                }
            });
            BlockEntity be = (BlockEntity) pedestal;
            be.setChanged();
            Level lvl = be.getLevel();
            if (lvl != null && !lvl.isClientSide()) {
                BlockPos pos = be.getBlockPos();
                BlockState state = be.getBlockState();
                lvl.sendBlockUpdated(pos, state, state, 3);
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-Batch-Goety] writePedestalItem failed", e);
        }
    }

    @SuppressWarnings("unchecked")
    private List<Object> findAvailablePedestals(ServerLevel level, Object ritual) {
        List<Object> result = new ArrayList<>();
        try {
            List<Object> raw = (List<Object>)
                    ritual.getClass().getMethod(GoetyReflection.M_GET_PEDESTALS, Level.class, BlockPos.class)
                            .invoke(ritual, level, myPos);
            RSIntegrationMod.LOGGER.debug("[RSI-Batch-Goety] getPedestals returned {} entries", raw.size());
            for (Object ped : raw) {
                ItemStack stack = readPedestalItem(ped);
                if (stack.isEmpty()) result.add(ped);
            }
            RSIntegrationMod.LOGGER.debug("[RSI-Batch-Goety] Pedestals: {} total, {} available",
                    raw.size(), result.size());
            if (!result.isEmpty()) return result;
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-Goety] ritual.getPedestals failed, using fallback scan", e);
        }
        int range = 16;
        for (BlockPos cp : BlockPos.betweenClosed(
                myPos.offset(-range, -range, -range),
                myPos.offset(range, range, range))) {
            if (!level.isLoaded(cp)) continue;
            BlockEntity be = level.getBlockEntity(cp);
            if (GoetyReflection.pedestalBEClass != null && GoetyReflection.pedestalBEClass.isInstance(be)) {
                ItemStack stack = readPedestalItem(be);
                if (stack.isEmpty()) result.add(be);
            }
        }
        RSIntegrationMod.LOGGER.debug("[RSI-Batch-Goety] Fallback: {} available pedestals", result.size());
        return result;
    }

    private void clearFilledPedestals() {
        if (filledPedestals == null) return;
        for (Object ped : filledPedestals) {
            ItemStack stack = readPedestalItem(ped);
            if (stack != null && !stack.isEmpty()) {
                if (usingSharedLedger) {
                    // Shared ledger owns the refund — don't double-insert.
                } else if (network != null) {
                    ItemStack leftover = network.insertItem(stack, stack.getCount(),
                            com.refinedmods.refinedstorage.api.util.Action.PERFORM);
                    if (!leftover.isEmpty()) {
                        ItemHandlerHelper.giveItemToPlayer(player, leftover);
                    }
                } else if (player != null) {
                    ItemHandlerHelper.giveItemToPlayer(player, stack);
                }
            }
            writePedestalItem(ped, ItemStack.EMPTY);
        }
    }

    private void recoverFromPedestals() {
        if (filledPedestals == null) return;
        // When player != null, abort() refunds the ledger — don't double-refund
        // physical items to RS. Only refund when player is offline (abortSilently).
        boolean refundToRS = player == null;
        for (Object ped : filledPedestals) {
            ItemStack stack = readPedestalItem(ped);
            if (stack != null && !stack.isEmpty()) {
                if (usingSharedLedger) {
                    // Shared ledger will refund the original extraction — do NOT
                    // re-insert items into RS or we double-refund (dupe exploit).
                } else if (refundToRS && network != null) {
                    ItemStack leftover = network.insertItem(stack, stack.getCount(),
                            com.refinedmods.refinedstorage.api.util.Action.PERFORM);
                    if (!leftover.isEmpty()) {
                        ItemHandlerHelper.giveItemToPlayer(player, leftover);
                    }
                } else if (refundToRS && player != null) {
                    ItemHandlerHelper.giveItemToPlayer(player, stack);
                }
            }
            writePedestalItem(ped, ItemStack.EMPTY);

            // IItemHandler capability fallback — extracts items that the reflection-based
            // itemStackHandler field access may have missed (prevents permanent item loss).
            if (ped instanceof BlockEntity be) {
                LazyOptional<IItemHandler> handler = be.getCapability(ForgeCapabilities.ITEM_HANDLER);
                handler.ifPresent(h -> {
                    for (int i = 0; i < h.getSlots(); i++) {
                        ItemStack s = h.extractItem(i, 64, false);
                        if (!s.isEmpty()) {
                            if (usingSharedLedger) {
                                // Shared ledger will refund — do not double-insert
                            } else if (refundToRS && network != null) {
                                ItemStack leftover = network.insertItem(s, s.getCount(),
                                        com.refinedmods.refinedstorage.api.util.Action.PERFORM);
                                if (!leftover.isEmpty()) {
                                    ItemHandlerHelper.giveItemToPlayer(player, leftover);
                                }
                            } else if (refundToRS && player != null) {
                                ItemHandlerHelper.giveItemToPlayer(player, s);
                            }
                        }
                    }
                });
            }
        }
    }

    private void refundActivationToPlayer() {
        if (activationExtractedFromPlayer != null && !activationExtractedFromPlayer.isEmpty() && player != null) {
            // Always refund: the activation item was extracted directly from the player's
            // inventory, not through the ledger — shared/private ledger doesn't matter.
            ItemHandlerHelper.giveItemToPlayer(player, activationExtractedFromPlayer.copy());
            activationExtractedFromPlayer = null;
        }
    }

    // ── Prerequisite validation ─────────────────────────────────

    private boolean checkRitualPrerequisites(Object recipe, Object ritual) {
        if (!checkResearchRequirement(recipe)) return false;
        if (!checkSacrificeRequirement(recipe)) return false;
        if (!checkStructureRequirements(recipe)) return false;
        if (!checkEnchantmentRequirements(recipe, ritual)) return false;
        if (!checkEnchantCompatibility(recipe, ritual)) return false;
        return true;
    }

    private boolean checkResearchRequirement(Object recipe) {
        if (GoetyReflection.researchListClass == null) return true;
        try {
            String researchId = Reflect.<String>invoke(recipe, "getResearch").orElse(null);
            if (researchId == null || researchId.isEmpty()) return true;

            Object research = GoetyReflection.researchListClass.getMethod("getResearch", String.class)
                    .invoke(null, researchId);
            if (research == null) return true;

            boolean hasIt = (boolean) GoetyReflection.seHelperClass.getMethod("hasResearch",
                    Player.class, research.getClass())
                    .invoke(null, player, research);
            if (!hasIt) {
                player.sendSystemMessage(Component.translatable(
                        "rsi.goety.error.research_required", resolveResearchName(researchId)));
                return false;
            }
            return true;
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-Batch-Goety] Research check failed, skipping", e);
            return true;
        }
    }

    private boolean checkSacrificeRequirement(Object recipe) {
        boolean requires = Reflect.<Boolean>invoke(recipe, "requiresSacrifice").orElse(false);
        if (!requires) return true;
        Component name = Reflect.<Component>invoke(recipe, "getEntityToSacrificeDisplayName")
                .orElse(Component.literal("?"));
        player.sendSystemMessage(Component.translatable("rsi.goety.error.requires_sacrifice", name));
        return false;
    }

    private boolean checkStructureRequirements(Object recipe) {
        try {
            String craftType = Reflect.<String>invoke(recipe, "getCraftType").orElse(null);
            if (craftType == null || craftType.isEmpty()) return true;
            ServerLevel level = resolveMachineLevel(player);
            Method m = GoetyReflection.ritualRequirementsClass.getMethod("getProperStructure",
                    String.class, GoetyReflection.ritualBlockEntityClass, BlockPos.class, Level.class);
            boolean valid = (boolean) m.invoke(null, craftType, altar, myPos, level);
            if (!valid) {
                player.sendSystemMessage(Component.translatable(
                        "rsi.goety.error.structure_mismatch", resolveCraftTypeName(craftType)));
                return false;
            }
            return true;
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-Batch-Goety] Structure check failed, skipping", e);
            return true;
        }
    }

    @SuppressWarnings("unchecked")
    private boolean checkRitualIsValid(Object ritual, ItemStack activationItem) {
        try {
            Recipe<?> r = (Recipe<?>) ritualRecipe;
            List<Ingredient> raw = r.getIngredients();
            List<Ingredient> filtered = new ArrayList<>();
            for (Ingredient ing : raw) {
                if (!ing.isEmpty()) filtered.add(ing);
            }
            ServerLevel level = resolveMachineLevel(player);
            boolean valid = (boolean) ritual.getClass()
                    .getMethod("isValid", Level.class, BlockPos.class, GoetyReflection.darkAltarBEClass,
                            Player.class, ItemStack.class, List.class)
                    .invoke(ritual, level, myPos, altar, player, activationItem, filtered);
            if (!valid) {
                RSIntegrationMod.LOGGER.warn("[RSI-Batch-Goety] ritual.isValid() returned false for {}, activationEmpty={}, ingredientCount={} (filtered from {} raw)",
                        ((Recipe<?>) ritualRecipe).getId(), activationItem.isEmpty(), filtered.size(), raw.size());
                return false;
            }
            return true;
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-Goety] ritual.isValid check failed", e);
            return false;
        }
    }

    private boolean checkEnchantmentRequirements(Object recipe, Object ritual) {
        if (GoetyReflection.enchantItemRitualClass == null || !GoetyReflection.enchantItemRitualClass.isInstance(ritual)) return true;
        int xpCost = Reflect.<Integer>invoke(recipe, "getXPLevelCost").orElse(0);
        if (xpCost > 0 && player.experienceLevel < xpCost) {
            player.sendSystemMessage(Component.translatable(
                    "rsi.goety.error.insufficient_xp", xpCost, player.experienceLevel));
            return false;
        }
        return true;
    }

    private boolean checkEnchantCompatibility(Object recipe, Object ritual) {
        if (GoetyReflection.enchantItemRitualClass == null || !GoetyReflection.enchantItemRitualClass.isInstance(ritual)) return true;
        try {
            Object enchantment = Reflect.invoke(recipe, GoetyReflection.M_GET_ENCHANTMENT).orElse(null);
            if (enchantment == null) {
                player.sendSystemMessage(Component.translatable("rsi.goety.error.no_enchantment"));
                return false;
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-Batch-Goety] Enchant check failed", e);
        }
        return true;
    }

    private ItemStack removeActivationFromMaterials(List<ItemStack> materials, Ingredient activationIng) {
        for (int i = 0; i < materials.size(); i++) {
            ItemStack stack = materials.get(i);
            if (activationIng.test(stack)) {
                materials.remove(i);
                return stack;
            }
        }
        ItemStack[] options = activationIng.getItems();
        if (options.length > 0) {
            Item baseItem = options[0].getItem();
            for (int i = 0; i < materials.size(); i++) {
                ItemStack stack = materials.get(i);
                if (stack.getItem() == baseItem) {
                    materials.remove(i);
                    return stack;
                }
            }
        }
        return ItemStack.EMPTY;
    }

    private ItemStack extractActivationFromPlayer(Ingredient ingredient) {
        var inv = player.getInventory();
        ItemStack result = ItemStack.EMPTY;

        for (int i = 0; i < inv.items.size(); i++) {
            ItemStack stack = inv.items.get(i);
            if (ingredient.test(stack)) {
                result = stack.split(1);
                if (stack.isEmpty()) inv.items.set(i, ItemStack.EMPTY);
                break;
            }
        }
        if (result.isEmpty()) {
            ItemStack[] options = ingredient.getItems();
            if (options.length > 0) {
                Item baseItem = options[0].getItem();
                for (int i = 0; i < inv.items.size(); i++) {
                    ItemStack stack = inv.items.get(i);
                    if (stack.getItem() == baseItem) {
                        result = stack.split(1);
                        if (stack.isEmpty()) inv.items.set(i, ItemStack.EMPTY);
                        break;
                    }
                }
            }
        }
        if (result.isEmpty()) {
            ItemStack offhand = player.getOffhandItem();
            if (ingredient.test(offhand)) {
                result = offhand.split(1);
                if (offhand.isEmpty()) player.getInventory().offhand.set(0, ItemStack.EMPTY);
            }
        }

        if (!result.isEmpty()) {
            player.getInventory().setChanged();
            player.inventoryMenu.broadcastChanges();
        }
        return result;
    }

    // ── Reflection helpers ───────────────────────────────────────

    /** Start a ritual on the altar via reflection (DarkAltarBlockEntity.startRitual).
     * @return true if the ritual started successfully */
    private static boolean startAltarRitual(Object altar, ServerPlayer player, ItemStack activation, Object recipe) {
        try {

            Method m = GoetyReflection.darkAltarBEClass.getMethod("startRitual", Player.class, ItemStack.class, GoetyReflection.ritualRecipeClass);
            m.invoke(altar, player, activation, recipe);
            return true;
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.error("[RSI-Batch-Goety] startRitual failed", e);
            return false;
        }
    }

    // ── Plan-time validation ─────────────────────────────────────

    public static List<String> getPlanWarnings(ServerPlayer player, Recipe<?> recipe,
                                               @Nullable ResourceLocation dim,
                                               @Nullable net.minecraft.core.BlockPos pos) {
        List<String> warnings = new ArrayList<>();

        if (GoetyReflection.ritualRecipeClass == null || !GoetyReflection.ritualRecipeClass.isInstance(recipe)) return warnings;

        // Research check
        String researchId = Reflect.<String>invoke(recipe, "getResearch").orElse(null);
        if (researchId != null && !researchId.isEmpty()) {
            boolean hasResearch = false;
            try {
                if (GoetyReflection.researchListClass != null && GoetyReflection.seHelperClass != null) {
                    Object research = GoetyReflection.researchListClass.getMethod("getResearch", String.class)
                            .invoke(null, researchId);
                    if (research != null) {
                        hasResearch = (boolean) GoetyReflection.seHelperClass.getMethod("hasResearch",
                                Player.class, research.getClass())
                                .invoke(null, player, research);
                    }
                }
            } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI-Batch-Goety] Research verification skipped", e); }
            if (!hasResearch) {
                warnings.add(Component.translatable(
                        "rsi.goety.warn.research_missing",
                        resolveResearchName(researchId)).getString());
            }
        }

        // Structure/craftType check
        if (pos != null && dim != null) {
            try {
                String craftType = Reflect.<String>invoke(recipe, "getCraftType").orElse(null);
                if (craftType != null && !craftType.isEmpty()) {
                    ServerLevel level = CraftPacketUtils.resolveLevel(player.server, dim, player);
                    if (level != null && level.isLoaded(pos)) {
                        BlockEntity be = level.getBlockEntity(pos);
                        if (GoetyReflection.darkAltarBEClass != null && GoetyReflection.darkAltarBEClass.isInstance(be)) {
                            Method m = GoetyReflection.ritualRequirementsClass.getMethod("getProperStructure",
                                    String.class, GoetyReflection.darkAltarBEClass, BlockPos.class, Level.class);
                            boolean valid = (boolean) m.invoke(null, craftType, be, pos, level);
                            if (!valid) {
                                warnings.add(Component.translatable(
                                        "rsi.goety.warn.structure_mismatch", resolveCraftTypeName(craftType)).getString());
                            }
                        }
                    }
                }
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.debug("[RSI-Batch-Goety] Plan structure check failed", e);
            }
        } else if (dim == null || pos == null) {
            try {
                String craftType = Reflect.<String>invoke(recipe, "getCraftType").orElse(null);
                if (craftType != null && !craftType.isEmpty()) {
                    warnings.add(Component.translatable(
                            "rsi.goety.warn.no_bound_altar", resolveCraftTypeName(craftType)).getString());
                }
            } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI-Batch-Goety] CraftType resolution skipped", e); }
        }

        return warnings;
    }

    private static String resolveCraftTypeName(String craftType) {
        String key = "jei.goety.craftType." + craftType;
        String translated = Component.translatable(key).getString();
        return translated.equals(key) ? craftType : translated;
    }

    private static String resolveResearchName(String researchId) {
        try {
            ResourceLocation rl = ResourceLocation.tryParse(researchId);
            String path = rl != null ? rl.getPath() : researchId;
            String ns = (rl != null && !rl.getNamespace().equals("minecraft"))
                    ? rl.getNamespace() : null;
            String scrollPath = path + "_scroll";

            if (ns != null) {
                Item item = BuiltInRegistries.ITEM.get(new ResourceLocation(ns, scrollPath));
                if (item != null && item != net.minecraft.world.item.Items.AIR)
                    return item.getDefaultInstance().getDisplayName().getString();
            }

            Item item = BuiltInRegistries.ITEM.get(new ResourceLocation(ModIds.GOETY, scrollPath));
            if (item != null && item != net.minecraft.world.item.Items.AIR)
                return item.getDefaultInstance().getDisplayName().getString();

            for (var entry : BuiltInRegistries.ITEM.entrySet()) {
                if (entry.getKey().location().getPath().equals(scrollPath)) {
                    return entry.getValue().getDefaultInstance().getDisplayName().getString();
                }
            }
        } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI-Batch-Goety] Research name lookup skipped", e); }
        return researchId;
    }
}
