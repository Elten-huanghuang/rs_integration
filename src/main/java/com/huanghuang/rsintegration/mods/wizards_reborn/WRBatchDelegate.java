package com.huanghuang.rsintegration.mods.wizards_reborn;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.batch.IBatchDelegate;
import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.network.AltarBindingRegistry;
import com.huanghuang.rsintegration.network.RSIntegration;
import com.huanghuang.rsintegration.util.Reflect;
import com.refinedmods.refinedstorage.api.network.INetwork;
import net.minecraft.core.BlockPos;
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
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemHandlerHelper;
import net.minecraftforge.items.ItemStackHandler;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;


public final class WRBatchDelegate implements IBatchDelegate {

    // ── Machine type enum ────────────────────────────────────────
    private enum MachineType {
        WISSEN_CRYSTALLIZER,
        ARCANE_ITERATOR,
        ARCANE_WORKBENCH,
        CRYSTAL_RITUAL,
        UNKNOWN
    }

    // ── Shared class refs (loaded via Class.forName) ─────────────
    private static volatile boolean classesLoaded;
    private static volatile Class<?> wissenCrystallizerBEClass;
    private static volatile Class<?> arcaneIteratorBEClass;
    private static volatile Class<?> arcaneWorkbenchBEClass;
    private static volatile Class<?> crystalRitualBEClass;
    private static volatile Class<?> crystalRitualClass;
    private static volatile Class<?> ritualAreaClass;
    private static volatile Class<?> crystalInfusionRecipeClass;
    private static volatile Class<?> runicPedestalBEClass;

    private static void ensureClasses() {
        if (classesLoaded) return;
        try {
            wissenCrystallizerBEClass = Class.forName(
                    "mod.maxbogomol.wizards_reborn.common.block.wissen_crystallizer.WissenCrystallizerBlockEntity");
        } catch (ClassNotFoundException ignored) {}
        try {
            arcaneIteratorBEClass = Class.forName(
                    "mod.maxbogomol.wizards_reborn.common.block.arcane_iterator.ArcaneIteratorBlockEntity");
        } catch (ClassNotFoundException ignored) {}
        try {
            arcaneWorkbenchBEClass = Class.forName(
                    "mod.maxbogomol.wizards_reborn.common.block.arcane_workbench.ArcaneWorkbenchBlockEntity");
        } catch (ClassNotFoundException ignored) {}
        try {
            // Try crystal_ritual first, then crystal as fallback
            try {
                crystalRitualBEClass = Class.forName(
                        "mod.maxbogomol.wizards_reborn.common.block.crystal_ritual.CrystalRitualBlockEntity");
            } catch (ClassNotFoundException e) {
                crystalRitualBEClass = Class.forName(
                        "mod.maxbogomol.wizards_reborn.common.block.crystal.CrystalBlockEntity");
            }
        } catch (ClassNotFoundException ignored) {}
        try {
            crystalRitualClass = Class.forName(
                    "mod.maxbogomol.wizards_reborn.api.crystalritual.CrystalRitual");
            ritualAreaClass = Class.forName(
                    "mod.maxbogomol.wizards_reborn.api.crystalritual.CrystalRitualArea");
        } catch (ClassNotFoundException ignored) {}
        try {
            crystalInfusionRecipeClass = Class.forName(
                    "mod.maxbogomol.wizards_reborn.common.recipe.CrystalInfusionRecipe");
        } catch (ClassNotFoundException ignored) {}
        try {
            runicPedestalBEClass = Class.forName(
                    "mod.maxbogomol.wizards_reborn.common.block.runic_pedestal.RunicPedestalBlockEntity");
        } catch (ClassNotFoundException ignored) {}
        classesLoaded = true;
    }

    // ── Instance state ───────────────────────────────────────────
    private ServerPlayer player;
    private ResourceKey<Level> myDim;
    private BlockPos myPos;
    private Object be;                   // The block entity
    private MachineType machineType = MachineType.UNKNOWN;
    private Recipe<?> recipe;
    private ExtractionLedger ledger;
    private ExtractionLedger sharedLedger;
    private INetwork network;
    private List<Integer> filledSlotIndices;
    private List<Object> filledPedestals;
    private List<?> pedestalRefs;
    // Track exactly what was placed in each slot, so we can detect
    // crystallizer outputs that appear in the same slots as inputs.
    private java.util.Map<Integer, ItemStack> placedInputs;
    // Same for pedestal-based machines (iterator / crystal ritual):
    // key = pedestal BlockEntity, value = the ItemStack we placed on it.
    private java.util.Map<Object, ItemStack> placedPedestalItems;
    private int waitTicks;
    private boolean usingSharedLedger;
    private boolean craftStarted;
    private static final int MAX_WAIT_TICKS = 600;

    // ── IBatchDelegate impl ───────────────────────────────────────

    @Override
    public boolean validateAndInit(ServerPlayer player, ResourceLocation recipeId,
                                   @Nullable ResourceLocation dim, BlockPos pos) {
        ensureClasses();

        ServerLevel level = CraftPacketUtils.resolveLevel(player.server, dim, player);
        if (level == null) {
            player.sendSystemMessage(Component.translatable("rsi.generic.error.dim_not_found"));
            return false;
        }
        this.myDim = level.dimension();
        this.myPos = pos;
        this.player = player;

        if (!level.isLoaded(pos)) level.getChunk(pos);
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity == null) {
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-WR] BE null at bound pos ({},{},{}) dim={}",
                    pos.getX(), pos.getY(), pos.getZ(), dim);
            return false;
        }
        this.be = blockEntity;

        // Determine machine type
        if (wissenCrystallizerBEClass != null && wissenCrystallizerBEClass.isInstance(be)) {
            machineType = MachineType.WISSEN_CRYSTALLIZER;
        } else if (arcaneIteratorBEClass != null && arcaneIteratorBEClass.isInstance(be)) {
            machineType = MachineType.ARCANE_ITERATOR;
        } else if (arcaneWorkbenchBEClass != null && arcaneWorkbenchBEClass.isInstance(be)) {
            machineType = MachineType.ARCANE_WORKBENCH;
        } else if (crystalRitualBEClass != null && crystalRitualBEClass.isInstance(be)) {
            machineType = MachineType.CRYSTAL_RITUAL;
        } else {
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-WR] Unsupported BE type: {}", be.getClass().getName());
            return false;
        }

        // Verify the recipe's machine sub-type matches the actual machine.
        // e.g. recipe "wizards_reborn:wissen_crystallizer/earth_crystal_seed"
        // must go to a WISSEN_CRYSTALLIZER, not an ARCANE_WORKBENCH.
        MachineType expected = expectedMachineType(recipeId);
        if (expected != MachineType.UNKNOWN && expected != machineType) {
            RSIntegrationMod.LOGGER.debug("[RSI-Batch-WR] Type mismatch: recipe expects {}, machine is {}",
                    expected, machineType);
            return false;
        }

        Recipe<?> foundRecipe = level.getRecipeManager().byKey(recipeId).orElse(null);
        if (foundRecipe == null) {
            player.sendSystemMessage(Component.translatable("rsi.generic.error.recipe_not_found", recipeId.toString()));
            return false;
        }
        this.recipe = foundRecipe;

        // Resolve network early so validateIdle can access it for crystal rituals
        this.network = CraftPacketUtils.resolveNetworkForCraft(player, myDim, myPos);

        // Validate idle state per machine type
        if (!validateIdle(player, level)) return false;

        this.waitTicks = 0;

        RSIntegrationMod.LOGGER.debug("[RSI-Batch-WR] validateAndInit OK: recipe={} type={}", recipeId, machineType);
        return true;
    }

    /**
     * Extract the expected MachineType from a recipe ID.
     * Recipe IDs use the format "namespace:machine_sub_type/recipe_name".
     */
    private static MachineType expectedMachineType(ResourceLocation recipeId) {
        String path = recipeId.getPath();
        int slash = path.indexOf('/');
        if (slash <= 0) return MachineType.UNKNOWN;
        String subType = path.substring(0, slash).toLowerCase(java.util.Locale.ROOT);
        if (subType.contains("crystallizer")) return MachineType.WISSEN_CRYSTALLIZER;
        if (subType.contains("workbench")) return MachineType.ARCANE_WORKBENCH;
        if (subType.contains("iterator")) return MachineType.ARCANE_ITERATOR;
        if (subType.contains("crystal")) return MachineType.CRYSTAL_RITUAL;
        return MachineType.UNKNOWN;
    }

    private boolean validateIdle(ServerPlayer player, ServerLevel level) {
        switch (machineType) {
            case WISSEN_CRYSTALLIZER: {
                // Guard: don't touch a crystallizer mid-craft.
                if (isCrystallizerCrafting()) {
                    player.sendSystemMessage(Component.translatable("rsi.wr.error.crystallizer_busy"));
                    return false;
                }
                int size = getContainerSize(be);
                if (size < 0) {
                    RSIntegrationMod.LOGGER.warn("[RSI-Batch-WR] Cannot determine crystallizer container size for {}",
                            be.getClass().getName());
                    return false;
                }
                // Auto-collect leftover outputs from ALL slots before checking inputs.
                // Some crystallizer recipes produce multiple outputs in different slots.
                int collectedCount = 0;
                for (int i = 0; i < size; i++) {
                    ItemStack outStack = getContainerItem(be, i);
                    if (!outStack.isEmpty()) {
                        setContainerItem(be, i, ItemStack.EMPTY);
                        collectedCount++;
                        if (network != null) {
                            network.insertItem(outStack, outStack.getCount(),
                                    com.refinedmods.refinedstorage.api.util.Action.PERFORM);
                        } else if (player != null) {
                            ItemHandlerHelper.giveItemToPlayer(player, outStack);
                        }
                    }
                }
                if (collectedCount > 0) {
                    RSIntegrationMod.LOGGER.debug("[RSI-Batch-WR] validateIdle collected {} leftover stacks from crystallizer", collectedCount);
                }
                break;
            }
            case ARCANE_ITERATOR: {
                try {
                    pedestalRefs = (List<?>) getMethod(be.getClass(), "getPedestals").invoke(be);
                    if (pedestalRefs != null) {
                        for (int i = 0; i < pedestalRefs.size(); i++) {
                            ItemStack stack = getContainerItem(pedestalRefs.get(i), 0);
                            if (!stack.isEmpty()) {
                                player.sendSystemMessage(Component.translatable(
                                        "rsi.wr.error.pedestal_not_empty", i));
                                return false;
                            }
                        }
                    }
                } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI] Reflection probe failed", e); }
                break;
            }
            case ARCANE_WORKBENCH: {
                ItemStackHandler handler = getWorkbenchItemHandler(be);
                if (handler == null) break;
                int slots = handler.getSlots();
                // Only check input slots (0..slots-2), skip probable output slot
                for (int i = 0; i < slots - 1; i++) {
                    if (!handler.getStackInSlot(i).isEmpty()) {
                        player.sendSystemMessage(Component.translatable(
                                "rsi.wr.error.workbench_not_empty", i));
                        return false;
                    }
                }
                break;
            }
            case CRYSTAL_RITUAL: {
                if (!validateCrystalSetup(player, level)) return false;
                break;
            }
            default:
                return false;
        }
        return true;
    }

    /** Check whether the crystallizer is actively processing a craft. */
    private boolean isCrystallizerCrafting() {
        if (be == null) return false;
        try {
            java.lang.reflect.Field f = Reflect.findField(be.getClass(), "startCraft").orElse(null);
            if (f != null) {
                f.setAccessible(true);
                if ((boolean) f.get(be)) return true;
            }
        } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI] Reflection probe failed", e); }
        try {
            java.lang.reflect.Field f = Reflect.findField(be.getClass(), "wissenInCraft").orElse(null);
            if (f != null) {
                f.setAccessible(true);
                if (f.getInt(be) > 0) return true;
            }
        } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI] Reflection probe failed", e); }
        return false;
    }

    /**
     * Validate the crystal ritual multi-block structure and state.
     * Mirrors betterjei's findCrystalSetup: checks startRitual, cooldown,
     * crystal presence, runic pedestal below with runic plate, and ritual ID.
     */
    private boolean validateCrystalSetup(ServerPlayer player, ServerLevel level) {
        // [Step 1] Not already running a ritual
        try {
            java.lang.reflect.Field f = Reflect.findField(be.getClass(), "startRitual").orElse(null);
            if (f != null) {
                f.setAccessible(true);
                if (f.getBoolean(be)) {
                    RSIntegrationMod.LOGGER.warn("[RSI-Batch-WR] [step 1/6] startRitual is true — ritual already running");
                    player.sendSystemMessage(Component.translatable("rsi.wr.error.ritual_already_running"));
                    return false;
                }
            }
        } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI-Batch-WR] [step 1/6] startRitual check exception", e); }
        RSIntegrationMod.LOGGER.debug("[RSI-Batch-WR] [step 1/6] startRitual OK");

        // [Step 2] Cooldown expired
        try {
            java.lang.reflect.Field f = Reflect.findField(be.getClass(), "cooldown").orElse(null);
            if (f != null) {
                f.setAccessible(true);
                int cd = f.getInt(be);
                if (cd > 0) {
                    RSIntegrationMod.LOGGER.warn("[RSI-Batch-WR] [step 2/6] cooldown={} — still cooling down", cd);
                    player.sendSystemMessage(Component.translatable("rsi.wr.error.crystal_cooldown"));
                    return false;
                }
            }
        } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI-Batch-WR] [step 2/6] cooldown check exception", e); }
        RSIntegrationMod.LOGGER.debug("[RSI-Batch-WR] [step 2/6] cooldown OK");

        // [Step 3] Crystal must already be in the crystal block (placed by player).
        //    RS does NOT supply the crystal — the player chooses the correct
        //    crystal type manually.  For CrystalInfusionRecipe the crystal is
        //    not even listed in recipe ingredients.
        try {
            Object crystalItem = getMethod(be.getClass(), "getCrystalItem").invoke(be);
            if (crystalItem == null || ((ItemStack) crystalItem).isEmpty()) {
                RSIntegrationMod.LOGGER.warn("[RSI-Batch-WR] [step 3/6] getCrystalItem() returned null/empty — no crystal in block at {}", myPos);
                player.sendSystemMessage(Component.translatable("rsi.wr.error.crystal_ritual_no_crystal"));
                return false;
            }
            RSIntegrationMod.LOGGER.debug("[RSI-Batch-WR] [step 3/6] crystal item present: {}",
                    ((ItemStack) crystalItem).getHoverName().getString());
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-WR] [step 3/6] Failed to read crystal item from CrystalBlockEntity", e);
            player.sendSystemMessage(Component.translatable("rsi.wr.error.crystal_ritual_no_crystal"));
            return false;
        }

        // [Step 4] Block below must be RunicPedestalBlockEntity
        BlockPos below = myPos.below();
        BlockEntity belowBE = level.getBlockEntity(below);
        if (belowBE == null) {
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-WR] [step 4/6] Block below at {} is null (no BE)", below);
            player.sendSystemMessage(Component.translatable("rsi.wr.error.runic_pedestal_missing"));
            return false;
        }
        if (runicPedestalBEClass == null) {
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-WR] [step 4/6] runicPedestalBEClass is null (not loaded)");
            player.sendSystemMessage(Component.translatable("rsi.wr.error.runic_pedestal_missing"));
            return false;
        }
        if (!runicPedestalBEClass.isInstance(belowBE)) {
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-WR] [step 4/6] Block below at {} is {} (expected RunicPedestalBlockEntity)",
                    below, belowBE.getClass().getName());
            player.sendSystemMessage(Component.translatable("rsi.wr.error.runic_pedestal_missing"));
            return false;
        }
        RSIntegrationMod.LOGGER.debug("[RSI-Batch-WR] [step 4/6] runic pedestal present at {}", below);

        // [Step 5] Runic pedestal must have a runic plate
        try {
            java.lang.reflect.Method hasPlate = Reflect.findMethod(
                    runicPedestalBEClass, "hasRunicPlate", new Class<?>[0]);
            if (hasPlate != null) {
                if (!(boolean) hasPlate.invoke(belowBE)) {
                    RSIntegrationMod.LOGGER.warn("[RSI-Batch-WR] [step 5/6] hasRunicPlate() returned false");
                    player.sendSystemMessage(Component.translatable("rsi.wr.error.no_runic_plate"));
                    return false;
                }
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-Batch-WR] [step 5/6] hasRunicPlate check exception", e);
        }
        RSIntegrationMod.LOGGER.debug("[RSI-Batch-WR] [step 5/6] hasRunicPlate OK");

        // [Step 6] Ritual must be crystal_infusion type
        try {
            java.lang.reflect.Method getRitual = Reflect.findMethod(
                    runicPedestalBEClass, "getCrystalRitual", new Class<?>[0]);
            if (getRitual != null) {
                Object ritual = getRitual.invoke(belowBE);
                if (ritual == null) {
                    RSIntegrationMod.LOGGER.warn("[RSI-Batch-WR] [step 6/6] getCrystalRitual() returned null");
                    player.sendSystemMessage(Component.translatable("rsi.wr.error.ritual_id_mismatch"));
                    return false;
                }
                RSIntegrationMod.LOGGER.debug("[RSI-Batch-WR] [step 6/6] ritual class={}", ritual.getClass().getName());
                java.lang.reflect.Method getId = Reflect.findMethod(
                        ritual.getClass(), "getId", new Class<?>[0]);
                if (getId != null) {
                    String id = (String) getId.invoke(ritual);
                    RSIntegrationMod.LOGGER.debug("[RSI-Batch-WR] [step 6/6] ritual id='{}'", id);
                    if (!"wizards_reborn:crystal_infusion".equals(id)) {
                        RSIntegrationMod.LOGGER.warn("[RSI-Batch-WR] [step 6/6] Ritual type mismatch: expected crystal_infusion, got '{}'",
                                id);
                        player.sendSystemMessage(Component.translatable("rsi.wr.error.ritual_id_mismatch"));
                        return false;
                    }
                } else {
                    RSIntegrationMod.LOGGER.debug("[RSI-Batch-WR] [step 6/6] getId method not found on {}", ritual.getClass().getName());
                }
            } else {
                RSIntegrationMod.LOGGER.debug("[RSI-Batch-WR] [step 6/6] getCrystalRitual method not found on {}", runicPedestalBEClass.getName());
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-Batch-WR] [step 6/6] ritual ID check exception", e);
        }

        RSIntegrationMod.LOGGER.info("[RSI-Batch-WR] Crystal setup validation PASSED at {}", myPos);
        return true;
    }


    /**
     * Extract ingredients from a WR recipe WITHOUT crystal filtering.
     * Uses the same multi-strategy approach as CraftPacketUtils.extractIngredients
     * but skips filterWRCrystal so the crystal item remains in the list.
     */
    @Nullable
    private static List<Ingredient> getUnfilteredIngredients(Object recipe) {
        // Strategy 1: getIngredients() method (most WR recipes)
        try {
            @SuppressWarnings("unchecked")
            List<Ingredient> result = (List<Ingredient>) recipe.getClass()
                    .getMethod("getIngredients").invoke(recipe);
            if (result != null && !result.isEmpty()) return result;
        } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI] Reflection probe failed", e); }

        // Strategy 2: scan fields for "ingredients" List<Ingredient>
        Class<?> scan = recipe.getClass();
        while (scan != null && scan != Object.class) {
            for (java.lang.reflect.Field field : scan.getDeclaredFields()) {
                if (field.getName().equals("ingredients")
                        && List.class.isAssignableFrom(field.getType())) {
                    field.setAccessible(true);
                    try {
                        List<?> list = (List<?>) field.get(recipe);
                        if (!list.isEmpty() && list.get(0) instanceof Ingredient) {
                            @SuppressWarnings("unchecked")
                            List<Ingredient> result = (List<Ingredient>) list;
                            return result;
                        }
                    } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI] Reflection probe failed", e); }
                }
            }
            scan = scan.getSuperclass();
        }

        // Strategy 3: try getInputs / getInputItems
        for (String name : new String[]{"getInputs", "getInputItems"}) {
            try {
                @SuppressWarnings("unchecked")
                List<Ingredient> result = (List<Ingredient>) recipe.getClass()
                        .getMethod(name).invoke(recipe);
                if (result != null && !result.isEmpty()) return result;
            } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI] Reflection probe failed", e); }
        }

        // Strategy 4: scan all List<Ingredient> fields
        scan = recipe.getClass();
        while (scan != null && scan != Object.class) {
            for (java.lang.reflect.Field field : scan.getDeclaredFields()) {
                if (!List.class.isAssignableFrom(field.getType())) continue;
                field.setAccessible(true);
                try {
                    List<?> list = (List<?>) field.get(recipe);
                    if (list != null && !list.isEmpty() && list.get(0) instanceof Ingredient) {
                        @SuppressWarnings("unchecked")
                        List<Ingredient> result = (List<Ingredient>) list;
                        return result;
                    }
                } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI] Reflection probe failed", e); }
            }
            scan = scan.getSuperclass();
        }

        return null;
    }

    @Override
    public boolean tryStartSingleCraft(ServerPlayer player) {
        this.player = player;
        this.ledger = new ExtractionLedger();
        this.network = CraftPacketUtils.resolveNetworkForCraft(player, myDim, myPos);
        this.filledSlotIndices = new ArrayList<>();
        this.filledPedestals = new ArrayList<>();
        this.waitTicks = 0;

        // CRYSTAL_RITUAL uses raw unfiltered ingredients (matching betterjei):
        // the crystal ingredient at index 0 may be empty (any-crystal catalyst)
        // or non-empty (specific crystal required as material on pedestal).
        // Other machines go through filterWRCrystal to strip crystal items.
        List<Ingredient> ingredients;
        if (machineType == MachineType.CRYSTAL_RITUAL) {
            ingredients = getUnfilteredIngredients(recipe);
        } else {
            ingredients = CraftPacketUtils.extractIngredients(recipe);
        }
        if (ingredients == null || ingredients.isEmpty()) {
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-WR] Failed to get ingredients from recipe: {}", recipe.getId());
            return false;
        }

        boolean ok;
        switch (machineType) {
            case WISSEN_CRYSTALLIZER:
                ok = tryStartWissenCrystallizer(player, ingredients);
                break;
            case ARCANE_ITERATOR:
                ok = tryStartArcaneIterator(player, ingredients);
                break;
            case ARCANE_WORKBENCH:
                ok = tryStartArcaneWorkbench(player, ingredients);
                break;
            case CRYSTAL_RITUAL:
                ok = tryStartCrystalRitual(player, ingredients);
                break;
            default:
                ok = false;
        }
        if (!ok) {
            ledger = null;
            // Keep network alive so onBatchFailed can return items to RS
        }
        return ok;
    }

    // ── Wissen Crystallizer ──────────────────────────────────────

    private boolean tryStartWissenCrystallizer(ServerPlayer player, List<Ingredient> ingredients) {
        int totalSlots = getContainerSize(be);
        if (totalSlots <= 0 || ingredients.size() > totalSlots) return false;

        // Phase 1: reserve all ingredients + validate slots
        List<ItemStack> templates = new ArrayList<>();
        this.placedInputs = new java.util.HashMap<>();
        for (int i = 0; i < ingredients.size(); i++) {
            Ingredient ing = ingredients.get(i);
            if (ing.getItems().length == 0) {
                templates.add(ItemStack.EMPTY);
                continue;
            }

            ItemStack existing = getContainerItem(be, i);
            if (!existing.isEmpty()) {
                if (ing.test(existing)) {
                    templates.add(ItemStack.EMPTY);
                    continue;
                }
                return false;
            }

            ItemStack taken = CraftPacketUtils.ensureMaterialAvailable(player, myDim, myPos, ing, 1, ledger);
            if (taken.isEmpty()) return false;
            templates.add(taken);
            filledSlotIndices.add(i);
        }

        // Phase 2: check wissen before extracting items from RS
        if (!checkWissen()) return false;

        // Phase 3: commit all extractions atomically
        if (!ledger.commit(network, player)) return false;

        // Phase 4: place items, tracking what we placed for completion detection
        for (int i = 0; i < templates.size(); i++) {
            ItemStack taken = templates.get(i);
            if (!taken.isEmpty()) {
                setContainerItem(be, i, taken);
                placedInputs.put(i, taken.copy());
            }
        }

        craftStarted = true;
        try {
            getMethod(be.getClass(), "wissenWandFunction").invoke(be);
            syncBlockEntity(be);
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-WR] wissenWandFunction invoke failed, rolling back", e);
            clearFilledSlots();

            return false;
        }

        return true;
    }

    // ── Arcane Iterator ──────────────────────────────────────────

    private boolean tryStartArcaneIterator(ServerPlayer player, List<Ingredient> ingredients) {
        List<?> pedestals;
        try {
            pedestals = (List<?>) getMethod(be.getClass(), "getPedestals").invoke(be);
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-WR] Failed to get pedestals from ArcaneIterator", e);
            return false;
        }
        this.pedestalRefs = pedestals;

        if (pedestals.size() < ingredients.size()) return false;

        // Phase 1: reserve all ingredients
        List<ItemStack> templates = new ArrayList<>();
        for (int i = 0; i < ingredients.size(); i++) {
            Ingredient ing = ingredients.get(i);
            if (ing.getItems().length == 0) {
                templates.add(ItemStack.EMPTY);
                continue;
            }
            ItemStack taken = CraftPacketUtils.ensureMaterialAvailable(player, myDim, myPos, ing, 1, ledger);
            if (taken.isEmpty()) return false;
            templates.add(taken);
        }

        // Phase 2: check wissen before extracting items from RS
        if (!checkWissen()) return false;

        // Phase 3: commit
        if (!ledger.commit(network, player)) return false;

        // Phase 4: place items
        this.placedPedestalItems = new java.util.HashMap<>();
        for (int i = 0; i < templates.size(); i++) {
            ItemStack taken = templates.get(i);
            if (taken.isEmpty()) continue;

            try {
                Object ped = pedestals.get(i);
                ItemStack existing = getContainerItem(ped, 0);
                if (!existing.isEmpty()) {
                    ItemHandlerHelper.giveItemToPlayer(player, existing.copy());
                }
                setContainerItem(ped, 0, taken);
                filledPedestals.add(ped);
                placedPedestalItems.put(ped, taken.copy());
                syncBlockEntity(ped);
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.warn("[RSI-Batch-WR] Failed to place item on ArcaneIterator pedestal {}: {}", i, e.getMessage());
                clearFilledPedestals();

                return false;
            }
        }

        craftStarted = true;
        try {
            getMethod(be.getClass(), "wissenWandFunction").invoke(be);
            syncBlockEntity(be);
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-WR] wissenWandFunction invoke failed, rolling back", e);
            clearFilledPedestals();

            return false;
        }

        return true;
    }

    // ── Arcane Workbench ─────────────────────────────────────────

    private boolean tryStartArcaneWorkbench(ServerPlayer player, List<Ingredient> ingredients) {
        ItemStackHandler itemHandler = getWorkbenchItemHandler(be);
        if (itemHandler == null) {
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-WR] Failed to get itemHandler from ArcaneWorkbench");
            return false;
        }

        int totalSlots = itemHandler.getSlots();
        if (ingredients.size() > totalSlots) return false;

        // Phase 1: reserve all ingredients + validate slots
        List<ItemStack> templates = new ArrayList<>();
        for (int i = 0; i < ingredients.size(); i++) {
            Ingredient ing = ingredients.get(i);
            if (ing.getItems().length == 0) {
                templates.add(ItemStack.EMPTY);
                continue;
            }

            ItemStack existing = itemHandler.getStackInSlot(i);
            if (!existing.isEmpty()) {
                if (ing.test(existing)) {
                    templates.add(ItemStack.EMPTY);
                    continue;
                }
                return false;
            }

            ItemStack taken = CraftPacketUtils.ensureMaterialAvailable(player, myDim, myPos, ing, 1, ledger);
            if (taken.isEmpty()) return false;
            templates.add(taken);
            filledSlotIndices.add(i);
        }

        // Phase 2: check wissen before extracting items from RS
        if (!checkWissen()) return false;

        // Phase 3: commit
        if (!ledger.commit(network, player)) return false;

        // Phase 4: place items
        for (int i = 0; i < templates.size(); i++) {
            ItemStack taken = templates.get(i);
            if (!taken.isEmpty()) {
                itemHandler.setStackInSlot(i, taken);
            }
        }

        craftStarted = true;
        try {
            getMethod(be.getClass(), "wissenWandFunction").invoke(be);
            syncBlockEntity(be);
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-WR] wissenWandFunction invoke failed, rolling back", e);
            clearFilledSlotsForHandler(itemHandler);

            return false;
        }

        return true;
    }

    // ── Crystal Ritual ───────────────────────────────────────────

    private boolean tryStartCrystalRitual(ServerPlayer player, List<Ingredient> ingredients) {
        Object ritual = extractRitual(recipe);
        if (ritual == null) {
            try {
                ritual = getMethod(be.getClass(), "getCrystalRitual").invoke(be);
            } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI] Reflection probe failed", e); }
        }
        if (ritual == null) {
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-WR] Failed to extract ritual data");
            return false;
        }

        Object area;
        try {
            area = getMethod(ritual.getClass(), "getArea", be.getClass()).invoke(ritual, be);
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-WR] Failed to get ritual area", e);
            return false;
        }

        List<?> pedestals;
        try {
            ServerLevel level = player.serverLevel();
            pedestals = (List<?>) getMethod(crystalRitualClass, "getPedestalsWithArea", Level.class, BlockPos.class, ritualAreaClass)
                    .invoke(null, level, myPos, area);
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-WR] Failed to find arcane pedestals for crystal ritual", e);
            return false;
        }

        if (pedestals.size() < ingredients.size()) return false;

        // Phase 1: reserve all ingredients
        List<ItemStack> templates = new ArrayList<>();
        for (int i = 0; i < ingredients.size(); i++) {
            Ingredient ing = ingredients.get(i);
            if (ing.getItems().length == 0) {
                templates.add(ItemStack.EMPTY);
                continue;
            }

            Object ped = pedestals.get(i);
            ItemStack existing = getContainerItem(ped, 0);
            if (!existing.isEmpty() && ing.test(existing)) {
                templates.add(ItemStack.EMPTY);
                continue;
            }

            ItemStack taken = CraftPacketUtils.ensureMaterialAvailable(player, myDim, myPos, ing, 1, ledger);
            if (taken.isEmpty()) return false;
            templates.add(taken);
        }

        // Phase 2: commit
        if (!ledger.commit(network, player)) return false;

        // Phase 3: place items
        this.pedestalRefs = pedestals;
        this.placedPedestalItems = new java.util.HashMap<>();

        for (int i = 0; i < templates.size(); i++) {
            ItemStack taken = templates.get(i);
            if (taken.isEmpty()) continue;

            Object ped = pedestals.get(i);
            ItemStack existing = getContainerItem(ped, 0);
            if (!existing.isEmpty()) {
                ItemHandlerHelper.giveItemToPlayer(player, existing.copy());
            }

            try {
                setContainerItem(ped, 0, taken);
                filledPedestals.add(ped);
                placedPedestalItems.put(ped, taken.copy());
                syncBlockEntity(ped);
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.warn("[RSI-Batch-WR] Failed to place item on crystal pedestal {}: {}", i, e.getMessage());
                clearFilledPedestals();
    
                return false;
            }
        }

        craftStarted = true;
        try {
            getMethod(be.getClass(), "wissenWandFunction").invoke(be);
            syncBlockEntity(be);
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-WR] Failed to invoke wissenWandFunction on crystal block", e);
            clearFilledPedestals();

            return false;
        }

        return true;
    }

    // ── Chain support: pre-reserved materials ────────────────────

    @Override
    @Nullable
    public List<IngredientSpec> getRequiredMaterials() {
        if (recipe == null) return null;
        // CRYSTAL_RITUAL: use raw ingredients (matching betterjei).
        // The crystal ingredient at index 0 is included if non-empty.
        // Other machines: filter out crystal items via extractIngredients.
        List<Ingredient> ingredients;
        if (machineType == MachineType.CRYSTAL_RITUAL) {
            ingredients = getUnfilteredIngredients(recipe);
        } else {
            ingredients = CraftPacketUtils.extractIngredients(recipe);
        }
        if (ingredients == null || ingredients.isEmpty()) return null;
        List<IngredientSpec> specs = new ArrayList<>();
        for (Ingredient ing : ingredients) {
            if (!ing.isEmpty()) specs.add(new IngredientSpec(ing, 1));
        }
        return specs.isEmpty() ? null : specs;
    }

    @Override
    public boolean tryStartWithMaterials(ServerPlayer player, List<ItemStack> materials,
                                         ExtractionLedger sharedLedger) {
        this.player = player;
        this.sharedLedger = sharedLedger;
        this.usingSharedLedger = true;
        this.filledSlotIndices = new ArrayList<>();
        this.filledPedestals = new ArrayList<>();
        this.waitTicks = 0;
        this.network = CraftPacketUtils.resolveNetworkForCraft(player, myDim, myPos);

        boolean ok;
        switch (machineType) {
            case WISSEN_CRYSTALLIZER:
                ok = startWissenWithMaterials(player, materials);
                break;
            case ARCANE_ITERATOR:
                ok = startIteratorWithMaterials(player, materials);
                break;
            case ARCANE_WORKBENCH:
                ok = startWorkbenchWithMaterials(player, materials);
                break;
            case CRYSTAL_RITUAL:
                ok = startCrystalRitualWithMaterials(player, materials);
                break;
            default:
                ok = false;
        }
        return ok;
    }

    private boolean startWissenWithMaterials(ServerPlayer player, List<ItemStack> materials) {
        if (!checkWissen()) return false;
        int totalSlots = getContainerSize(be);
        if (totalSlots <= 0) {
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-WR] Cannot determine crystallizer container size for {}",
                    be.getClass().getName());
            player.sendSystemMessage(Component.translatable("rsi.generic.error.machine_not_found"));
            return false;
        }
        if (materials.size() > totalSlots) {
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-WR] Not enough slots: need {}, have {}",
                    materials.size(), totalSlots);
            player.sendSystemMessage(Component.translatable(
                    "rsi.wr.error.slots_insufficient", materials.size(), totalSlots));
            return false;
        }

        // Place items in slots
        this.placedInputs = new java.util.HashMap<>();
        for (int i = 0; i < materials.size(); i++) {
            ItemStack stack = materials.get(i);
            if (stack.isEmpty()) continue;
            ItemStack existing = getContainerItem(be, i);
            if (!existing.isEmpty()) {
                if (ItemStack.isSameItemSameTags(stack, existing)) continue;
                player.sendSystemMessage(Component.translatable(
                        "rsi.wr.error.slot_occupied", i, existing.getDisplayName().getString()));
                return false;
            }
            setContainerItem(be, i, stack);
            filledSlotIndices.add(i);
            placedInputs.put(i, stack.copy());
        }

        craftStarted = true;
        try {
            getMethod(be.getClass(), "wissenWandFunction").invoke(be);
            syncBlockEntity(be);
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-WR] wissenWandFunction invoke failed, rolling back", e);
            clearFilledSlots();
            return false;
        }
        return true;
    }

    private boolean startIteratorWithMaterials(ServerPlayer player, List<ItemStack> materials) {
        if (!checkWissen()) return false;
        List<?> pedestals;
        try {
            pedestals = (List<?>) getMethod(be.getClass(), "getPedestals").invoke(be);
        } catch (Exception e) {
            return false;
        }
        this.pedestalRefs = pedestals;
        if (pedestals.size() < materials.size()) return false;

        this.placedPedestalItems = new java.util.HashMap<>();
        for (int i = 0; i < materials.size(); i++) {
            ItemStack stack = materials.get(i);
            if (stack.isEmpty()) continue;
            try {
                Object ped = pedestals.get(i);
                ItemStack existing = getContainerItem(ped, 0);
                if (!existing.isEmpty()) {
                    ItemHandlerHelper.giveItemToPlayer(player, existing.copy());
                }
                setContainerItem(ped, 0, stack);
                filledPedestals.add(ped);
                placedPedestalItems.put(ped, stack.copy());
                syncBlockEntity(ped);
            } catch (Exception e) {
                clearFilledPedestals();
                return false;
            }
        }

        craftStarted = true;
        try {
            getMethod(be.getClass(), "wissenWandFunction").invoke(be);
            syncBlockEntity(be);
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-WR] wissenWandFunction invoke failed, rolling back", e);
            clearFilledPedestals();
            return false;
        }
        return true;
    }

    private boolean startWorkbenchWithMaterials(ServerPlayer player, List<ItemStack> materials) {
        if (!checkWissen()) return false;
        ItemStackHandler itemHandler = getWorkbenchItemHandler(be);
        if (itemHandler == null) return false;

        // Map materials back to their original recipe slot indices.
        // getRequiredMaterials() filters empty ingredients, so materials are
        // compacted; we must re-expand them to match the recipe's slot layout.
        List<Ingredient> recipeIngredients = CraftPacketUtils.extractIngredients(recipe);
        if (recipeIngredients == null) return false;

        int matIdx = 0;
        for (int slot = 0; slot < recipeIngredients.size() && matIdx < materials.size(); slot++) {
            Ingredient ing = recipeIngredients.get(slot);
            if (ing.isEmpty()) continue;

            ItemStack stack = materials.get(matIdx);
            if (stack.isEmpty()) { matIdx++; continue; }

            ItemStack existing = itemHandler.getStackInSlot(slot);
            if (!existing.isEmpty()) {
                if (ItemStack.isSameItemSameTags(stack, existing)) { matIdx++; continue; }
                return false;
            }
            itemHandler.setStackInSlot(slot, stack);
            filledSlotIndices.add(slot);
            matIdx++;
        }

        craftStarted = true;
        try {
            getMethod(be.getClass(), "wissenWandFunction").invoke(be);
            syncBlockEntity(be);
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-WR] wissenWandFunction invoke failed, rolling back", e);
            clearFilledSlotsForHandler(itemHandler);
            return false;
        }
        return true;
    }

    private boolean startCrystalRitualWithMaterials(ServerPlayer player, List<ItemStack> materials) {
        Object ritual = extractRitual(recipe);
        if (ritual == null) {
            try {
                ritual = getMethod(be.getClass(), "getCrystalRitual").invoke(be);
            } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI] Reflection probe failed", e); }
        }
        if (ritual == null) return false;

        Object area;
        try {
            area = getMethod(ritual.getClass(), "getArea", be.getClass()).invoke(ritual, be);
        } catch (Exception e) {
            return false;
        }

        List<?> pedestals;
        try {
            ServerLevel level = player.serverLevel();
            pedestals = (List<?>) getMethod(crystalRitualClass, "getPedestalsWithArea", Level.class, BlockPos.class, ritualAreaClass)
                    .invoke(null, level, myPos, area);
        } catch (Exception e) {
            return false;
        }

        // Re-expand compacted materials to match recipe ingredient positions.
        // getRequiredMaterials() filters empty ingredients, so materials may
        // be compacted. We must match them to the recipe's actual ingredient list.
        List<Ingredient> recipeIngredients = getUnfilteredIngredients(recipe);
        if (recipeIngredients == null || pedestals.size() < recipeIngredients.size()) return false;

        this.pedestalRefs = pedestals;
        this.placedPedestalItems = new java.util.HashMap<>();
        int matIdx = 0;
        for (int slot = 0; slot < recipeIngredients.size() && matIdx < materials.size(); slot++) {
            Ingredient ing = recipeIngredients.get(slot);
            if (ing.isEmpty()) continue; // skip empty (e.g. crystal catalyst slot)

            ItemStack stack = materials.get(matIdx);
            matIdx++;
            if (stack.isEmpty()) continue;

            Object ped = pedestals.get(slot);
            ItemStack existing = getContainerItem(ped, 0);
            if (!existing.isEmpty()) {
                ItemHandlerHelper.giveItemToPlayer(player, existing.copy());
            }
            try {
                setContainerItem(ped, 0, stack);
                filledPedestals.add(ped);
                placedPedestalItems.put(ped, stack.copy());
                syncBlockEntity(ped);
            } catch (Exception e) {
                clearFilledPedestals();
                return false;
            }
        }

        craftStarted = true;
        try {
            getMethod(be.getClass(), "wissenWandFunction").invoke(be);
            syncBlockEntity(be);
        } catch (Exception e) {
            clearFilledPedestals();
            return false;
        }
        return true;
    }

    // ── Completion detection ─────────────────────────────────────

    @Override
    public boolean isCraftComplete(ServerLevel level) {
        if (!craftStarted) return false;
        waitTicks++;
        if (waitTicks > MAX_WAIT_TICKS) return true; // timeout

        switch (machineType) {
            case WISSEN_CRYSTALLIZER:
                // Crystallizer outputs into the SAME slots as inputs —
                // check that slot contents changed from what we placed.
                if (placedInputs != null) {
                    for (java.util.Map.Entry<Integer, ItemStack> e : placedInputs.entrySet()) {
                        int idx = e.getKey();
                        ItemStack current = getContainerItem(be, idx);
                        if (!current.isEmpty() && ItemStack.isSameItemSameTags(current, e.getValue())) {
                            return false; // still contains our input, craft not done
                        }
                    }
                    return true;
                }
                // fallback: check slots became empty
                for (int idx : filledSlotIndices) {
                    if (!getContainerItem(be, idx).isEmpty()) return false;
                }
                return true;

            case ARCANE_WORKBENCH:
                for (int idx : filledSlotIndices) {
                    ItemStackHandler handler = getWorkbenchItemHandler(be);
                    if (handler == null) return false;
                    if (!handler.getStackInSlot(idx).isEmpty()) return false;
                }
                return true;

            case ARCANE_ITERATOR:
            case CRYSTAL_RITUAL:
                for (Object ped : filledPedestals) {
                    syncBlockEntity(ped);
                }
                // Check that every pedestal we placed an item on no longer
                // holds that exact input.  If the craft produces output on
                // the same pedestal, the old check "is it empty?" would wait
                // until timeout; now we detect the item *changed*.
                if (placedPedestalItems != null) {
                    for (java.util.Map.Entry<Object, ItemStack> e : placedPedestalItems.entrySet()) {
                        ItemStack current = getContainerItem(e.getKey(), 0);
                        if (!current.isEmpty() && ItemStack.isSameItemSameTags(current, e.getValue()))
                            return false;
                    }
                    return true;
                }
                // fallback: original empty-slot check
                for (Object ped : filledPedestals) {
                    if (!getContainerItem(ped, 0).isEmpty()) return false;
                }
                return true;

            default:
                return false;
        }
    }

    @Override
    public ItemStack collectResult(ServerPlayer player) {
        // First try to read output from machine slots
        ItemStack fromMachine = ItemStack.EMPTY;
        switch (machineType) {
            case WISSEN_CRYSTALLIZER: {
                // The crystallizer outputs into the same slots as inputs.
                // Scan ALL slots; skip those still holding unchanged original input.
                int size = getContainerSize(be);
                if (size > 0) {
                    for (int i = 0; i < size; i++) {
                        ItemStack stack = getContainerItem(be, i);
                        if (stack.isEmpty()) continue;
                        // Skip if this is still the unchanged input we placed
                        if (placedInputs != null) {
                            ItemStack original = placedInputs.get(i);
                            if (original != null && ItemStack.isSameItemSameTags(stack, original))
                                continue;
                        }
                        setContainerItem(be, i, ItemStack.EMPTY);
                        if (fromMachine.isEmpty()) {
                            fromMachine = stack;
                        } else {
                            // Multiple outputs — insert extras directly into RS
                            if (network != null) {
                                network.insertItem(stack, stack.getCount(),
                                        com.refinedmods.refinedstorage.api.util.Action.PERFORM);
                            } else {
                                ItemHandlerHelper.giveItemToPlayer(player, stack);
                            }
                        }
                    }
                }
                break;
            }
            case ARCANE_ITERATOR: {
                // The output appears on an arcane_pedestal (typically the bottom one),
                // NOT inside the Iterator block entity. Scan all known pedestals.
                if (pedestalRefs != null) {
                    for (Object ped : pedestalRefs) {
                        ItemStack stack = getContainerItem(ped, 0);
                        if (stack.isEmpty()) continue;
                        // Skip only if this pedestal still holds the unchanged
                        // input we placed.  If the item changed, it's an output —
                        // even if the pedestal is in filledPedestals.
                        if (placedPedestalItems != null) {
                            ItemStack original = placedPedestalItems.get(ped);
                            if (original != null && ItemStack.isSameItemSameTags(stack, original))
                                continue;
                        } else if (filledPedestals != null && filledPedestals.contains(ped)) {
                            continue;
                        }
                        setContainerItem(ped, 0, ItemStack.EMPTY);
                        syncBlockEntity(ped);
                        if (fromMachine.isEmpty()) {
                            fromMachine = stack;
                        } else {
                            if (network != null) {
                                network.insertItem(stack, stack.getCount(),
                                        com.refinedmods.refinedstorage.api.util.Action.PERFORM);
                            } else {
                                ItemHandlerHelper.giveItemToPlayer(player, stack);
                            }
                        }
                    }
                }
                // Fallback: check the iterator itself
                if (fromMachine.isEmpty()) {
                    try {
                        ItemStack result = getContainerItem(be, 0);
                        if (!result.isEmpty()) {
                            setContainerItem(be, 0, ItemStack.EMPTY);
                            fromMachine = result;
                        }
                    } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI] Reflection probe failed", e); }
                }
                break;
            }
            case ARCANE_WORKBENCH: {
                // The workbench has a separate itemOutputHandler (1 slot) for results.
                // itemHandler (13 slots) only holds inputs — never search it for output.
                ItemStackHandler outHandler = getWorkbenchOutputHandler(be);
                if (outHandler != null) {
                    ItemStack result = outHandler.getStackInSlot(0);
                    if (!result.isEmpty()) {
                        outHandler.setStackInSlot(0, ItemStack.EMPTY);
                        fromMachine = result;
                    }
                }
                break;
            }
            case CRYSTAL_RITUAL: {
                // 1) Try getItemsResult() — the ritual places output into the
                //    crystal block's result slots (IItemResultBlockEntity).
                try {
                    java.lang.reflect.Method getResults = Reflect.findMethod(
                            be.getClass(), "getItemsResult", new Class<?>[0]);
                    if (getResults != null) {
                        @SuppressWarnings("unchecked")
                        List<ItemStack> results = (List<ItemStack>) getResults.invoke(be);
                        if (results != null && !results.isEmpty()) {
                            for (ItemStack s : results) {
                                if (s != null && !s.isEmpty()) {
                                    ItemStack taken = s.copy();
                                    s.setCount(0); // clear the result slot
                                    if (fromMachine.isEmpty()) {
                                        fromMachine = taken;
                                    } else {
                                        if (network != null) {
                                            network.insertItem(taken, taken.getCount(),
                                                    com.refinedmods.refinedstorage.api.util.Action.PERFORM);
                                        } else {
                                            ItemHandlerHelper.giveItemToPlayer(player, taken);
                                        }
                                    }
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    RSIntegrationMod.LOGGER.debug("[RSI-Batch-WR] getItemsResult failed", e);
                }
                if (!fromMachine.isEmpty()) break;

                // 2) Scan for ItemEntity dropped near the crystal block.
                //    Some WR rituals spawn the output as a world item entity
                //    rather than delivering to the player.
                ServerLevel slevel = player.serverLevel();
                var aabb = new net.minecraft.world.phys.AABB(
                        myPos.offset(-2, -1, -2), myPos.offset(2, 3, 2));
                for (var entity : slevel.getEntitiesOfClass(
                        net.minecraft.world.entity.item.ItemEntity.class, aabb)) {
                    ItemStack stack = entity.getItem();
                    if (stack.isEmpty()) continue;
                    if (fromMachine.isEmpty()) {
                        fromMachine = stack.copy();
                        entity.discard();
                    } else if (ItemStack.isSameItemSameTags(stack, fromMachine)) {
                        int canTake = Math.min(stack.getCount(),
                                fromMachine.getMaxStackSize() - fromMachine.getCount());
                        if (canTake > 0) {
                            fromMachine.grow(canTake);
                            stack.shrink(canTake);
                            if (stack.isEmpty()) entity.discard();
                        }
                    }
                }
                break;
            }
        }
        if (!fromMachine.isEmpty()) return fromMachine;

        // wissenWandFunction auto-delivers the result to the player's inventory
        // (standard WR behavior). Search player inventory as fallback.
        ItemStack expected = com.huanghuang.rsintegration.crafting.ModRecipeIndex.tryGetResultItem(
                recipe, player.serverLevel().registryAccess());
        if (expected.isEmpty()) return ItemStack.EMPTY;

        var inv = player.getInventory();
        java.util.List<ItemStack> all = new java.util.ArrayList<>();
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
            if (!stack.isEmpty() && ItemStack.isSameItemSameTags(stack, expected)) {
                int take = Math.min(stack.getCount(), expected.getCount());
                ItemStack taken = stack.split(take);
                return taken;
            }
        }
        return ItemStack.EMPTY;
    }

    private boolean isInputSlot(int idx) {
        return filledSlotIndices != null && filledSlotIndices.contains(idx);
    }

    // ── Cleanup ──────────────────────────────────────────────────

    @Override
    public void onBatchFailed(ServerPlayer player, String reason) {
        // WR commits ledger before placement, so items are already
        // extracted from RS. Always recover actual items from machine
        // slots/pedestals before clearing.
        this.player = player;
        clearFilledSlots();
        clearFilledPedestals();
        ledger = null;
        sharedLedger = null;
        network = null;
        usingSharedLedger = false;
        craftStarted = false;
    }

    @Override
    public void onBatchFinished(ServerPlayer player) {
        // Items were consumed by the craft — slots/pedestals should
        // already be empty. Clear is just a safety measure.
        this.player = player;
        clearFilledSlots();
        clearFilledPedestals();
        ledger = null;
        sharedLedger = null;
        network = null;
        usingSharedLedger = false;
        craftStarted = false;
    }

    @Override
    public BlockPos getMachinePos() {
        return myPos;
    }

    // ── Cleanup helpers ──────────────────────────────────────────
    //
    // WR commits the ledger BEFORE placing items (unlike Goety), so
    // every clear MUST recover the actual items back to RS/player.
    // Failure to do so loses items that were already extracted from RS.

    /** Recover items from Wissen Crystallizer / Arcane Workbench slots, then clear. */
    private void clearFilledSlots() {
        if (filledSlotIndices == null) return;
        for (int idx : filledSlotIndices) {
            try {
                ItemStack stack = ItemStack.EMPTY;
                switch (machineType) {
                    case WISSEN_CRYSTALLIZER:
                        stack = getContainerItem(be, idx);
                        break;
                    case ARCANE_WORKBENCH: {
                        ItemStackHandler handler = getWorkbenchItemHandler(be);
                        if (handler != null) stack = handler.getStackInSlot(idx);
                        break;
                    }
                }
                if (!stack.isEmpty()) returnItem(stack);
                switch (machineType) {
                    case WISSEN_CRYSTALLIZER:
                        setContainerItem(be, idx, ItemStack.EMPTY);
                        break;
                    case ARCANE_WORKBENCH:
                        ItemStackHandler handler = getWorkbenchItemHandler(be);
                        if (handler != null) handler.setStackInSlot(idx, ItemStack.EMPTY);
                        break;
                }
            } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI] Reflection probe failed", e); }
        }
        // Also recover any item left in the workbench's output handler
        if (machineType == MachineType.ARCANE_WORKBENCH) {
            try {
                ItemStackHandler outHandler = getWorkbenchOutputHandler(be);
                if (outHandler != null) {
                    ItemStack result = outHandler.getStackInSlot(0);
                    if (!result.isEmpty()) {
                        returnItem(result);
                        outHandler.setStackInSlot(0, ItemStack.EMPTY);
                    }
                }
            } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI] Reflection probe failed", e); }
        }
        // Also recover any items left in crystallizer non-input slots
        if (machineType == MachineType.WISSEN_CRYSTALLIZER) {
            try {
                int size = getContainerSize(be);
                for (int i = 0; i < size; i++) {
                    if (isInputSlot(i)) continue;
                    ItemStack stack = getContainerItem(be, i);
                    if (!stack.isEmpty()) {
                        returnItem(stack);
                        setContainerItem(be, i, ItemStack.EMPTY);
                    }
                }
            } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI] Reflection probe failed", e); }
        }
    }

    /** Recover items from ArcaneWorkbench slots (handler version), then clear. */
    private void clearFilledSlotsForHandler(ItemStackHandler handler) {
        if (filledSlotIndices == null) return;
        for (int idx : filledSlotIndices) {
            try {
                ItemStack stack = handler.getStackInSlot(idx);
                if (!stack.isEmpty()) returnItem(stack);
                handler.setStackInSlot(idx, ItemStack.EMPTY);
            } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI] Reflection probe failed", e); }
        }
    }

    /** Recover items from Arcane Iterator / Crystal Ritual pedestals, then clear. */
    private void clearFilledPedestals() {
        if (filledPedestals == null) return;
        for (Object ped : filledPedestals) {
            try {
                ItemStack stack = getContainerItem(ped, 0);
                if (!stack.isEmpty()) returnItem(stack);
                setContainerItem(ped, 0, ItemStack.EMPTY);
                syncBlockEntity(ped);
            } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI] Reflection probe failed", e); }
        }
    }

    private void returnItem(ItemStack stack) {
        if (stack.isEmpty()) return;
        if (network != null) {
            network.insertItem(stack, stack.getCount(),
                    com.refinedmods.refinedstorage.api.util.Action.PERFORM);
        } else if (player != null) {
            if (!player.getInventory().add(stack)) {
                player.drop(stack, false);
            }
        }
    }

    // ── Container helpers ────────────────────────────────────────

    @Nullable
    private static ItemStackHandler getWorkbenchItemHandler(Object be) {
        try {
            java.lang.reflect.Field f = be.getClass().getDeclaredField("itemHandler");
            f.setAccessible(true);
            return (ItemStackHandler) f.get(be);
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-WR] Failed to get itemHandler from {}: {}",
                    be.getClass().getName(), e.toString());
            return null;
        }
    }

    @Nullable
    private static ItemStackHandler getWorkbenchOutputHandler(Object be) {
        try {
            java.lang.reflect.Field f = be.getClass().getDeclaredField("itemOutputHandler");
            f.setAccessible(true);
            return (ItemStackHandler) f.get(be);
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-WR] Failed to get itemOutputHandler from {}: {}",
                    be.getClass().getName(), e.toString());
            return null;
        }
    }

    /**
     * Get the Forge IItemHandler for a block entity via the standard
     * Forge capability system. This is the most reliable way to access
     * any well-behaved Forge mod's inventory.
     */
    @Nullable
    private static IItemHandler getForgeItemHandler(Object be) {
        if (be instanceof BlockEntity blockEntity) {
            return blockEntity.getCapability(ForgeCapabilities.ITEM_HANDLER, null).resolve().orElse(null);
        }
        return null;
    }

    /**
     * Walk the class hierarchy to find and return the BE's actual
     * SimpleContainer field. Unlike createItemHandler(), this gives us
     * the live instance that the BE itself uses.
     */
    @Nullable
    private static net.minecraft.world.SimpleContainer getLiveSimpleContainer(Object be) {
        Class<?> clazz = be.getClass();
        while (clazz != null && clazz != Object.class) {
            for (java.lang.reflect.Field field : clazz.getDeclaredFields()) {
                if (net.minecraft.world.SimpleContainer.class.isAssignableFrom(field.getType())) {
                    field.setAccessible(true);
                    try {
                        return (net.minecraft.world.SimpleContainer) field.get(be);
                    } catch (Exception ignored) {}
                }
            }
            clazz = clazz.getSuperclass();
        }
        return null;
    }

    /**
     * Get a SimpleContainer via the protected createItemHandler() factory.
     * WARNING: This MAY create a NEW empty container each call, not the BE's
     * live inventory. Prefer getLiveSimpleContainer() or getForgeItemHandler().
     */
    @Nullable
    private static net.minecraft.world.SimpleContainer getSimpleContainer(Object be) {
        Class<?> clazz = be.getClass();
        while (clazz != null && clazz != Object.class) {
            try {
                java.lang.reflect.Method m = clazz.getDeclaredMethod("createItemHandler");
                m.setAccessible(true);
                return (net.minecraft.world.SimpleContainer) m.invoke(be);
            } catch (NoSuchMethodException e) {
                clazz = clazz.getSuperclass();
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    private static int getContainerSize(Object be) {
        Class<?> bc = be.getClass();
        String beName = bc.getName();
        java.lang.reflect.Method m;

        // 1. WR BlockSimpleInventory.inventorySize() (no "get" prefix)
        m = com.huanghuang.rsintegration.util.Reflect.findMethod(bc, "inventorySize", new Class<?>[0]);
        if (m != null) { try { return (int) m.invoke(be); } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-Batch-WR] inventorySize() invoke failed for {}: {}", beName, e.toString());
        }}
        // 2. Alternative: getInventorySize()
        m = com.huanghuang.rsintegration.util.Reflect.findMethod(bc, "getInventorySize", new Class<?>[0]);
        if (m != null) { try { return (int) m.invoke(be); } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-Batch-WR] getInventorySize() invoke failed for {}: {}", beName, e.toString());
        }}
        // 3. Vanilla: getContainerSize() (BaseContainerBlockEntity)
        m = com.huanghuang.rsintegration.util.Reflect.findMethod(bc, "getContainerSize", new Class<?>[0]);
        if (m != null) { try { return (int) m.invoke(be); } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-Batch-WR] getContainerSize() invoke failed for {}: {}", beName, e.toString());
        }}
        // 4. getItemHandler() → IItemHandler.getSlots() or Container.getContainerSize()
        m = com.huanghuang.rsintegration.util.Reflect.findMethod(bc, "getItemHandler", new Class<?>[0]);
        if (m != null) {
            try {
                Object h = m.invoke(be);
                if (h != null) {
                    // Try IItemHandler.getSlots() first
                    java.lang.reflect.Method gm = com.huanghuang.rsintegration.util.Reflect.findMethod(h.getClass(), "getSlots", new Class<?>[0]);
                    if (gm != null) return (int) gm.invoke(h);
                    // Vanilla Container.getContainerSize() (WR returns Container, not IItemHandler)
                    gm = com.huanghuang.rsintegration.util.Reflect.findMethod(h.getClass(), "getContainerSize", new Class<?>[0]);
                    if (gm != null) return (int) gm.invoke(h);
                    // SRG fallback for Container
                    gm = com.huanghuang.rsintegration.util.Reflect.findMethod(h.getClass(), "m_6643_", new Class<?>[0]);
                    if (gm != null) return (int) gm.invoke(h);
                }
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.debug("[RSI-Batch-WR] getItemHandler() invoke failed for {}: {}", beName, e.toString());
            }
        }
        // 5. Forge capabilities (standard API)
        IItemHandler cap = getForgeItemHandler(be);
        if (cap != null) return cap.getSlots();
        // 6. Live SimpleContainer field on BE
        net.minecraft.world.SimpleContainer live = getLiveSimpleContainer(be);
        if (live != null) return live.getContainerSize();
        // 7. SRG name
        m = com.huanghuang.rsintegration.util.Reflect.findMethod(bc, "m_6643_", new Class<?>[0]);
        if (m != null) { try { return (int) m.invoke(be); } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-Batch-WR] m_6643_() invoke failed for {}: {}", beName, e.toString());
        }}
        // 8. createItemHandler() factory (last resort)
        net.minecraft.world.SimpleContainer sc = getSimpleContainer(be);
        if (sc != null) return sc.getContainerSize();

        RSIntegrationMod.LOGGER.warn("[RSI-Batch-WR] getContainerSize failed for {} — superclass={}, fields: {}",
                beName, bc.getSuperclass() != null ? bc.getSuperclass().getName() : "<none>",
                getFieldTypes(bc));
        return -1;
    }

    private static String getFieldTypes(Class<?> bc) {
        StringBuilder sb = new StringBuilder();
        Class<?> clazz = bc;
        int count = 0;
        while (clazz != null && clazz != Object.class && count < 3) {
            for (java.lang.reflect.Field field : clazz.getDeclaredFields()) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(field.getName()).append(':').append(field.getType().getSimpleName());
            }
            clazz = clazz.getSuperclass();
            count++;
        }
        return sb.toString();
    }

    private static ItemStack getContainerItem(Object be, int slot) {
        Class<?> bc = be.getClass();
        // 1. Vanilla: getItem(int)
        java.lang.reflect.Method m = com.huanghuang.rsintegration.util.Reflect.findMethod(bc, "getItem", new Class<?>[]{int.class});
        if (m != null) try { return (ItemStack) m.invoke(be, slot); } catch (Exception ignored) {}
        // 2. getItemHandler() — returns either IItemHandler or Container
        m = com.huanghuang.rsintegration.util.Reflect.findMethod(bc, "getItemHandler", new Class<?>[0]);
        if (m != null) {
            try {
                Object h = m.invoke(be);
                if (h != null) {
                    // 2a. IItemHandler.getStackInSlot(int)
                    java.lang.reflect.Method gm = com.huanghuang.rsintegration.util.Reflect.findMethod(h.getClass(), "getStackInSlot", new Class<?>[]{int.class});
                    if (gm != null) return (ItemStack) gm.invoke(h, slot);
                    // 2b. Container.getItem(int) — ExposedBlockSimpleInventory path
                    gm = com.huanghuang.rsintegration.util.Reflect.findMethod(h.getClass(), "getItem", new Class<?>[]{int.class});
                    if (gm != null) return (ItemStack) gm.invoke(h, slot);
                    // 2c. SRG m_8020_(int) → Container.getItem
                    gm = com.huanghuang.rsintegration.util.Reflect.findMethod(h.getClass(), "m_8020_", new Class<?>[]{int.class});
                    if (gm != null) return (ItemStack) gm.invoke(h, slot);
                }
            } catch (Exception ignored) {}
        }
        // 3. Forge capabilities (standard API)
        IItemHandler cap = getForgeItemHandler(be);
        if (cap != null) return cap.getStackInSlot(slot);
        // 4. Live SimpleContainer field on BE
        net.minecraft.world.SimpleContainer live = getLiveSimpleContainer(be);
        if (live != null) return live.getItem(slot);
        // 5. SRG name on BE
        m = com.huanghuang.rsintegration.util.Reflect.findMethod(bc, "m_8020_", new Class<?>[]{int.class});
        if (m != null) try { return (ItemStack) m.invoke(be, slot); } catch (Exception ignored) {}
        // 6. createItemHandler() factory (last resort)
        net.minecraft.world.SimpleContainer sc = getSimpleContainer(be);
        if (sc != null) return sc.getItem(slot);

        return ItemStack.EMPTY;
    }

    private static void setContainerItem(Object be, int slot, ItemStack stack) {
        Class<?> bc = be.getClass();
        // 1. Vanilla: setItem(int, ItemStack)
        java.lang.reflect.Method m = com.huanghuang.rsintegration.util.Reflect.findMethod(bc, "setItem", new Class<?>[]{int.class, ItemStack.class});
        if (m != null) { try { m.invoke(be, slot, stack); return; } catch (Exception ignored) {} }
        // 2. getItemHandler() — returns either IItemHandler or Container
        m = com.huanghuang.rsintegration.util.Reflect.findMethod(bc, "getItemHandler", new Class<?>[0]);
        if (m != null) {
            try {
                Object h = m.invoke(be);
                if (h != null) {
                    // 2a. IItemHandler.setStackInSlot(int, ItemStack)
                    java.lang.reflect.Method sm = com.huanghuang.rsintegration.util.Reflect.findMethod(h.getClass(), "setStackInSlot", new Class<?>[]{int.class, ItemStack.class});
                    if (sm != null) { sm.invoke(h, slot, stack); return; }
                    // 2b. Container.setItem(int, ItemStack) — ExposedBlockSimpleInventory path
                    sm = com.huanghuang.rsintegration.util.Reflect.findMethod(h.getClass(), "setItem", new Class<?>[]{int.class, ItemStack.class});
                    if (sm != null) { sm.invoke(h, slot, stack); return; }
                    // 2c. SRG m_6836_(int, ItemStack) → Container.setItem
                    sm = com.huanghuang.rsintegration.util.Reflect.findMethod(h.getClass(), "m_6836_", new Class<?>[]{int.class, ItemStack.class});
                    if (sm != null) { sm.invoke(h, slot, stack); return; }
                }
            } catch (Exception ignored) {}
        }
        // 3. Forge capabilities
        IItemHandler cap = getForgeItemHandler(be);
        if (cap != null) {
            if (cap instanceof ItemStackHandler handler) {
                handler.setStackInSlot(slot, stack);
                return;
            }
            // Non-ItemStackHandler: extract old, insert new
            ItemStack old = cap.extractItem(slot, cap.getStackInSlot(slot).getCount(), false);
            ItemStack leftover = cap.insertItem(slot, stack, false);
            if (!leftover.isEmpty()) {
                // insertItem didn't accept at this slot; restore old
                if (!old.isEmpty()) cap.insertItem(slot, old, false);
            } else {
                return;
            }
        }
        // 4. Live SimpleContainer field on BE
        net.minecraft.world.SimpleContainer live = getLiveSimpleContainer(be);
        if (live != null) { live.setItem(slot, stack); return; }
        // 5. SRG name on BE
        m = com.huanghuang.rsintegration.util.Reflect.findMethod(bc, "m_6836_", new Class<?>[]{int.class, ItemStack.class});
        if (m != null) { try { m.invoke(be, slot, stack); return; } catch (Exception ignored) {} }
        // 6. createItemHandler() factory (last resort)
        net.minecraft.world.SimpleContainer sc = getSimpleContainer(be);
        if (sc != null) { sc.setItem(slot, stack); return; }
        RSIntegrationMod.LOGGER.warn("[RSI-Batch-WR] Failed to set container item for {}",
                be.getClass().getName());
    }

    // ── Reflection helper ────────────────────────────────────────

    private static java.lang.reflect.Method getMethod(Class<?> clazz, String name, Class<?>... paramTypes)
            throws NoSuchMethodException {
        java.lang.reflect.Method m = com.huanghuang.rsintegration.util.Reflect.findMethod(
                clazz, name, paramTypes);
        if (m == null) throw new NoSuchMethodException(clazz.getName() + "." + name);
        return m;
    }

    // ── Client sync ──────────────────────────────────────────────

    /**
     * Send a block-entity update to tracking clients so the rendered item
     * model matches the server-side inventory. Tries FluffyFur's packet
     * helper first, then vanilla sendBlockUpdated which reliably syncs.
     */
    private static void syncBlockEntity(Object be) {
        if (!(be instanceof BlockEntity blockEntity)) return;
        var level = blockEntity.getLevel();
        if (level == null) return;
        try {
            Class<?> updateClass = Class.forName(
                    "mod.maxbogomol.fluffy_fur.common.network.BlockEntityUpdate");
            getMethod(updateClass, "packet", BlockEntity.class).invoke(null, blockEntity);
        } catch (Exception e) {
            // Vanilla: marks changed + sends SUpdateBlockEntityPacket to tracking clients
            blockEntity.setChanged();
            level.sendBlockUpdated(blockEntity.getBlockPos(),
                    blockEntity.getBlockState(), blockEntity.getBlockState(), 3);
        }
    }

    // ── Crystal ritual pre-flight checks ──────────────────────────

    /**
     * Check whether the crystal block is on cooldown (has been recently used).
     * Tries multiple method/field names to accommodate different WR versions.
     */
    // ── Wissen energy check ───────────────────────────────────────

    /**
     * Check whether the machine has enough Wissen energy for the recipe.
     * Returns true if wissen is sufficient or if the check can't be performed.
     * Must be called BEFORE ledger commit to avoid extracting items that can't be used.
     */
    private boolean checkWissen() {
        // Crystal rituals don't consume wissen
        if (machineType == MachineType.CRYSTAL_RITUAL) return true;

        int cost = readWissenCost();
        if (cost <= 0) return true; // no wissen cost, pass

        int current = readCurrentWissen();
        if (current < cost) {
            player.sendSystemMessage(Component.translatable(
                    "rsi.wr.error.insufficient_wissen",
                    String.format("%,d", current), String.format("%,d", cost)));
            return false;
        }
        return true;
    }

    private int readWissenCost() {
        try {
            java.lang.reflect.Method m = Reflect.findMethod(recipe.getClass(), "getWissen", new Class<?>[0]);
            if (m != null) return (int) m.invoke(recipe);
        } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI-Batch-WR] Failed to read wissen cost", e); }
        return 0;
    }

    private int readCurrentWissen() {
        try {
            java.lang.reflect.Method m = Reflect.findMethod(be.getClass(), "getWissen", new Class<?>[0]);
            if (m != null) return (int) m.invoke(be);
        } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI-Batch-WR] Failed to read current wissen", e); }
        // Fallback: try reading the public `wissen` field directly
        try {
            java.lang.reflect.Field f = Reflect.findField(be.getClass(), "wissen").orElse(null);
            if (f != null) {
                f.setAccessible(true);
                return f.getInt(be);
            }
        } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI-Batch-WR] Failed to read wissen field", e); }
        return Integer.MAX_VALUE; // can't determine — don't block
    }

    // ── Ritual extraction ────────────────────────────────────────

    @Nullable
    private static Object extractRitual(Object recipe) {
        Class<?> clazz = recipe.getClass();
        while (clazz != null && clazz != Object.class) {
            for (java.lang.reflect.Field field : clazz.getDeclaredFields()) {
                if (field.getName().equals("ritual")) {
                    field.setAccessible(true);
                    try {
                        return field.get(recipe);
                    } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI] Reflection probe failed", e); }
                }
            }
            clazz = clazz.getSuperclass();
        }
        return null;
    }

    // ── Plan warnings ─────────────────────────────────────────────

    public static List<String> getPlanWarnings(ServerPlayer player, Recipe<?> recipe,
                                               @Nullable ResourceLocation dim,
                                               @Nullable BlockPos pos) {
        List<String> warnings = new ArrayList<>();
        if (recipe == null) return warnings;
        ensureClasses();

        // Determine machine type from recipe ID path
        MachineType mt = expectedMachineType(recipe.getId());
        if (mt == MachineType.CRYSTAL_RITUAL) return warnings; // no wissen for crystal rituals

        // Read wissen cost from recipe
        int wissenCost = 0;
        try {
            java.lang.reflect.Method m = Reflect.findMethod(recipe.getClass(), "getWissen", new Class<?>[0]);
            if (m != null) wissenCost = (int) m.invoke(recipe);
        } catch (Exception e) { /* can't read, skip */ }
        if (wissenCost <= 0) return warnings;

        boolean didCheckMachine = false;
        // If machine is bound, check current wissen
        if (dim != null && pos != null) {
            try {
                ServerLevel level = CraftPacketUtils.resolveLevel(player.server, dim, player);
                if (level != null && level.isLoaded(pos)) {
                    BlockEntity be = level.getBlockEntity(pos);
                    if (be != null) {
                        int current = readCurrentWissenStatic(be);
                        if (current >= 0) {
                            didCheckMachine = true;
                            if (current < wissenCost) {
                                warnings.add(Component.translatable(
                                        "rsi.wr.warn.insufficient_wissen_plan",
                                        String.format("%,d", current),
                                        String.format("%,d", wissenCost)).getString());
                            }
                        }
                    }
                }
            } catch (Exception e) { /* can't read, skip */ }
        }
        if (!didCheckMachine) {
            // No machine bound or couldn't read — just show the cost
            warnings.add(Component.translatable(
                    "rsi.wr.warn.wissen_cost",
                    String.format("%,d", wissenCost)).getString());
        }

        return warnings;
    }

    private static int readCurrentWissenStatic(BlockEntity be) {
        try {
            java.lang.reflect.Method m = Reflect.findMethod(be.getClass(), "getWissen", new Class<?>[0]);
            if (m != null) return (int) m.invoke(be);
        } catch (Exception e) {}
        try {
            java.lang.reflect.Field f = Reflect.findField(be.getClass(), "wissen").orElse(null);
            if (f != null) { f.setAccessible(true); return f.getInt(be); }
        } catch (Exception e) {}
        return -1;
    }
}
