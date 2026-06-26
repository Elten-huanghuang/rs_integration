package com.huanghuang.rsintegration.mods.goety;

import com.Polarice3.Goety.common.blocks.entities.DarkAltarBlockEntity;
import com.Polarice3.Goety.common.blocks.entities.PedestalBlockEntity;
import com.Polarice3.Goety.common.crafting.RitualRecipe;
import com.Polarice3.Goety.common.ritual.CraftItemRitual;
import com.Polarice3.Goety.common.ritual.EnchantItemRitual;
import com.Polarice3.Goety.common.ritual.Ritual;
import com.Polarice3.Goety.common.ritual.RitualRequirements;
import com.Polarice3.Goety.common.ritual.SummonRitual;
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
import net.minecraft.core.registries.BuiltInRegistries;
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

        // Filter unsupported ritual subtypes
        Ritual ritualObj = rr.getRitual();
        if (ritualObj instanceof ConvertRitual) {
            player.sendSystemMessage(Component.translatable("rsi.goety.error.unsupported_ritual_type", "convert"));
            return false;
        }
        if (ritualObj instanceof TeleportRitual) {
            player.sendSystemMessage(Component.translatable("rsi.goety.error.unsupported_ritual_type", "teleport"));
            return false;
        }

        // Validate idle
        if (altar.getCurrentRitualRecipe() != null) {
            player.sendSystemMessage(Component.translatable("rsi.goety.warn.altar_busy"));
            return false;
        }

        RSIntegrationMod.LOGGER.debug("[RSI-Batch-Goety] validateAndInit OK: recipe={}", recipeId);
        return true;
    }

    @Override
    public boolean tryStartSingleCraft(ServerPlayer player) {
        this.player = player;
        this.ledger = new ExtractionLedger();
        this.network = CraftPacketUtils.resolveNetworkForCraft(player, myDim, myPos);
        this.activationExtractedFromPlayer = null;

        if (altar.getCurrentRitualRecipe() != null) return false;

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

    @Override
    @Nullable
    public List<IngredientSpec> getRequiredMaterials() {
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

        if (altar.getCurrentRitualRecipe() != null) {
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

    @Override
    public boolean isCraftComplete(ServerLevel level) {
        if (altar.getCurrentRitualRecipe() != null) {
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

    @Override
    public ItemStack collectResult(ServerPlayer player) {
        ItemStack expected = ModRecipeIndex.tryGetResultItem(ritualRecipe, player.serverLevel().registryAccess());
        if (expected.isEmpty()) return ItemStack.EMPTY;

        // 1. Check altar's own inventory — Goety places the result into the
        //    altar's itemStackHandler (slot 0) so it renders on top of the block.
        try {
            var resolved = altar.itemStackHandler.resolve();
            if (resolved.isPresent()) {
                var handler = resolved.get();
                ItemStack inAltar = handler.getStackInSlot(0);
                if (!inAltar.isEmpty() && ItemStack.isSameItemSameTags(inAltar, expected)) {
                    ItemStack collected = inAltar.copy();
                    // Cap to expected output count (batch craft may want fewer)
                    if (collected.getCount() > expected.getCount()) {
                        collected.setCount(expected.getCount());
                    }
                    handler.extractItem(0, collected.getCount(), false);
                    return collected;
                }
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-Batch-Goety] Altar inventory read failed: {}", e.toString());
        }

        // 2. Scan for ItemEntity near the altar (belt-and-suspenders).
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
                return collected;
            }
        }

        // 3. Fallback: check player inventory (player may have picked it up already)
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

    @Override
    public void onBatchFailed(ServerPlayer player, String reason) {
        ritualEverSeenActive = false;
        refundActivationToPlayer();
        recoverFromPedestals();
        ledger = null;
        sharedLedger = null;
        network = null;
        usingSharedLedger = false;
        activationExtractedFromPlayer = null;
    }

    @Override
    public void onBatchFinished(ServerPlayer player) {
        ritualEverSeenActive = false;
        clearFilledPedestals();
        ledger = null;
        sharedLedger = null;
        network = null;
        usingSharedLedger = false;
        activationExtractedFromPlayer = null;
    }

    @Override
    public BlockPos getMachinePos() {
        return myPos;
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
        // Fallback: scan within default pedestal range (16 blocks)
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
                if (usingSharedLedger) {
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

    /** Return a player-extracted activation item on failure so it is not lost. */
    private void refundActivationToPlayer() {
        if (activationExtractedFromPlayer != null && !activationExtractedFromPlayer.isEmpty() && player != null) {
            ItemHandlerHelper.giveItemToPlayer(player, activationExtractedFromPlayer.copy());
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

    // ── Plan-time validation (called from GenericCraftPacket.tryBuildPlan) ──

    /**
     * Validate Goety-specific prerequisites for plan display.
     * Called during plan building so the UI can show research/structure warnings.
     *
     * @return list of pre-translated warning strings (empty = all clear)
     */
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

    /**
     * Resolve a Goety internal craftType string to its translated display name
     * using Goety's own {@code jei.goety.craftType.*} localisation keys.
     * Falls back to the raw string for unknown or addon-provided types.
     */
    private static String resolveCraftTypeName(String craftType) {
        String key = "jei.goety.craftType." + craftType;
        String translated = Component.translatable(key).getString();
        // Component.translatable returns the key itself when no translation exists
        return translated.equals(key) ? craftType : translated;
    }

    /**
     * Resolve a research ID to its translated scroll display name.
     * Research IDs come in several flavours:
     * <ul>
     *   <li>{@code frost} — simple name, scroll is usually {@code goety:frost_scroll}</li>
     *   <li>{@code goety:frost} — already namespaced</li>
     *   <li>{@code ars_nouveau:fire} — addon namespace</li>
     * </ul>
     * We try namespace-scoped lookups first, then fall back to scanning the
     * entire item registry for any scroll whose path matches, to accommodate
     * Goety addons registering under arbitrary mod IDs.
     */
    private static String resolveResearchName(String researchId) {
        try {
            // Parse: "frost" → path=frost, ns=null; "goety:frost" → path=frost, ns=goety
            ResourceLocation rl = ResourceLocation.tryParse(researchId);
            String path = rl != null ? rl.getPath() : researchId;
            String ns = (rl != null && !rl.getNamespace().equals("minecraft"))
                    ? rl.getNamespace() : null;
            String scrollPath = path + "_scroll";

            // 1. Explicit namespace from the research ID (e.g. ars_nouveau:fire → ars_nouveau:fire_scroll)
            if (ns != null) {
                Item item = BuiltInRegistries.ITEM.get(new ResourceLocation(ns, scrollPath));
                if (item != null && item != net.minecraft.world.item.Items.AIR)
                    return item.getDefaultInstance().getDisplayName().getString();
            }

            // 2. Default Goety namespace (most common case)
            Item item = BuiltInRegistries.ITEM.get(new ResourceLocation("goety", scrollPath));
            if (item != null && item != net.minecraft.world.item.Items.AIR)
                return item.getDefaultInstance().getDisplayName().getString();

            // 3. Scan entire registry for any namespace with the matching scroll path
            for (var entry : BuiltInRegistries.ITEM.entrySet()) {
                if (entry.getKey().location().getPath().equals(scrollPath)) {
                    return entry.getValue().getDefaultInstance().getDisplayName().getString();
                }
            }
        } catch (Exception e) { /* fall through */ }
        return researchId;
    }
}
