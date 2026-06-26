package com.huanghuang.rsintegration.mods.goety;

import com.Polarice3.Goety.common.blocks.entities.DarkAltarBlockEntity;
import com.Polarice3.Goety.common.blocks.entities.NecroBrazierBlockEntity;
import com.Polarice3.Goety.common.blocks.entities.PedestalBlockEntity;
import com.Polarice3.Goety.common.blocks.entities.SoulCandlestickBlockEntity;
import com.Polarice3.Goety.common.crafting.BrazierRecipe;
import com.Polarice3.Goety.common.crafting.RitualRecipe;
import com.Polarice3.Goety.common.ritual.CraftItemRitual;
import com.Polarice3.Goety.common.ritual.EnchantItemRitual;
import com.Polarice3.Goety.common.ritual.Ritual;
import com.Polarice3.Goety.common.ritual.RitualRequirements;
import com.Polarice3.Goety.common.ritual.ConvertRitual;
import com.Polarice3.Goety.common.ritual.TeleportRitual;
import com.Polarice3.Goety.utils.SEHelper;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.batch.IBatchDelegate;
import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.ModRecipeIndex;
import com.huanghuang.rsintegration.network.AltarBindingRegistry;
import com.huanghuang.rsintegration.network.RSIntegration;
import com.refinedmods.refinedstorage.api.network.INetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.items.ItemHandlerHelper;

import javax.annotation.Nullable;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

public final class GoetyBatchDelegate implements IBatchDelegate {

    // ── Shared class refs ────────────────────────────────────────
    private static volatile boolean classesLoaded;
    private static volatile Class<?> darkAltarBEClass;
    private static volatile Class<?> seHelperClass;
    private static volatile Class<?> cursedCageBEClass;
    private static volatile Class<?> researchListClass;

    private static void ensureClasses() {
        if (classesLoaded) return;
        try {
            darkAltarBEClass = Class.forName(
                    "com.Polarice3.Goety.common.blocks.entities.DarkAltarBlockEntity");
            seHelperClass = Class.forName(
                    "com.Polarice3.Goety.utils.SEHelper");
            cursedCageBEClass = Class.forName(
                    "com.Polarice3.Goety.common.blocks.entities.CursedCageBlockEntity");
            try {
                researchListClass = Class.forName(
                        "com.Polarice3.Goety.common.research.ResearchList");
            } catch (ClassNotFoundException e) {
                RSIntegrationMod.LOGGER.debug("[RSI-Batch-Goety] ResearchList not found — research checks disabled");
                researchListClass = null;
            }
            classesLoaded = true;
        } catch (ClassNotFoundException e) {
            RSIntegrationMod.LOGGER.error("[RSI-Batch-Goety] Failed to load Goety classes", e);
        }
    }

    // ── Instance state ───────────────────────────────────────────
    private ServerPlayer player;
    private ResourceKey<Level> myDim;
    private BlockPos myPos;
    private DarkAltarBlockEntity altar;
    private RitualRecipe ritualRecipe;
    private ExtractionLedger ledger;
    private ExtractionLedger sharedLedger;
    private INetwork network;
    private List<PedestalBlockEntity> filledPedestals;
    private int soulCost;
    private boolean usingSharedLedger;
    private boolean ritualEverSeenActive;
    @Nullable private ItemStack activationExtractedFromPlayer;

    // Brazier-mode state
    private boolean isBrazier;
    private NecroBrazierBlockEntity brazier;
    private BrazierRecipe brazierRecipeObj;
    private int brazierSoulCost;
    private boolean brazierCraftStarted;

    // ── IBatchDelegate impl ───────────────────────────────────────

    @Override
    public boolean validateAndInit(ServerPlayer player, ResourceLocation recipeId,
                                   @Nullable ResourceLocation dim, BlockPos pos) {
        ServerLevel level = CraftPacketUtils.resolveLevel(player.server, dim, player);
        if (level == null) {
            player.sendSystemMessage(Component.translatable("rsi.generic.error.dim_not_found"));
            return false;
        }
        this.myDim = level.dimension();
        this.myPos = pos;
        this.player = player;

        if (!level.isLoaded(pos)) level.getChunk(pos);
        BlockEntity be = level.getBlockEntity(pos);

        // ── Brazier path ───────────────────────────────────
        if (be instanceof NecroBrazierBlockEntity nb) {
            return validateAndInitBrazier(nb, recipeId, level);
        }

        // ── Dark Altar path (original) ─────────────────────
        if (!(be instanceof DarkAltarBlockEntity darkAltar)) {
            player.sendSystemMessage(Component.translatable("rsi.goety.error.altar_not_found"));
            return false;
        }
        this.altar = darkAltar;

        Recipe<?> foundRecipe = level.getRecipeManager().byKey(recipeId).orElse(null);
        if (!(foundRecipe instanceof RitualRecipe rr)) {
            if (foundRecipe == null)
                player.sendSystemMessage(Component.translatable("rsi.generic.error.recipe_not_found", recipeId.toString()));
            else
                player.sendSystemMessage(Component.translatable("rsi.generic.error.wrong_recipe_type"));
            return false;
        }
        this.ritualRecipe = rr;
        this.ritualEverSeenActive = false;

        this.soulCost = rr.getSoulCost();

        Ritual ritualObj = rr.getRitual();
        if (ritualObj instanceof ConvertRitual) {
            player.sendSystemMessage(Component.translatable("rsi.goety.error.unsupported_ritual_type", "convert"));
            return false;
        }
        if (ritualObj instanceof TeleportRitual) {
            player.sendSystemMessage(Component.translatable("rsi.goety.error.unsupported_ritual_type", "teleport"));
            return false;
        }

        if (altar.currentRitualRecipe != null) {
            player.sendSystemMessage(Component.translatable("rsi.goety.warn.altar_busy"));
            return false;
        }

        // Verify all pedestals are empty.  A craft that consumes items
        // immediately can leave `getCurrentRitualRecipe() == null` while
        // pedestals still hold leftovers; a second craft would overwrite
        // them and cause item duplication.
        try {
            @SuppressWarnings("unchecked")
            List<PedestalBlockEntity> allPedestals = (List<PedestalBlockEntity>)
                    ritualObj.getClass().getMethod("getPedestals", Level.class, BlockPos.class)
                            .invoke(ritualObj, level, myPos);
            if (allPedestals != null) {
                for (PedestalBlockEntity ped : allPedestals) {
                    if (!readPedestalItem(ped).isEmpty()) {
                        player.sendSystemMessage(Component.translatable("rsi.goety.warn.altar_busy"));
                        return false;
                    }
                }
            }
        } catch (Exception e) {
            // If we can't scan pedestals, err on the safe side: skip the
            // check but log loudly so the admin can investigate.
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-Goety] Pedestal scan failed — proceeding without pedestal check", e);
        }

        RSIntegrationMod.LOGGER.debug("[RSI-Batch-Goety] validateAndInit OK: recipe={}", recipeId);
        return true;
    }

    private boolean validateAndInitBrazier(NecroBrazierBlockEntity nb, ResourceLocation recipeId,
                                           ServerLevel level) {
        this.isBrazier = true;
        this.brazier = nb;
        this.altar = null;
        this.ritualRecipe = null;

        Recipe<?> foundRecipe = level.getRecipeManager().byKey(recipeId).orElse(null);
        if (!(foundRecipe instanceof BrazierRecipe br)) {
            if (foundRecipe == null)
                player.sendSystemMessage(Component.translatable("rsi.generic.error.recipe_not_found", recipeId.toString()));
            else
                player.sendSystemMessage(Component.translatable("rsi.generic.error.wrong_recipe_type"));
            return false;
        }
        this.brazierRecipeObj = br;
        this.brazierSoulCost = br.getSoulCost();

        // Idle check: currentTime must be 0 and no active recipe
        if (nb.currentTime > 0 || nb.recipe != null) {
            player.sendSystemMessage(Component.translatable("rsi.goety.warn.altar_busy"));
            return false;
        }

        // Inventory must be empty (no leftover items from previous manual craft)
        if (!nb.isEmpty()) {
            player.sendSystemMessage(Component.translatable("rsi.goety.warn.altar_busy"));
            return false;
        }

        // Deep Dark biome check — the brazier tick() requires it
        ResourceKey<Biome> deepDark = ResourceKey.create(Registries.BIOME,
                new ResourceLocation("minecraft", "deep_dark"));
        if (!level.getBiome(myPos).is(deepDark)) {
            player.sendSystemMessage(Component.translatable("rsi.goety.error.brazier_wrong_biome"));
            return false;
        }

        // Soul candlestick check
        var candlesticks = findNearbyCandlesticks(level, myPos);
        if (candlesticks.isEmpty()) {
            player.sendSystemMessage(Component.translatable("rsi.goety.error.brazier_no_candlesticks"));
            return false;
        }
        int totalSouls = candlesticks.stream().mapToInt(SoulCandlestickBlockEntity::getSouls).sum();
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
        this.player = player;
        this.ledger = new ExtractionLedger();
        this.network = CraftPacketUtils.resolveNetworkForCraft(player, myDim, myPos);
        this.activationExtractedFromPlayer = null;

        if (isBrazier) return tryStartBrazierCraft();

        // ── Dark Altar path ────────────────────────────────
        if (altar.currentRitualRecipe != null) return false;

        this.filledPedestals = new ArrayList<>();

        if (!validateSoulsAvailable(soulCost)) return false;

        Ritual ritual = ritualRecipe.getRitual();
        if (ritual == null) {
            RSIntegrationMod.LOGGER.error("[RSI-Batch-Goety] Ritual is null for recipe {}", ritualRecipe.getId());
            return false;
        }

        if (!checkRitualPrerequisites(ritualRecipe, ritual)) return false;

        List<IngredientSpec> specList = collectIngredients(ritualRecipe);
        Ingredient activationIng = ritualRecipe.getActivationItem();
        if (activationIng != null && !activationIng.isEmpty()) {
            ItemStack[] actItems = activationIng.getItems();
            if (actItems.length > 0) {
                Item actBase = actItems[0].getItem();
                specList.removeIf(spec -> {
                    ItemStack[] specItems = spec.ingredient().getItems();
                    return specItems.length > 0 && specItems[0].getItem() == actBase;
                });
            }
        }

        ItemStack activationItemStack = ItemStack.EMPTY;
        if (activationIng != null && !activationIng.isEmpty()) {
            activationItemStack = CraftPacketUtils.ensureMaterialAvailable(
                    player, myDim, myPos, activationIng, 1, ledger);
            if (activationItemStack.isEmpty()) {
                RSIntegrationMod.LOGGER.debug("[RSI-Batch-Goety] Activation item not in RS, extracting from player");
                activationItemStack = extractActivationFromPlayer(activationIng);
                if (activationItemStack.isEmpty()) {
                    player.sendSystemMessage(Component.translatable("rsi.goety.error.missing_activation",
                            CraftPacketUtils.describeIngredient(activationIng)));
                    return false;
                }
                this.activationExtractedFromPlayer = activationItemStack.copy();
            }
        }

        if (specList.isEmpty()) {
            try {
                if (!checkRitualIsValid(ritual, activationItemStack)) {
                    player.sendSystemMessage(Component.translatable("rsi.goety.error.ritual_invalid"));
                    refundActivationToPlayer();
                    ledger.rollback(player);
                    return false;
                }
                altar.startRitual(player, activationItemStack, ritualRecipe);
                if (!ledger.commit(network, player)) {
                    RSIntegrationMod.LOGGER.error("[RSI-Batch-Goety] Ledger commit failed (no ingredients)");
                    return false;
                }
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.error("[RSI-Batch-Goety] Failed to start ritual", e);
                refundActivationToPlayer();
                ledger.rollback(player);
                return false;
            }
            return true;
        }

        List<PedestalBlockEntity> availablePedestals = findAvailablePedestals(player.serverLevel(), ritual);
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

        try {
            for (int i = 0; i < templates.size(); i++) {
                ItemStack stack = templates.get(i);
                if (stack.isEmpty()) continue;
                PedestalBlockEntity ped = availablePedestals.get(i);
                writePedestalItem(ped, stack);
                filledPedestals.add(ped);
            }

            if (!checkRitualIsValid(ritual, activationItemStack)) {
                player.sendSystemMessage(Component.translatable("rsi.goety.error.ritual_invalid"));
                refundActivationToPlayer();
                recoverFromPedestals();
                ledger.rollback(player);
                return false;
            }
            altar.startRitual(player, activationItemStack, ritualRecipe);
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.error("[RSI-Batch-Goety] Placement/start failed:", e);
            refundActivationToPlayer();
            recoverFromPedestals();
            ledger.rollback(player);
            return false;
        }

        if (!ledger.commit(network, player)) {
            RSIntegrationMod.LOGGER.error("[RSI-Batch-Goety] Ledger commit failed after ritual start");
            return true;
        }

        return true;
    }

    private boolean tryStartBrazierCraft() {
        ServerLevel level = player.serverLevel();
        if (!level.isLoaded(myPos)) level.getChunk(myPos);

        // Re-verify idle
        if (brazier.currentTime > 0 || brazier.recipe != null || !brazier.isEmpty()) {
            player.sendSystemMessage(Component.translatable("rsi.goety.warn.altar_busy"));
            return false;
        }

        List<Ingredient> ingredients = brazierRecipeObj.getIngredients();
        NonNullList<ItemStack> toPlace = NonNullList.withSize(brazier.getContainer().getContainerSize(), ItemStack.EMPTY);

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

        brazier.setItems(toPlace);
        brazier.recipeId = brazierRecipeObj.getId();
        brazier.updateRecipe(level);

        if (brazier.recipe == null) {
            RSIntegrationMod.LOGGER.error("[RSI-Batch-Goety] Brazier recipe not set after updateRecipe: {}",
                    brazierRecipeObj.getId());
            brazier.setItems(NonNullList.withSize(brazier.getContainer().getContainerSize(), ItemStack.EMPTY));
            brazier.recipeId = null;
            ledger.rollback(player);
            return false;
        }

        if (!ledger.commit(network, player)) {
            RSIntegrationMod.LOGGER.error("[RSI-Batch-Goety] Ledger commit failed for brazier craft");
            return true;
        }

        this.brazierCraftStarted = true;
        RSIntegrationMod.LOGGER.debug("[RSI-Batch-Goety] Brazier craft started: recipe={} soulCost={}",
                brazierRecipeObj.getId(), brazierSoulCost);
        return true;
    }

    @Override
    @Nullable
    public List<IngredientSpec> getRequiredMaterials() {
        if (isBrazier) {
            if (brazierRecipeObj == null) return null;
            List<IngredientSpec> specs = new ArrayList<>();
            for (Ingredient ing : brazierRecipeObj.getIngredients()) {
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

        if (isBrazier) return tryStartBrazierWithMaterials(materials);

        // ── Dark Altar path ────────────────────────────────
        if (altar.currentRitualRecipe != null) {
            player.sendSystemMessage(Component.translatable("rsi.goety.warn.altar_busy"));
            return false;
        }

        this.filledPedestals = new ArrayList<>();

        if (!validateSoulsAvailable(soulCost)) return false;

        Ritual ritual = ritualRecipe.getRitual();
        if (ritual == null) {
            RSIntegrationMod.LOGGER.error("[RSI-Batch-Goety] tryStartWithMaterials: Ritual is null for recipe {}", ritualRecipe.getId());
            player.sendSystemMessage(Component.translatable("rsi.goety.error.one_click_failed"));
            return false;
        }

        if (!checkRitualPrerequisites(ritualRecipe, ritual)) return false;

        ItemStack activationItem = ItemStack.EMPTY;
        Ingredient activationIng = ritualRecipe.getActivationItem();
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
                altar.startRitual(player, activationItem, ritualRecipe);
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.error("[RSI-Batch-Goety] tryStartWithMaterials: Failed to start ritual (no materials)", e);
                player.sendSystemMessage(Component.translatable("rsi.goety.error.one_click_failed"));
                refundActivationToPlayer();
                return false;
            }
            return true;
        }

        List<PedestalBlockEntity> availablePedestals = findAvailablePedestals(player.serverLevel(), ritual);
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
                PedestalBlockEntity ped = availablePedestals.get(i);
                writePedestalItem(ped, stack);
                filledPedestals.add(ped);
            }

            if (!checkRitualIsValid(ritual, activationItem)) {
                player.sendSystemMessage(Component.translatable("rsi.goety.error.ritual_invalid"));
                refundActivationToPlayer();
                recoverFromPedestals();
                return false;
            }
            altar.startRitual(player, activationItem, ritualRecipe);
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
        ServerLevel level = player.serverLevel();
        if (!level.isLoaded(myPos)) level.getChunk(myPos);

        if (brazier.currentTime > 0 || brazier.recipe != null || !brazier.isEmpty()) {
            player.sendSystemMessage(Component.translatable("rsi.goety.warn.altar_busy"));
            return false;
        }

        NonNullList<ItemStack> toPlace = NonNullList.withSize(
                brazier.getContainer().getContainerSize(), ItemStack.EMPTY);
        int slot = 0;
        for (ItemStack mat : materials) {
            if (mat.isEmpty()) continue;
            toPlace.set(slot++, mat.copy());
        }

        brazier.setItems(toPlace);
        brazier.recipeId = brazierRecipeObj.getId();
        brazier.updateRecipe(level);

        if (brazier.recipe == null) {
            RSIntegrationMod.LOGGER.error("[RSI-Batch-Goety] Brazier recipe not set in tryStartWithMaterials: {}",
                    brazierRecipeObj.getId());
            brazier.setItems(NonNullList.withSize(brazier.getContainer().getContainerSize(), ItemStack.EMPTY));
            brazier.recipeId = null;
            player.sendSystemMessage(Component.translatable("rsi.goety.error.one_click_failed"));
            return false;
        }

        this.brazierCraftStarted = true;
        return true;
    }

    @Override
    public boolean isCraftComplete(ServerLevel level) {
        if (isBrazier) return isBrazierCraftComplete(level);

        // Use the public field directly — getCurrentRitualRecipe() has a side
        // effect: it re-populates currentRitualRecipe from currentRitualRecipeId
        // via recipe-manager lookup, which defeats null-after-completion detection.
        if (altar.currentRitualRecipe != null) {
            ritualEverSeenActive = true;
            return false;
        }
        if (!ritualEverSeenActive && player != null) {
            ItemStack expected = ModRecipeIndex.tryGetResultItem(ritualRecipe, level.registryAccess());
            if (!expected.isEmpty()) {
                if (myPos != null && level.isLoaded(myPos)) {
                    var entities = level.getEntitiesOfClass(
                            net.minecraft.world.entity.item.ItemEntity.class,
                            new net.minecraft.world.phys.AABB(myPos).inflate(3),
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
        if (brazier.recipe == null && brazier.currentTime == 0 && brazier.isEmpty()) {
            return true;
        }
        return false;
    }

    @Override
    public ItemStack collectResult(ServerPlayer player) {
        if (isBrazier) return collectBrazierResult(player);

        ItemStack expected = ModRecipeIndex.tryGetResultItem(ritualRecipe, player.serverLevel().registryAccess());
        if (expected.isEmpty()) return ItemStack.EMPTY;

        // 1. Check altar's own inventory (slot 0 is where Ritual.finish() places the result)
        try {
            var resolved = altar.itemStackHandler.resolve();
            if (resolved.isPresent()) {
                var handler = resolved.get();
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
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-Batch-Goety] Altar inventory read failed: {}", e.toString());
        }

        // 2. Scan for ItemEntity near the altar
        if (myPos != null && player.serverLevel().isLoaded(myPos)) {
            var entities = player.serverLevel().getEntitiesOfClass(
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
        } catch (Throwable e) { RSIntegrationMod.LOGGER.debug("[RSI] Reflection probe failed", e); }

        for (ItemStack stack : all) {
            if (ItemStack.isSameItemSameTags(stack, expected)) {
                ItemStack collected = stack.split(expected.getCount());
                return collected;
            }
        }

        return ItemStack.EMPTY;
    }

    private ItemStack collectBrazierResult(ServerPlayer player) {
        ItemStack expected = brazierRecipeObj.getResultItem(player.serverLevel().registryAccess());
        if (expected.isEmpty()) return ItemStack.EMPTY;

        // stopBrazier(true) drops the result at (x, y+1, z)
        // including an offset for the random scatter in dropItemStack
        if (myPos != null && player.serverLevel().isLoaded(myPos)) {
            var entities = player.serverLevel().getEntitiesOfClass(
                    net.minecraft.world.entity.item.ItemEntity.class,
                    new net.minecraft.world.phys.AABB(myPos).inflate(3),
                    e -> ItemStack.isSameItemSameTags(e.getItem(), expected));
            for (var entity : entities) {
                ItemStack collected = entity.getItem().copy();
                if (collected.getCount() > expected.getCount()) {
                    collected.setCount(expected.getCount());
                }
                entity.getItem().shrink(collected.getCount());
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
    public void onBatchFailed(ServerPlayer player, String reason) {
        if (isBrazier) {
            recoverBrazierItems();
        } else {
            ritualEverSeenActive = false;
            refundActivationToPlayer();
            recoverFromPedestals();
        }
        ledger = null;
        sharedLedger = null;
        network = null;
        usingSharedLedger = false;
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
        ledger = null;
        sharedLedger = null;
        network = null;
        usingSharedLedger = false;
        activationExtractedFromPlayer = null;
    }

    private void recoverBrazierItems() {
        if (brazier == null) return;
        NonNullList<ItemStack> items = brazier.getItems();
        boolean hasItems = false;
        for (ItemStack stack : items) {
            if (!stack.isEmpty()) {
                hasItems = true;
                break;
            }
        }
        if (!hasItems) return;

        if (usingSharedLedger && network != null) {
            // Shared committed ledger: items are template copies.
            // Only CLEAR the slots — refund is centralized via ledger.refundCommitted().
            for (ItemStack stack : items) {
                if (!stack.isEmpty()) stack.setCount(0);
            }
        } else if (player != null) {
            for (ItemStack stack : items) {
                if (!stack.isEmpty()) {
                    ItemHandlerHelper.giveItemToPlayer(player, stack.copy());
                }
            }
        }
        brazier.setItems(NonNullList.withSize(brazier.getContainer().getContainerSize(), ItemStack.EMPTY));
        brazier.recipe = null;
        brazier.recipeId = null;
        brazier.currentTime = 0;
        brazierCraftStarted = false;
    }

    @Override
    public BlockPos getMachinePos() {
        return myPos;
    }

    // ── Brazier helpers ─────────────────────────────────────────

    private static final int CANDLESTICK_SEARCH_RANGE = 8;

    private static List<SoulCandlestickBlockEntity> findNearbyCandlesticks(
            ServerLevel level, BlockPos pos) {
        List<SoulCandlestickBlockEntity> result = new ArrayList<>();
        int r = CANDLESTICK_SEARCH_RANGE;
        for (BlockPos cp : BlockPos.betweenClosed(
                pos.offset(-r, -r, -r), pos.offset(r, r, r))) {
            if (!level.isLoaded(cp)) continue;
            BlockEntity be = level.getBlockEntity(cp);
            if (be instanceof SoulCandlestickBlockEntity cs && cs.getSouls() > 0) {
                result.add(cs);
            }
        }
        return result;
    }

    // ── Soul energy ──────────────────────────────────────────────

    private boolean validateSoulsAvailable(int cost) {
        if (cost <= 0) return true;
        try {
            var cageOpt = com.huanghuang.rsintegration.util.Reflect.getField(altar, "cursedCageTile");
            if (cageOpt.isEmpty() || cageOpt.get() == null) {
                RSIntegrationMod.LOGGER.debug("[RSI-Batch-Goety] No cursedCageTile — assuming link gem/Arca, skipping soul check");
                return true;
            }
            Object cage = cageOpt.get();
            if (cursedCageBEClass == null || !cursedCageBEClass.isInstance(cage)) return true;

            int available = (int) cursedCageBEClass.getMethod("getSouls").invoke(cage);
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
    private static List<IngredientSpec> collectIngredients(RitualRecipe recipe) {
        List<IngredientSpec> specs = CraftPacketUtils.extractIngredientSpecs(recipe);
        if (specs != null) return specs;

        var ingredients = recipe.getIngredients();
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

    private static ItemStack readPedestalItem(PedestalBlockEntity pedestal) {
        return pedestal.itemStackHandler.map(h -> h.getStackInSlot(0)).orElse(ItemStack.EMPTY);
    }

    private static void writePedestalItem(PedestalBlockEntity pedestal, ItemStack stack) {
        pedestal.itemStackHandler.ifPresent(handler -> {
            handler.extractItem(0, 64, false);
            if (!stack.isEmpty()) {
                handler.insertItem(0, stack.copy(), false);
            }
        });
        pedestal.setChanged();
        if (pedestal.getLevel() != null && !pedestal.getLevel().isClientSide()) {
            pedestal.getLevel().sendBlockUpdated(pedestal.getBlockPos(), pedestal.getBlockState(), pedestal.getBlockState(), 3);
        }
    }

    @SuppressWarnings("unchecked")
    private List<PedestalBlockEntity> findAvailablePedestals(ServerLevel level, Ritual ritual) {
        List<PedestalBlockEntity> result = new ArrayList<>();
        try {
            List<PedestalBlockEntity> raw = (List<PedestalBlockEntity>)
                    ritual.getClass().getMethod("getPedestals", Level.class, BlockPos.class)
                            .invoke(ritual, level, myPos);
            RSIntegrationMod.LOGGER.debug("[RSI-Batch-Goety] getPedestals returned {} entries", raw.size());
            for (PedestalBlockEntity ped : raw) {
                ItemStack stack = readPedestalItem(ped);
                if (stack.isEmpty()) result.add(ped);
            }
            RSIntegrationMod.LOGGER.debug("[RSI-Batch-Goety] Pedestals: {} total, {} available",
                    raw.size(), result.size());
            if (!result.isEmpty()) return result;
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-Goety] ritual.getPedestals failed, using fallback scan: {}", e.toString());
        }
        int range = 16;
        for (BlockPos cp : BlockPos.betweenClosed(
                myPos.offset(-range, -range, -range),
                myPos.offset(range, range, range))) {
            if (!level.isLoaded(cp)) continue;
            BlockEntity be = level.getBlockEntity(cp);
            if (be instanceof PedestalBlockEntity ped) {
                ItemStack stack = readPedestalItem(ped);
                if (stack.isEmpty()) result.add(ped);
            }
        }
        RSIntegrationMod.LOGGER.debug("[RSI-Batch-Goety] Fallback: {} available pedestals", result.size());
        return result;
    }

    private void clearFilledPedestals() {
        if (filledPedestals == null) return;
        for (PedestalBlockEntity ped : filledPedestals) {
            writePedestalItem(ped, ItemStack.EMPTY);
        }
    }

    private void recoverFromPedestals() {
        if (filledPedestals == null) return;
        for (PedestalBlockEntity ped : filledPedestals) {
            ItemStack stack = readPedestalItem(ped);
            if (stack != null && !stack.isEmpty()) {
                if (!usingSharedLedger) {
                    if (network != null) {
                        ItemStack leftover = network.insertItem(stack, stack.getCount(),
                                com.refinedmods.refinedstorage.api.util.Action.PERFORM);
                        if (!leftover.isEmpty()) {
                            ItemHandlerHelper.giveItemToPlayer(player, leftover);
                        }
                    } else if (player != null) {
                        ItemHandlerHelper.giveItemToPlayer(player, stack);
                    }
                }
            }
            writePedestalItem(ped, ItemStack.EMPTY);
        }
    }

    private void refundActivationToPlayer() {
        if (activationExtractedFromPlayer != null && !activationExtractedFromPlayer.isEmpty() && player != null) {
            if (!usingSharedLedger) {
                ItemHandlerHelper.giveItemToPlayer(player, activationExtractedFromPlayer.copy());
            }
            activationExtractedFromPlayer = null;
        }
    }

    // ── Prerequisite validation ─────────────────────────────────

    private boolean checkRitualPrerequisites(RitualRecipe recipe, Ritual ritual) {
        if (!checkResearchRequirement(recipe)) return false;
        if (!checkSacrificeRequirement(recipe)) return false;
        if (!checkStructureRequirements(recipe)) return false;
        if (!checkEnchantmentRequirements(recipe, ritual)) return false;
        if (!checkEnchantCompatibility(recipe, ritual)) return false;
        return true;
    }

    private boolean checkResearchRequirement(RitualRecipe recipe) {
        if (researchListClass == null) return true;
        try {
            String researchId = recipe.getResearch();
            if (researchId == null || researchId.isEmpty()) return true;

            Object research = researchListClass.getMethod("getResearch", String.class)
                    .invoke(null, researchId);
            if (research == null) return true;

            boolean hasIt = (boolean) seHelperClass.getMethod("hasResearch",
                    net.minecraft.world.entity.player.Player.class, research.getClass())
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

    private boolean checkSacrificeRequirement(RitualRecipe recipe) {
        if (!recipe.requiresSacrifice()) return true;
        player.sendSystemMessage(Component.translatable(
                "rsi.goety.error.requires_sacrifice", recipe.getEntityToSacrificeDisplayName()));
        return false;
    }

    private boolean checkStructureRequirements(RitualRecipe recipe) {
        try {
            String craftType = recipe.getCraftType();
            if (craftType == null || craftType.isEmpty()) return true;
            ServerLevel level = player.serverLevel();
            boolean valid = RitualRequirements.getProperStructure(craftType, altar, myPos, level);
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
    private boolean checkRitualIsValid(Ritual ritual, ItemStack activationItem) {
        try {
            List<Ingredient> raw = ritualRecipe.getIngredients();
            List<Ingredient> filtered = new ArrayList<>();
            for (Ingredient ing : raw) {
                if (!ing.isEmpty()) filtered.add(ing);
            }
            ServerLevel level = player.serverLevel();
            boolean valid = ritual.isValid(level, myPos, altar, player, activationItem, filtered);
            if (!valid) {
                RSIntegrationMod.LOGGER.warn("[RSI-Batch-Goety] ritual.isValid() returned false for {}, activationEmpty={}, ingredientCount={} (filtered from {} raw)",
                        ritualRecipe.getId(), activationItem.isEmpty(), filtered.size(), raw.size());
                return false;
            }
            return true;
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-Goety] ritual.isValid check failed: {}", e.toString());
            return false;
        }
    }

    private boolean checkEnchantmentRequirements(RitualRecipe recipe, Ritual ritual) {
        if (!(ritual instanceof EnchantItemRitual)) return true;
        int xpCost = recipe.getXPLevelCost();
        if (xpCost > 0 && player.experienceLevel < xpCost) {
            player.sendSystemMessage(Component.translatable(
                    "rsi.goety.error.insufficient_xp", xpCost, player.experienceLevel));
            return false;
        }
        return true;
    }

    private boolean checkEnchantCompatibility(RitualRecipe recipe, Ritual ritual) {
        if (!(ritual instanceof EnchantItemRitual)) return true;
        try {
            if (recipe.getEnchantment() == null) {
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
        for (int i = 0; i < inv.items.size(); i++) {
            ItemStack stack = inv.items.get(i);
            if (ingredient.test(stack)) {
                ItemStack result = stack.split(1);
                if (stack.isEmpty()) inv.items.set(i, ItemStack.EMPTY);
                return result;
            }
        }
        ItemStack[] options = ingredient.getItems();
        if (options.length > 0) {
            Item baseItem = options[0].getItem();
            for (int i = 0; i < inv.items.size(); i++) {
                ItemStack stack = inv.items.get(i);
                if (stack.getItem() == baseItem) {
                    ItemStack result = stack.split(1);
                    if (stack.isEmpty()) inv.items.set(i, ItemStack.EMPTY);
                    return result;
                }
            }
        }
        ItemStack offhand = player.getOffhandItem();
        if (ingredient.test(offhand)) {
            ItemStack result = offhand.split(1);
            if (offhand.isEmpty()) player.getInventory().offhand.set(0, ItemStack.EMPTY);
            return result;
        }
        return ItemStack.EMPTY;
    }

    // ── Reflection helpers ───────────────────────────────────────

    private static Method getMethod(Class<?> clazz, String name, Class<?>... paramTypes) throws NoSuchMethodException {
        Method m = com.huanghuang.rsintegration.util.Reflect.findMethod(clazz, name, paramTypes);
        if (m == null) throw new NoSuchMethodException(clazz.getName() + "." + name);
        return m;
    }

    // ── Plan-time validation ─────────────────────────────────────

    public static List<String> getPlanWarnings(ServerPlayer player, Recipe<?> recipe,
                                               @Nullable ResourceLocation dim,
                                               @Nullable net.minecraft.core.BlockPos pos) {
        List<String> warnings = new ArrayList<>();
        if (!(recipe instanceof RitualRecipe rr)) return warnings;

        // Research check
        String researchId = rr.getResearch();
        if (researchId != null && !researchId.isEmpty()) {
            boolean hasResearch = false;
            try {
                ensureClasses();
                if (researchListClass != null && seHelperClass != null) {
                    Object research = researchListClass.getMethod("getResearch", String.class)
                            .invoke(null, researchId);
                    if (research != null) {
                        hasResearch = (boolean) seHelperClass.getMethod("hasResearch",
                                net.minecraft.world.entity.player.Player.class, research.getClass())
                                .invoke(null, player, research);
                    }
                }
            } catch (Exception e) { /* can't verify — skip */ }
            if (!hasResearch) {
                warnings.add(Component.translatable(
                        "rsi.goety.warn.research_missing",
                        resolveResearchName(researchId)).getString());
            }
        }

        // Structure/craftType check
        if (pos != null && dim != null) {
            try {
                String craftType = rr.getCraftType();
                if (craftType != null && !craftType.isEmpty()) {
                    ServerLevel level = CraftPacketUtils.resolveLevel(player.server, dim, player);
                    if (level != null && level.isLoaded(pos)) {
                        BlockEntity be = level.getBlockEntity(pos);
                        if (be instanceof DarkAltarBlockEntity darkAltar) {
                            boolean valid = RitualRequirements.getProperStructure(craftType, darkAltar, pos, level);
                            if (!valid) {
                                warnings.add(Component.translatable(
                                        "rsi.goety.warn.structure_mismatch", resolveCraftTypeName(craftType)).getString());
                            }
                        }
                    }
                }
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.debug("[RSI-Batch-Goety] Plan structure check failed: {}", e.toString());
            }
        } else if (dim == null || pos == null) {
            try {
                String craftType = rr.getCraftType();
                if (craftType != null && !craftType.isEmpty()) {
                    warnings.add(Component.translatable(
                            "rsi.goety.warn.no_bound_altar", resolveCraftTypeName(craftType)).getString());
                }
            } catch (Exception e) { /* ignore */ }
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

            Item item = BuiltInRegistries.ITEM.get(new ResourceLocation("goety", scrollPath));
            if (item != null && item != net.minecraft.world.item.Items.AIR)
                return item.getDefaultInstance().getDisplayName().getString();

            for (var entry : BuiltInRegistries.ITEM.entrySet()) {
                if (entry.getKey().location().getPath().equals(scrollPath)) {
                    return entry.getValue().getDefaultInstance().getDisplayName().getString();
                }
            }
        } catch (Exception e) { /* fall through */ }
        return researchId;
    }
}
