package com.huanghuang.rsintegration.mods.wizardsreborn;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.batch.AbstractBatchDelegate;

import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.reflection.probes.WRReflection;
import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.MaterialSources;
import com.huanghuang.rsintegration.network.binding.AltarBindingRegistry;
import com.huanghuang.rsintegration.network.RSIntegrationNetwork;
import com.huanghuang.rsintegration.recipe.WRRecipeHandler;
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

/** Batch delegate for Wizard's Reborn machines (Crystallizer, Workbench, Iterator, Crystal Ritual). */
public final class WRBatchDelegate extends AbstractBatchDelegate {

    private enum MachineType {
        WISSEN_CRYSTALLIZER,
        ARCANE_ITERATOR,
        ARCANE_WORKBENCH,
        CRYSTAL_RITUAL,
        UNKNOWN
    }


    private ServerPlayer player;
    private ResourceKey<Level> myDim;
    private BlockPos myPos;
    private Object be;                   // The block entity
    private MachineType machineType = MachineType.UNKNOWN;
    private Recipe<?> recipe;
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
    private boolean craftStarted;
    // For ARCANE_ITERATOR: tracks whether wissenInCraft was observed > 0,
    // meaning the craft actually started processing (not just warm-up).
    private boolean iteratorCraftProcessing;
    // Stall detection: if progress freezes (wissen/XP/health exhausted),
    // abort early instead of waiting for MAX_WAIT_TICKS.
    private int lastCraftProgress = -1;
    private int stallTicks;
    // Timeout: max of 7200 ticks (6 min) or wissenCost/5*2 (double the
    // theoretical time, accounting for XP/health drain cooldowns).
    // Stall threshold kicks in at 5 seconds of no progress.
    private static final int MAX_WAIT_TICKS = 7200;
    private static final int STALL_THRESHOLD = 100; // 5 seconds

    // ── Arcane Iterator per-level scheduler ──
    // The iterator enchants the center-pedestal book +1 per machine run and
    // re-places it on the center (NOT consumed), so reaching level N from a plain
    // book takes N runs, each consuming one fresh side-material set + Wissen. We
    // grind lazily: run once, then on completion re-supply the next side set and
    // re-trigger, repeating until the target level is reached or materials/Wissen
    // run out (graceful degradation — stop and collect the book achieved so far,
    // never an error, never item loss). Verified against WR 0.2.9 bytecode:
    // tick() reads getMainPedestal().m_8020_(0), enchant()s it +1, and writes it
    // back via m_6836_(0, book); side pedestals are emptied one set per run.
    private int iteratorTargetLevel = 1;
    private int iteratorCompletedRuns;
    private List<Ingredient> iteratorSideIngredients;
    // Snapshot of the center-pedestal book captured just before each machine
    // run is triggered. After the run we compare it to the current center book:
    // if unchanged, the machine couldn't enchant further (e.g. target exceeds the
    // enchantment's max level) → stop instead of wasting more side-material sets.
    private ItemStack iteratorCenterBefore = ItemStack.EMPTY;

    @Override
    public boolean validateAndInit(ServerPlayer player, ResourceLocation recipeId,
                                   @Nullable ResourceLocation dim, BlockPos pos) {

        // Reset all instance state to prevent pollution from a previous
        // validateAndInit() call that failed partway through.
        this.machineType = MachineType.UNKNOWN;
        this.be = null;
        this.recipe = null;
        this.pedestalRefs = null;
        this.waitTicks = 0;
        this.craftStarted = false;
        this.iteratorCraftProcessing = false;
        this.lastCraftProgress = -1;
        this.stallTicks = 0;
        this.filledSlotIndices = null;
        this.filledPedestals = null;
        this.placedInputs = null;
        this.placedPedestalItems = null;
        this.iteratorTargetLevel = 1;
        this.iteratorCompletedRuns = 0;
        this.iteratorSideIngredients = null;

        ServerLevel level = CraftPacketUtils.resolveLevel(player.server, dim, player);
        if (level == null) {
            player.sendSystemMessage(Component.translatable("rsi.generic.error.dim_not_found"));
            return false;
        }
        this.myDim = level.dimension();
        this.myPos = pos;
        this.player = player;

        if (!level.isLoaded(pos)) {
            RSIntegrationMod.LOGGER.debug("[RSI-Batch-WR] Chunk unloaded at {} — force-loading", pos);
            level.getChunk(pos);
        }
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity == null) {
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-WR] BE null at bound pos ({},{},{}) dim={}",
                    pos.getX(), pos.getY(), pos.getZ(), dim);
            return false;
        }
        this.be = blockEntity;

        // Determine machine type
        if (WRReflection.wissenCrystallizerBEClass != null && WRReflection.wissenCrystallizerBEClass.isInstance(be)) {
            machineType = MachineType.WISSEN_CRYSTALLIZER;
        } else if (WRReflection.arcaneIteratorBEClass != null && WRReflection.arcaneIteratorBEClass.isInstance(be)) {
            machineType = MachineType.ARCANE_ITERATOR;
        } else if (WRReflection.arcaneWorkbenchBEClass != null && WRReflection.arcaneWorkbenchBEClass.isInstance(be)) {
            machineType = MachineType.ARCANE_WORKBENCH;
        } else if (WRReflection.crystalRitualBEClass != null && WRReflection.crystalRitualBEClass.isInstance(be)) {
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
        if (subType.contains("focus")) return MachineType.WISSEN_CRYSTALLIZER;
        if (subType.contains("crystal")) return MachineType.CRYSTAL_RITUAL;
        return MachineType.UNKNOWN;
    }

    private boolean validateIdle(ServerPlayer player, ServerLevel level) {
        // Every machine type must pass an "is crafting?" check first.
        // Even if all slots / pedestals appear empty (some machines
        // consume items immediately when the craft starts), the ticking
        // craft state should still be detectable.
        // ARCANE_ITERATOR has its own crafting check below (wissenInCraft).
        // isMachineCrafting() scans for "startCraft"/"active" which can be
        // stuck true on iterators even when idle (parent BlockEntityBase).
        if (machineType != MachineType.ARCANE_ITERATOR && isMachineCrafting()) {
            player.sendSystemMessage(Component.translatable("rsi.wr.error.machine_busy"));
            return false;
        }

        switch (machineType) {
            case WISSEN_CRYSTALLIZER: {
                int size = getContainerSize(be);
                if (size < 0) {
                    RSIntegrationMod.LOGGER.warn("[RSI-Batch-WR] Cannot determine crystallizer container size for {}",
                            be.getClass().getName());
                    return false;
                }
                // Reject if ANY slot has items — the owning task is responsible
                // for collecting results. Auto-collecting here would steal items
                // from another running task, causing item duping.
                for (int i = 0; i < size; i++) {
                    if (!getContainerItem(be, i).isEmpty()) {
                        player.sendSystemMessage(Component.translatable("rsi.wr.error.machine_busy"));
                        return false;
                    }
                }
                break;
            }
            case ARCANE_ITERATOR: {
                // Use the same check as isCraftComplete: both startCraft AND
                // wissenInCraft must be set for the machine to be truly busy.
                // wissenInCraft can still be draining (>0) after startCraft
                // flips to false, which would block the next craft if we only
                // checked wissenInCraft alone.
                if (isIteratorCraftRunning()) {
                    player.sendSystemMessage(Component.translatable("rsi.wr.error.machine_busy"));
                    return false;
                }
                try {
                    pedestalRefs = (List<?>) Reflect.getMethodOrThrow(be.getClass(), "getPedestals", "getPedestals").invoke(be);
                } catch (Exception e) {
                    RSIntegrationMod.LOGGER.warn("[RSI-Batch-WR] Failed to get iterator pedestals", e);
                    return false;
                }
                if (pedestalRefs == null) {
                    RSIntegrationMod.LOGGER.warn("[RSI-Batch-WR] getPedestals() returned null for ArcaneIterator at {}", myPos);
                    return false;
                }
                // If pedestals hold items but the iterator isn't actively
                // crafting (startCraft=false), recover them to RS instead of
                // leaving them to be consumed by a stray tick detection.
                boolean hadStray = false;
                for (int i = 0; i < pedestalRefs.size(); i++) {
                    ItemStack stack = getContainerItem(pedestalRefs.get(i), 0);
                    if (!stack.isEmpty()) {
                        // Recover to RS network or player inventory
                        if (network != null) {
                            ItemStack leftover = network.insertItem(stack.copy(), stack.getCount(),
                                    com.refinedmods.refinedstorage.api.util.Action.PERFORM);
                            if (!leftover.isEmpty()) {
                                net.minecraftforge.items.ItemHandlerHelper.giveItemToPlayer(player, leftover);
                            }
                        } else {
                            net.minecraftforge.items.ItemHandlerHelper.giveItemToPlayer(player, stack.copy());
                        }
                        setContainerItem(pedestalRefs.get(i), 0, ItemStack.EMPTY);
                        syncBlockEntity(pedestalRefs.get(i));
                        hadStray = true;
                    }
                }
                if (hadStray) {
                    RSIntegrationMod.LOGGER.debug("[RSI-Batch-WR] Recovered stray pedestal items for iterator at {}", myPos);
                }
                break;
            }
            case ARCANE_WORKBENCH: {
                ItemStackHandler handler = getWorkbenchItemHandler(be);
                if (handler == null) {
                    RSIntegrationMod.LOGGER.warn("[RSI-Batch-WR] Cannot get itemHandler for ArcaneWorkbench at {}", myPos);
                    return false;
                }
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

    private boolean isCrystallizerCrafting() {
        return isMachineCrafting();
    }

    private boolean isWorkbenchCrafting() {
        return isMachineCrafting();
    }

    /**
     * Returns true if any WR machine's internal crafting-state flag is set,
     * regardless of machine sub-type.  Covers startCraft / wissenInCraft
     * and several common variant field names so a rename in a WR update
     * won't silently defeat the check.
     */
    private boolean isMachineCrafting() {
        if (be == null) return false;
        Class<?> bc = be.getClass();
        // Boolean flags — set during processing
        for (String name : new String[]{"startCraft", "isCrafting", "crafting", "active", "workStarted"}) {
            try {
                java.lang.reflect.Field f = Reflect.findField(bc, name).orElse(null);
                if (f != null) {
                    f.setAccessible(true);
                    if ((boolean) f.get(be)) return true;
                }
            } catch (Exception e) { /* field not present or wrong type */ }
        }
        // Int timers — >0 means craft in progress
        for (String name : new String[]{"wissenInCraft", "craftTick", "progress", "craftTime", "craftTimer"}) {
            try {
                java.lang.reflect.Field f = Reflect.findField(bc, name).orElse(null);
                if (f != null) {
                    f.setAccessible(true);
                    if (f.getInt(be) > 0) return true;
                }
            } catch (Exception e) { /* field not present or wrong type */ }
        }
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
            Object crystalItem = Reflect.getMethodOrThrow(be.getClass(), "getCrystalItem", "getCrystalItem").invoke(be);
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
        if (WRReflection.runicPedestalBEClass == null) {
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-WR] [step 4/6] WRReflection.runicPedestalBEClass is null (not loaded)");
            player.sendSystemMessage(Component.translatable("rsi.wr.error.runic_pedestal_missing"));
            return false;
        }
        if (!WRReflection.runicPedestalBEClass.isInstance(belowBE)) {
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-WR] [step 4/6] Block below at {} is {} (expected RunicPedestalBlockEntity)",
                    below, belowBE.getClass().getName());
            player.sendSystemMessage(Component.translatable("rsi.wr.error.runic_pedestal_missing"));
            return false;
        }
        RSIntegrationMod.LOGGER.debug("[RSI-Batch-WR] [step 4/6] runic pedestal present at {}", below);

        // [Step 5] Runic pedestal must have a runic plate
        try {
            java.lang.reflect.Method hasPlate = Reflect.findMethod(
                    WRReflection.runicPedestalBEClass, "hasRunicPlate", new Class<?>[0]);
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
                    WRReflection.runicPedestalBEClass, "getCrystalRitual", new Class<?>[0]);
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
                RSIntegrationMod.LOGGER.debug("[RSI-Batch-WR] [step 6/6] getCrystalRitual method not found on {}", WRReflection.runicPedestalBEClass.getName());
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-Batch-WR] [step 6/6] ritual ID check exception", e);
        }

        RSIntegrationMod.LOGGER.debug("[RSI-Batch-WR] Crystal setup validation PASSED at {}", myPos);
        return true;
    }

    @Override
    public boolean tryStartSingleCraft(ServerPlayer player) {
        this.player = player;
        this.ledger = new ExtractionLedger();
        this.network = CraftPacketUtils.resolveNetworkForCraft(player, myDim, myPos);
        this.filledSlotIndices = new ArrayList<>();
        this.filledPedestals = new ArrayList<>();
        this.waitTicks = 0;

        // Verify the cached BlockEntity is still valid
        if (myPos != null && resolveMachineLevel(player).isLoaded(myPos)) {
            BlockEntity current = resolveMachineLevel(player).getBlockEntity(myPos);
            if (current == null || current.isRemoved()) {
                player.sendSystemMessage(Component.translatable("rsi.error.machine_missing"));
                if (ledger != null && ledger.isCommitted()) {
                    ledger.refundCommitted(network, player);
                }
                return false;
            }
        }

        // All machine types go through extractIngredients → filterWRCrystal.
        // The crystal catalyst belongs in the crystal block (validated in
        // validateCrystalSetup), not on a pedestal from RS extraction.
        List<Ingredient> ingredients = CraftPacketUtils.extractIngredients(recipe);
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
            Reflect.getMethodOrThrow(be.getClass(), "wissenWandFunction", "wissenWandFunction").invoke(be);
            syncBlockEntity(be);
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-WR] wissenWandFunction invoke failed, rolling back", e);
            clearFilledSlots(!usingSharedLedger);

            return false;
        }

        return true;
    }

    private boolean tryStartArcaneIterator(ServerPlayer player, List<Ingredient> ingredients) {
        List<?> pedestals;
        try {
            pedestals = (List<?>) Reflect.getMethodOrThrow(be.getClass(), "getPedestals", "getPedestals").invoke(be);
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-WR] Failed to get pedestals from ArcaneIterator", e);
            return false;
        }
        this.pedestalRefs = pedestals;

        if (pedestals.size() < ingredients.size()) return false;
        if (ingredients.isEmpty()) return false;

        // Determine the enchant level the player clicked. Levels I/II/III share
        // one recipe id and declare no static output, so the level is inferred by
        // matching the clicked output (captured as targetOutput) against books
        // built at each candidate level. Non-enchant iterator recipes (e.g.
        // arcanum_lens) infer level 1 → a single run, unchanged behavior.
        // Reaching level N from a plain book requires N sequential machine runs
        // (each +1 the center book, consuming one side-material set + Wissen);
        // we grind them lazily via the poll loop (see supplyNextIteratorLevel /
        // isMachineCraftFinished ARCANE_ITERATOR). Verified vs WR 0.2.9 bytecode.
        this.iteratorTargetLevel = WRRecipeHandler.inferTargetLevel(recipe, getTargetOutput());
        this.iteratorCompletedRuns = 0;

        // Prefer the highest exact-NBT intermediate book already available. This
        // turns (for example) an existing Curse IV + target V into one machine run
        // instead of rebuilding I..IV. Search both RS and the same player/backpack
        // sources ExtractionLedger can consume, then replace the synthetic plain-
        // book requirement with that exact book ingredient.
        List<Ingredient> effectiveIngredients = new ArrayList<>(ingredients);
        if (iteratorTargetLevel >= 2 && !effectiveIngredients.isEmpty()) {
            var available = MaterialSources.listAllAvailable(player, network);
            for (int lvl = iteratorTargetLevel - 1; lvl >= 1; lvl--) {
                ItemStack candidate = WRRecipeHandler.buildEnchantedBookOutput(recipe, lvl);
                if (candidate.isEmpty()) break;
                boolean present = available.entrySet().stream()
                        .anyMatch(e -> e.getValue() > 0
                                && ItemStack.isSameItemSameTags(e.getKey().toStack(), candidate));
                if (present) {
                    effectiveIngredients.set(0,
                            net.minecraftforge.common.crafting.StrictNBTIngredient.of(candidate));
                    this.iteratorCompletedRuns = lvl;
                    break;
                }
            }
        }
        // Side ingredients = everything after the hidden center book (index 0,
        // prepended by filterWRCrystal). These are re-supplied once per level.
        this.iteratorSideIngredients = effectiveIngredients.size() > 1
                ? new ArrayList<>(effectiveIngredients.subList(1, effectiveIngredients.size()))
                : new ArrayList<>();

        // Phase 1: reserve the selected center book + the first side set into
        // the private ledger. reserve() does no physical extraction.
        List<ItemStack> templates = new ArrayList<>();
        for (int i = 0; i < effectiveIngredients.size(); i++) {
            Ingredient ing = effectiveIngredients.get(i);
            if (ing.getItems().length == 0) {
                templates.add(ItemStack.EMPTY);
                continue;
            }
            ItemStack taken = CraftPacketUtils.ensureMaterialAvailable(player, myDim, myPos, ing, 1, ledger);
            if (taken.isEmpty()) return false;
            templates.add(taken);
        }

        // Phase 2: Wissen/XP/health gate for the first level.
        if (!checkWissen()) return false;

        // Phase 3: commit (physical extraction of center book + first side set).
        if (!ledger.commit(network, player)) return false;

        // Phase 4: place center book + first side set on their pedestals.
        this.placedPedestalItems = new java.util.HashMap<>();
        this.filledPedestals = new ArrayList<>();
        int placed = 0;
        for (int i = 0; i < templates.size(); i++) {
            ItemStack taken = templates.get(i);
            if (taken.isEmpty()) { placed = i + 1; continue; }
            try {
                Object ped = pedestals.get(i);
                ItemStack existing = getContainerItem(ped, 0);
                if (!existing.isEmpty()) {
                    ItemHandlerHelper.giveItemToPlayer(player, existing.copy());
                }
                setContainerItem(ped, 0, taken);
                filledPedestals.add(ped);
                placedPedestalItems.put(ped, taken.copy());
                placed = i + 1;
                syncBlockEntity(ped);
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.warn("[RSI-Batch-WR] Failed to place item on ArcaneIterator pedestal {}: {}", i, e.getMessage());
                // Hard abort of the first level: physically recover placed pedestals
                // + return the still-unplaced (already extracted) templates, then
                // reset the ledger so its committed entries are not double-refunded.
                clearFilledPedestals(!usingSharedLedger);
                for (int k = placed; k < templates.size(); k++) {
                    if (!templates.get(k).isEmpty()) returnItem(templates.get(k));
                }
                ledger.reset();
                return false;
            }
        }

        // The committed center book + first side set are now physically on the
        // pedestals. Reset the private ledger to IDLE so (a) it can atomically
        // extract the NEXT level's side set later and (b) its now-stale committed
        // entries are never re-refunded — abort recovers items from the pedestals
        // instead (the plain book "becomes" the output book in-place, so it must
        // not be refunded separately).
        ledger.reset();

        craftStarted = true;
        iteratorCraftProcessing = false;
        iteratorCenterBefore = getContainerItem(pedestals.get(0), 0).copy();
        try {
            Reflect.getMethodOrThrow(be.getClass(), "wissenWandFunction", "wissenWandFunction").invoke(be);
            syncBlockEntity(be);
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-WR] wissenWandFunction invoke failed, rolling back", e);
            clearFilledPedestals(!usingSharedLedger);
            return false;
        }

        return true;
    }

    /**
     * Extract and place the next level's side-material set, then re-trigger the
     * machine for another +1 enchant run. Called from the poll loop after a run
     * completes when the target level has not yet been reached.
     *
     * <p>Uses the private ledger purely as a per-level atomic-extraction tool:
     * reset → reserve → commit → place → reset. On any shortfall the ledger is
     * reset (reservations are in-memory only, so nothing is half-extracted) and
     * the method returns false, signalling graceful degradation — the caller
     * stops and collects the enchant level reached so far. The evolved center
     * book is always preserved for collection; only side materials are recovered.
     *
     * @return true if the next run was started; false on graceful stop.
     */
    private boolean supplyNextIteratorLevel() {
        if (ledger == null || iteratorSideIngredients == null || pedestalRefs == null) return false;

        // Re-fetch pedestals (positional cube scan is stable; reuse cache on miss).
        List<?> pedestals = pedestalRefs;
        try {
            Object refetched = Reflect.getMethodOrThrow(be.getClass(), "getPedestals", "getPedestals").invoke(be);
            if (refetched instanceof List<?> l && l.size() > iteratorSideIngredients.size()) {
                pedestals = l;
                this.pedestalRefs = l;
            }
        } catch (Exception e) { /* reuse cached refs */ }
        if (pedestals.size() <= iteratorSideIngredients.size()) return false;

        // Fresh atomic extraction: private ledger must be IDLE before reserving.
        if (ledger.state() != ExtractionLedger.State.IDLE) ledger.reset();

        // Wissen/XP/health gate for this level (sends a specific reason on failure).
        if (!checkWissen()) return false;

        // Reserve one fresh side set. A miss leaves only in-memory reservations
        // that reset() discards — never a half-held physical set.
        List<ItemStack> sideTemplates = new ArrayList<>();
        for (Ingredient ing : iteratorSideIngredients) {
            if (ing.getItems().length == 0) { sideTemplates.add(ItemStack.EMPTY); continue; }
            ItemStack taken = CraftPacketUtils.ensureMaterialAvailable(player, myDim, myPos, ing, 1, ledger);
            if (taken.isEmpty()) {
                ledger.reset();
                return false; // insufficient materials → graceful stop
            }
            sideTemplates.add(taken);
        }

        if (!ledger.commit(network, player)) {
            return false; // lost an extraction race → graceful stop
        }

        // Place the side set on side pedestals (indices 1+). Rebuild filledPedestals
        // as [center, ...placed sides] so abort physically recovers the evolved
        // center book plus any side items this run hasn't consumed yet.
        Object centerPed = pedestals.get(0);
        List<Object> newFilled = new ArrayList<>();
        newFilled.add(centerPed);
        this.placedPedestalItems = new java.util.HashMap<>();
        int placed = 0;
        for (int i = 0; i < sideTemplates.size(); i++) {
            ItemStack taken = sideTemplates.get(i);
            if (taken.isEmpty()) { placed = i + 1; continue; }
            Object ped = pedestals.get(i + 1);
            try {
                ItemStack existing = getContainerItem(ped, 0);
                if (!existing.isEmpty()) ItemHandlerHelper.giveItemToPlayer(player, existing.copy());
                setContainerItem(ped, 0, taken);
                newFilled.add(ped);
                placedPedestalItems.put(ped, taken.copy());
                placed = i + 1;
                syncBlockEntity(ped);
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.warn("[RSI-Batch-WR] Failed to place side material for next iterator level: {}", e.getMessage());
                // Graceful: recover this level's side items (placed → physical,
                // unplaced → in-memory), preserve the evolved center book so
                // collectResult still returns the level reached so far.
                for (int k = 1; k < newFilled.size(); k++) {
                    Object sp = newFilled.get(k);
                    ItemStack s = getContainerItem(sp, 0);
                    if (!s.isEmpty()) { returnItem(s); setContainerItem(sp, 0, ItemStack.EMPTY); syncBlockEntity(sp); }
                }
                for (int k = placed; k < sideTemplates.size(); k++) {
                    if (!sideTemplates.get(k).isEmpty()) returnItem(sideTemplates.get(k));
                }
                ledger.reset();
                this.filledPedestals = new ArrayList<>();
                this.filledPedestals.add(centerPed);
                return false;
            }
        }
        this.filledPedestals = newFilled;

        // Committed side set is now physically on the pedestals; reset ledger so
        // stale committed entries are never re-refunded (physical recovery on abort).
        ledger.reset();

        iteratorCraftProcessing = false;
        iteratorCenterBefore = getContainerItem(centerPed, 0).copy();
        // Reset stall tracking so the previous run's final progress value can't
        // trip a false stall on the fresh run (which restarts progress near 0).
        // Also reset the delegate's per-run timeout window; the chain-level
        // timeout (MULTIBLOCK_CRAFT_TIMEOUT_SECONDS) remains the global ceiling.
        lastCraftProgress = -1;
        stallTicks = 0;
        waitTicks = 0;
        try {
            Reflect.getMethodOrThrow(be.getClass(), "wissenWandFunction", "wissenWandFunction").invoke(be);
            syncBlockEntity(be);
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-WR] wissenWandFunction re-trigger failed", e);
            // Recover side items, preserve center book, graceful stop.
            for (int k = 1; k < newFilled.size(); k++) {
                Object sp = newFilled.get(k);
                ItemStack s = getContainerItem(sp, 0);
                if (!s.isEmpty()) { returnItem(s); setContainerItem(sp, 0, ItemStack.EMPTY); syncBlockEntity(sp); }
            }
            this.filledPedestals = new ArrayList<>();
            this.filledPedestals.add(centerPed);
            return false;
        }
        RSIntegrationMod.LOGGER.debug("[RSI-Batch-WR] ArcaneIterator leveling: started run {} of {}",
                iteratorCompletedRuns + 1, iteratorTargetLevel);
        return true;
    }

    /** Read the current center-pedestal book (index 0 of getPedestals / getMainPedestal). */
    private ItemStack readIteratorCenterBook() {
        try {
            java.lang.reflect.Method getMain = Reflect.findMethod(be.getClass(), "getMainPedestal", new Class<?>[0]);
            if (getMain != null) {
                Object mainPed = getMain.invoke(be);
                if (mainPed != null) return getContainerItem(mainPed, 0);
            }
        } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI] center book probe failed", e); }
        if (pedestalRefs != null && !pedestalRefs.isEmpty()) {
            try { return getContainerItem(pedestalRefs.get(0), 0); } catch (Exception e) { /* ignore */ }
        }
        return ItemStack.EMPTY;
    }

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
            Reflect.getMethodOrThrow(be.getClass(), "wissenWandFunction", "wissenWandFunction").invoke(be);
            syncBlockEntity(be);
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-WR] wissenWandFunction invoke failed, rolling back", e);
            clearFilledSlotsForHandler(itemHandler, !usingSharedLedger);

            return false;
        }

        return true;
    }

    private boolean tryStartCrystalRitual(ServerPlayer player, List<Ingredient> ingredients) {
        Object ritual = extractRitual(recipe);
        if (ritual == null) {
            try {
                ritual = Reflect.getMethodOrThrow(be.getClass(), "getCrystalRitual", "getCrystalRitual").invoke(be);
            } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI] Reflection probe failed", e); }
        }
        if (ritual == null) {
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-WR] Failed to extract ritual data");
            return false;
        }

        Object area;
        try {
            area = Reflect.getMethodOrThrow(ritual.getClass(), "getArea", "getArea", be.getClass()).invoke(ritual, be);
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-WR] Failed to get ritual area", e);
            return false;
        }

        List<?> pedestals;
        try {
            ServerLevel level = resolveMachineLevel(player);
            pedestals = (List<?>) Reflect.getMethodOrThrow(WRReflection.crystalRitualClass, "getPedestalsWithArea", "getPedestalsWithArea", Level.class, BlockPos.class, WRReflection.ritualAreaClass)
                    .invoke(null, level, myPos, area);
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-WR] Failed to find arcane pedestals for crystal ritual", e);
            return false;
        }

        if (pedestals.size() < ingredients.size()) return false;

        // The crystal ingredient is now filtered out by extractIngredients.
        // If the ritual area has more pedestals than material ingredients,
        // the extra pedestal (index 0) corresponds to the crystal slot in
        // the original recipe and should be skipped.
        int pedOffset = pedestals.size() - ingredients.size();

        // Phase 1: reserve all ingredients
        List<ItemStack> templates = new ArrayList<>();
        for (int i = 0; i < ingredients.size(); i++) {
            Ingredient ing = ingredients.get(i);
            if (ing.getItems().length == 0) {
                templates.add(ItemStack.EMPTY);
                continue;
            }

            Object ped = pedestals.get(i + pedOffset);
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

            Object ped = pedestals.get(i + pedOffset);
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
                clearFilledPedestals(!usingSharedLedger);

                return false;
            }
        }

        craftStarted = true;
        try {
            Reflect.getMethodOrThrow(be.getClass(), "wissenWandFunction", "wissenWandFunction").invoke(be);
            syncBlockEntity(be);
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-WR] Failed to invoke wissenWandFunction on crystal block", e);
            clearFilledPedestals(!usingSharedLedger);

            return false;
        }

        return true;
    }

    @Override
    @Nullable
    public List<IngredientSpec> getRequiredMaterials() {
        if (recipe == null) return null;
        // ARCANE_ITERATOR must self-manage extraction: leveled enchant books
        // require N sequential machine runs (one side-material set + Wissen per
        // run), and the chain's shared-ledger refundCommitted() would re-refund
        // already-consumed sets on a level-≥2 abort (duplication). Returning null
        // routes it to the private-ledger tryStartSingleCraft path where the
        // per-level scheduler owns extraction and physical-recovery abort.
        if (machineType == MachineType.ARCANE_ITERATOR) return null;
        List<Ingredient> ingredients = CraftPacketUtils.extractIngredients(recipe);
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

        // Verify the cached BlockEntity is still valid
        if (myPos != null && resolveMachineLevel(player).isLoaded(myPos)) {
            BlockEntity current = resolveMachineLevel(player).getBlockEntity(myPos);
            if (current == null || current.isRemoved()) {
                player.sendSystemMessage(Component.translatable("rsi.error.machine_missing"));
                if (ledger != null && ledger.isCommitted()) {
                    ledger.refundCommitted(network, player);
                }
                return false;
            }
        }

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
            Reflect.getMethodOrThrow(be.getClass(), "wissenWandFunction", "wissenWandFunction").invoke(be);
            syncBlockEntity(be);
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-WR] wissenWandFunction invoke failed, rolling back", e);
            clearFilledSlots(!usingSharedLedger);
            return false;
        }
        return true;
    }

    private boolean startIteratorWithMaterials(ServerPlayer player, List<ItemStack> materials) {
        if (!checkWissen()) return false;
        List<?> pedestals;
        try {
            pedestals = (List<?>) Reflect.getMethodOrThrow(be.getClass(), "getPedestals", "getPedestals").invoke(be);
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
                clearFilledPedestals(!usingSharedLedger);
                return false;
            }
        }

        craftStarted = true;
        iteratorCraftProcessing = false;
        try {
            Reflect.getMethodOrThrow(be.getClass(), "wissenWandFunction", "wissenWandFunction").invoke(be);
            syncBlockEntity(be);
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-WR] wissenWandFunction invoke failed, rolling back", e);
            clearFilledPedestals(!usingSharedLedger);
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
            Reflect.getMethodOrThrow(be.getClass(), "wissenWandFunction", "wissenWandFunction").invoke(be);
            syncBlockEntity(be);
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-WR] wissenWandFunction invoke failed, rolling back", e);
            clearFilledSlotsForHandler(itemHandler, !usingSharedLedger);
            return false;
        }
        return true;
    }

    private boolean startCrystalRitualWithMaterials(ServerPlayer player, List<ItemStack> materials) {
        Object ritual = extractRitual(recipe);
        if (ritual == null) {
            try {
                ritual = Reflect.getMethodOrThrow(be.getClass(), "getCrystalRitual", "getCrystalRitual").invoke(be);
            } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI] Reflection probe failed", e); }
        }
        if (ritual == null) return false;

        Object area;
        try {
            area = Reflect.getMethodOrThrow(ritual.getClass(), "getArea", "getArea", be.getClass()).invoke(ritual, be);
        } catch (Exception e) {
            return false;
        }

        List<?> pedestals;
        try {
            ServerLevel level = resolveMachineLevel(player);
            pedestals = (List<?>) Reflect.getMethodOrThrow(WRReflection.crystalRitualClass, "getPedestalsWithArea", "getPedestalsWithArea", Level.class, BlockPos.class, WRReflection.ritualAreaClass)
                    .invoke(null, level, myPos, area);
        } catch (Exception e) {
            return false;
        }

        // Re-expand compacted materials to match recipe ingredient positions.
        // Both getRequiredMaterials() and extractIngredients() filter out the
        // crystal catalyst, so materials and recipeIngredients are aligned.
        List<Ingredient> recipeIngredients = CraftPacketUtils.extractIngredients(recipe);
        if (recipeIngredients == null || pedestals.size() < recipeIngredients.size()) return false;

        int pedOffset = pedestals.size() - recipeIngredients.size();

        this.pedestalRefs = pedestals;
        this.placedPedestalItems = new java.util.HashMap<>();
        int matIdx = 0;
        for (int slot = 0; slot < recipeIngredients.size() && matIdx < materials.size(); slot++) {
            Ingredient ing = recipeIngredients.get(slot);
            if (ing.isEmpty()) continue;

            ItemStack stack = materials.get(matIdx);
            matIdx++;
            if (stack.isEmpty()) continue;

            Object ped = pedestals.get(slot + pedOffset);
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
                clearFilledPedestals(!usingSharedLedger);
                return false;
            }
        }

        craftStarted = true;
        try {
            Reflect.getMethodOrThrow(be.getClass(), "wissenWandFunction", "wissenWandFunction").invoke(be);
            syncBlockEntity(be);
        } catch (Exception e) {
            clearFilledPedestals(!usingSharedLedger);
            return false;
        }
        return true;
    }

    @Override
    protected boolean isMachineCraftFinished(ServerLevel level, BlockEntity be) {
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
                // Check output slot first — the workbench places the result
                // into itemOutputHandler when the craft finishes.
                ItemStackHandler outHandler = getWorkbenchOutputHandler(be);
                if (outHandler != null && !outHandler.getStackInSlot(0).isEmpty())
                    return true;
                // If the workbench stopped crafting without producing output,
                // recover after a short grace period.
                if (!isWorkbenchCrafting() && waitTicks > 40) return true;
                return false;

            case ARCANE_ITERATOR:
                // Arcane Iterator reads but does NOT consume the center-pedestal
                // book — it enchants it +1 in place per run. Output = the evolved
                // center book. To reach the clicked level N we drive N runs,
                // re-supplying one side-material set + Wissen between each.
                for (Object ped : filledPedestals) {
                    syncBlockEntity(ped);
                }
                if (isIteratorCraftRunning()) {
                    iteratorCraftProcessing = true;
                    // Stall detection: if progress freezes (wissen/XP/health
                    // exhausted mid-run), stop and collect the level reached.
                    int progress = readIteratorProgress();
                    if (progress == lastCraftProgress) {
                        stallTicks++;
                        if (stallTicks > STALL_THRESHOLD) {
                            warnOnce("iterator_stalled", "[RSI-Batch-WR] Arcane Iterator craft stalled (progress={}), stopping", progress);
                            return true;
                        }
                    } else {
                        lastCraftProgress = progress;
                        stallTicks = 0;
                    }
                    return false;
                }
                // Not running. If a run was in progress, it just finished.
                if (iteratorCraftProcessing) {
                    iteratorCompletedRuns++;
                    // Guard: if the center book didn't change, the machine could
                    // not enchant further (target exceeds the enchantment's max
                    // level) → stop and collect what we have.
                    ItemStack centerNow = readIteratorCenterBook();
                    boolean advanced = !ItemStack.matches(centerNow, iteratorCenterBefore);
                    if (!advanced) {
                        warnOnce("iterator_capped", "[RSI-Batch-WR] Arcane Iterator center book unchanged after run {} — at max level, stopping", iteratorCompletedRuns);
                        return true;
                    }
                    if (iteratorCompletedRuns >= iteratorTargetLevel) {
                        return true; // reached target level
                    }
                    // More levels wanted: re-supply and re-trigger. If materials/
                    // Wissen run out, supplyNextIteratorLevel returns false →
                    // gracefully stop and collect the level achieved so far.
                    if (iteratorTargetLevel >= 2) {
                        player.sendSystemMessage(Component.translatable(
                                "rsi.wr.info.iterator_leveling",
                                iteratorCompletedRuns, iteratorTargetLevel));
                    }
                    if (supplyNextIteratorLevel()) {
                        return false; // next run started; keep polling
                    }
                    return true; // graceful stop
                }
                // Never saw the run process yet — keep waiting for wissenWandFunction
                // to propagate; MAX_WAIT_TICKS bounds the wait.
                return false;

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
                    // Pedestal items consumed. For crystal rituals the result
                    // may not be ready yet: ritual.start() consumes items but
                    // ritual.end() runs ticks later. Wait for startRitual=false.
                    if (isCrystalRitualRunning())
                        return false;
                    return true;
                }
                // fallback: original empty-slot check
                for (Object ped : filledPedestals) {
                    if (!getContainerItem(ped, 0).isEmpty()) return false;
                }
                if (isCrystalRitualRunning())
                    return false;
                return true;

            default:
                return false;
        }
    }

    private boolean isIteratorCraftRunning() {
        if (be == null) return false;
        try {
            java.lang.reflect.Field sc = be.getClass().getDeclaredField("startCraft");
            sc.setAccessible(true);
            if (!sc.getBoolean(be)) return false;
            java.lang.reflect.Field wic = be.getClass().getDeclaredField("wissenInCraft");
            wic.setAccessible(true);
            return wic.getInt(be) > 0;
        } catch (Exception e) { /* ignore */ }
        return false;
    }

    /** Read the combined craft progress from the arcane iterator.
     *  Sums wissenIsCraft + experienceIsCraft + healthIsCraft so stall
     *  detection catches a freeze in any one of them. */
    private int readIteratorProgress() {
        if (be == null) return -1;
        try {
            int total = 0;
            for (String name : new String[]{"wissenIsCraft", "experienceIsCraft", "healthIsCraft"}) {
                java.lang.reflect.Field f = be.getClass().getDeclaredField(name);
                f.setAccessible(true);
                total += f.getInt(be);
            }
            return total;
        } catch (Exception e) { /* ignore */ }
        return -1;
    }

    private boolean isCrystalRitualRunning() {
        if (be == null) return false;
        try {
            java.lang.reflect.Field f = Reflect.findField(be.getClass(), "startRitual").orElse(null);
            if (f != null) {
                f.setAccessible(true);
                return f.getBoolean(be);
            }
        } catch (Exception e) { /* ignore */ }
        return false;
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
                                ItemStack leftover = network.insertItem(stack, stack.getCount(),
                                        com.refinedmods.refinedstorage.api.util.Action.PERFORM);
                                if (!leftover.isEmpty()) {
                                    ItemHandlerHelper.giveItemToPlayer(player, leftover);
                                }
                            } else {
                                ItemHandlerHelper.giveItemToPlayer(player, stack);
                            }
                        }
                    }
                }
                break;
            }
            case ARCANE_ITERATOR: {
                // The Arcane Iterator places output on the main pedestal
                // (two blocks below). Durability-based recipes leave tools
                // on input pedestals with reduced durability — those are NOT
                // outputs and must not be collected.
                ItemStack expectedOut = com.huanghuang.rsintegration.crafting.RecipeIndex
                        .tryGetResultItem(recipe, player.serverLevel().registryAccess());

                // 1. Check main pedestal (canonical output location)
                try {
                    java.lang.reflect.Method getMain = Reflect.findMethod(
                            be.getClass(), "getMainPedestal", new Class<?>[0]);
                    if (getMain != null) {
                        Object mainPed = getMain.invoke(be);
                        if (mainPed != null) {
                            ItemStack mainStack = getContainerItem(mainPed, 0);
                            if (!mainStack.isEmpty()) {
                                setContainerItem(mainPed, 0, ItemStack.EMPTY);
                                syncBlockEntity(mainPed);
                                fromMachine = mainStack;
                            }
                        }
                    }
                } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI] mainPedestal probe failed", e); }

                // 2. Scan input pedestals — only collect items that match
                //    the expected recipe output (not durability-modified inputs).
                if (fromMachine.isEmpty() && pedestalRefs != null) {
                    for (Object ped : pedestalRefs) {
                        ItemStack stack = getContainerItem(ped, 0);
                        if (stack.isEmpty()) continue;
                        // Skip unchanged inputs
                        if (placedPedestalItems != null) {
                            ItemStack original = placedPedestalItems.get(ped);
                            if (original != null && ItemStack.isSameItemSameTags(stack, original))
                                continue;
                        } else if (filledPedestals != null && filledPedestals.contains(ped)) {
                            continue;
                        }
                        // This item differs from what was placed — but it could be
                        // a durability-reduced tool, not an output. Only collect
                        // if it matches the recipe's expected result.
                        if (!expectedOut.isEmpty() && !ItemStack.isSameItem(stack, expectedOut))
                            continue;
                        setContainerItem(ped, 0, ItemStack.EMPTY);
                        syncBlockEntity(ped);
                        if (fromMachine.isEmpty()) {
                            fromMachine = stack;
                        } else {
                            if (network != null) {
                                ItemStack leftover = network.insertItem(stack, stack.getCount(),
                                        com.refinedmods.refinedstorage.api.util.Action.PERFORM);
                                if (!leftover.isEmpty())
                                    ItemHandlerHelper.giveItemToPlayer(player, leftover);
                            } else {
                                ItemHandlerHelper.giveItemToPlayer(player, stack);
                            }
                        }
                    }
                }

                // 3. Fallback: getItemsResult() — handles durability-only /
                //    enchantment recipes where output is computed by the BE.
                if (fromMachine.isEmpty()) {
                    try {
                        var getResults = Reflect.findMethod(be.getClass(), "getItemsResult", new Class<?>[0]);
                        if (getResults != null) {
                            @SuppressWarnings("unchecked")
                            List<ItemStack> results = (List<ItemStack>) getResults.invoke(be);
                            if (results != null) {
                                for (ItemStack r : results) {
                                    if (r.isEmpty()) continue;
                                    if (fromMachine.isEmpty()) {
                                        fromMachine = r;
                                    } else {
                                        if (network != null) {
                                            ItemStack leftover = network.insertItem(r, r.getCount(),
                                                    com.refinedmods.refinedstorage.api.util.Action.PERFORM);
                                            if (!leftover.isEmpty())
                                                ItemHandlerHelper.giveItemToPlayer(player, leftover);
                                        } else {
                                            ItemHandlerHelper.giveItemToPlayer(player, r);
                                        }
                                    }
                                }
                            }
                        }
                    } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI] getItemsResult fallback failed", e); }
                }

                // 4. Last fallback: check the iterator's own slot
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
                                            ItemStack leftover = network.insertItem(taken, taken.getCount(),
                                                    com.refinedmods.refinedstorage.api.util.Action.PERFORM);
                                            if (!leftover.isEmpty()) {
                                                ItemHandlerHelper.giveItemToPlayer(player, leftover);
                                            }
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
                ServerLevel slevel = resolveMachineLevel(player);
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
        ItemStack expected = com.huanghuang.rsintegration.crafting.RecipeIndex.tryGetResultItem(
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

    @Override
    protected void clearMachineState(BlockEntity be, ServerPlayer player) {
        // Abort path. WR commits the ledger BEFORE placement, so items are on
        // the machine. In the shared-ledger (chain) path the chain's abort()
        // already refunds them via ledger.refundCommitted() — so we must only
        // VOID here, not return, or the item is duplicated. In the private-ledger
        // path (usingSharedLedger=false) nothing else refunds → return.
        // Read usingSharedLedger before resetState() clears it.
        this.player = player;
        boolean refund = !usingSharedLedger;
        clearFilledSlots(refund);
        clearFilledPedestals(refund);
        resetState();
        craftStarted = false;
    }

    @Override
    public void onBatchFinished(ServerPlayer player) {
        // Success path. The chain does ledger.reset() (no refund), so any item
        // still on the machine is real and uncounted → always return it. Normally
        // inputs were consumed and the output was already taken by collectResult,
        // so this is just a safety net for stragglers.
        this.player = player;
        clearFilledSlots(true);
        clearFilledPedestals(true);
        resetState();
        craftStarted = false;
    }

    @Override
    public BlockPos getMachinePos() {
        return myPos;
    }

    //
    // WR commits the ledger BEFORE placing items (unlike Goety). Whether a
    // clear must physically RETURN the machine contents to RS, or merely VOID
    // them, depends on who else refunds the committed ledger:
    //
    //   • Success (onBatchFinished): the chain does ledger.reset() (no refund),
    //     so any item still on the machine is real and uncounted → RETURN it.
    //   • Abort, shared ledger (usingSharedLedger): the chain's abort() calls
    //     ledger.refundCommitted(), which re-inserts every committed template.
    //     Returning here too would DOUBLE-refund (item duplication) → VOID only.
    //   • Abort, private ledger: nothing else refunds → RETURN it.
    //
    // Callers pass refund=true on success and refund=!usingSharedLedger on abort
    // so the shared-ledger abort path never duplicates.

    /** Recover (or void) items from Wissen Crystallizer / Arcane Workbench slots, then clear. */
    private void clearFilledSlots(boolean refund) {
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
                if (refund && !stack.isEmpty()) returnItem(stack);
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
                        if (refund) returnItem(result);
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
                        if (refund) returnItem(stack);
                        setContainerItem(be, i, ItemStack.EMPTY);
                    }
                }
            } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI] Reflection probe failed", e); }
        }
    }

    private void clearFilledSlotsForHandler(ItemStackHandler handler, boolean refund) {
        if (filledSlotIndices == null) return;
        for (int idx : filledSlotIndices) {
            try {
                ItemStack stack = handler.getStackInSlot(idx);
                if (refund && !stack.isEmpty()) returnItem(stack);
                handler.setStackInSlot(idx, ItemStack.EMPTY);
            } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI] Reflection probe failed", e); }
        }
    }

    private void clearFilledPedestals(boolean refund) {
        if (filledPedestals == null) return;
        for (Object ped : filledPedestals) {
            try {
                ItemStack stack = getContainerItem(ped, 0);
                if (refund && !stack.isEmpty()) returnItem(stack);
                setContainerItem(ped, 0, ItemStack.EMPTY);
                syncBlockEntity(ped);
            } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI] Reflection probe failed", e); }
        }
    }

    private void returnItem(ItemStack stack) {
        if (stack.isEmpty()) return;
        if (network != null) {
            ItemStack leftover = network.insertItem(stack, stack.getCount(),
                    com.refinedmods.refinedstorage.api.util.Action.PERFORM);
            if (!leftover.isEmpty()) {
                ItemHandlerHelper.giveItemToPlayer(player, leftover);
            }
        } else if (player != null) {
            if (!player.getInventory().add(stack)) {
                player.drop(stack, false);
            }
        }
    }

    private static ItemStackHandler getWorkbenchItemHandler(Object be) {
        try {
            java.lang.reflect.Field f = be.getClass().getDeclaredField("itemHandler");
            f.setAccessible(true);
            return (ItemStackHandler) f.get(be);
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-WR] Failed to get itemHandler from {}", be.getClass().getName(), e);
            return null;
        }
    }

    private static ItemStackHandler getWorkbenchOutputHandler(Object be) {
        try {
            java.lang.reflect.Field f = be.getClass().getDeclaredField("itemOutputHandler");
            f.setAccessible(true);
            return (ItemStackHandler) f.get(be);
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-WR] Failed to get itemOutputHandler from {}", be.getClass().getName(), e);
            return null;
        }
    }

    /**
     * Get the Forge IItemHandler for a block entity via the standard
     * Forge capability system. This is the most reliable way to access
     * any well-behaved Forge mod's inventory.
     */
    private static IItemHandler getForgeItemHandler(Object be) {
        return WRContainerHelper.getForgeItemHandler(be);
    }

    /**
     * Walk the class hierarchy to find and return the BE's actual
     * SimpleContainer field. Unlike createItemHandler(), this gives us
     * the live instance that the BE itself uses.
     */
    private static net.minecraft.world.SimpleContainer getLiveSimpleContainer(Object be) {
        return WRContainerHelper.getLiveSimpleContainer(be);
    }

    private static net.minecraft.world.SimpleContainer getSimpleContainer(Object be) {
        return WRContainerHelper.getSimpleContainer(be);
    }

    private static int getContainerSize(Object be) {
        return WRContainerHelper.getContainerSize(be);
    }

    private static ItemStack getContainerItem(Object be, int slot) {
        return WRContainerHelper.getContainerItem(be, slot);
    }

    private static void setContainerItem(Object be, int slot, ItemStack stack) {
        WRContainerHelper.setContainerItem(be, slot, stack);
    }

    private static void syncBlockEntity(Object be) {
        WRContainerHelper.syncBlockEntity(be);
    }

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

        // ARCANE_ITERATOR also drains XP levels and health from the player
        // during the craft.  If the player doesn't have enough, the craft
        // will stall mid-way and eventually time out.
        if (machineType == MachineType.ARCANE_ITERATOR) {
            int xpNeeded = readRecipeInt("getExperience");
            if (xpNeeded > 0 && player.experienceLevel < xpNeeded) {
                player.sendSystemMessage(Component.translatable(
                        "rsi.wr.error.insufficient_xp",
                        player.experienceLevel, xpNeeded));
                return false;
            }
            int hpNeeded = readRecipeInt("getHealth");
            if (hpNeeded > 0 && player.getHealth() <= hpNeeded) {
                player.sendSystemMessage(Component.translatable(
                        "rsi.wr.error.insufficient_health",
                        (int) player.getHealth(), hpNeeded));
                return false;
            }
        }
        return true;
    }

    private int readRecipeInt(String methodName) {
        try {
            java.lang.reflect.Method m = Reflect.findMethod(recipe.getClass(), methodName, new Class<?>[0]);
            if (m != null) return (int) m.invoke(recipe);
        } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI-Batch-WR] Failed to read recipe {}", methodName, e); }
        return 0;
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
        return -1; // can't determine — fail safe
    }

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

    public static List<String> getPlanWarnings(ServerPlayer player, Recipe<?> recipe,
                                               @Nullable ResourceLocation dim,
                                               @Nullable BlockPos pos) {
        List<String> warnings = new ArrayList<>();
        if (recipe == null) return warnings;

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
        } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI-Batch-WR] readCurrentWissen method failed", e); }
        try {
            java.lang.reflect.Field f = Reflect.findField(be.getClass(), "wissen").orElse(null);
            if (f != null) { f.setAccessible(true); return f.getInt(be); }
        } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI-Batch-WR] readCurrentWissen field failed", e); }
        return -1;
    }
}
