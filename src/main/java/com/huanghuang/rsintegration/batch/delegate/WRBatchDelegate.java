package com.huanghuang.rsintegration.batch.delegate;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.batch.IBatchDelegate;
import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.integration.AltarBindingRegistry;
import com.huanghuang.rsintegration.integration.RSIntegration;
import com.refinedmods.refinedstorage.api.network.INetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
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

    private static void ensureClasses() {
        if (classesLoaded) return;
        classesLoaded = true;
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
            player.sendSystemMessage(Component.translatable("rsi.generic.error.machine_not_found"));
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
            player.sendSystemMessage(Component.translatable("rsi.generic.error.unsupported_machine", be.getClass().getName()));
            return false;
        }

        Recipe<?> foundRecipe = level.getRecipeManager().byKey(recipeId).orElse(null);
        if (foundRecipe == null) {
            player.sendSystemMessage(Component.translatable("rsi.generic.error.recipe_not_found", recipeId.toString()));
            return false;
        }
        this.recipe = foundRecipe;

        // Validate idle state per machine type
        if (!validateIdle(player, level)) return false;

        this.waitTicks = 0;

        RSIntegrationMod.LOGGER.debug("[RSI-Batch-WR] validateAndInit OK: recipe={} type={}", recipeId, machineType);
        return true;
    }

    private boolean validateIdle(ServerPlayer player, ServerLevel level) {
        switch (machineType) {
            case WISSEN_CRYSTALLIZER: {
                int size = getContainerSize(be);
                for (int i = 0; i < size; i++) {
                    ItemStack stack = getContainerItem(be, i);
                    if (!stack.isEmpty()) return false;
                }
                break;
            }
            case ARCANE_ITERATOR: {
                try {
                    pedestalRefs = (List<?>) be.getClass().getMethod("getPedestals").invoke(be);
                    if (pedestalRefs != null) {
                        for (Object ped : pedestalRefs) {
                            ItemStack stack = getContainerItem(ped, 0);
                            if (!stack.isEmpty()) return false;
                        }
                    }
                } catch (Exception ignored) {}
                break;
            }
            case ARCANE_WORKBENCH: {
                try {
                    ItemStackHandler handler = (ItemStackHandler) be.getClass()
                            .getField("itemHandler").get(be);
                    for (int i = 0; i < handler.getSlots(); i++) {
                        if (!handler.getStackInSlot(i).isEmpty()) return false;
                    }
                } catch (Exception e) {
                    RSIntegrationMod.LOGGER.warn("[RSI-Batch-WR] Failed to check workbench idle state", e);
                }
                break;
            }
            case CRYSTAL_RITUAL: {
                try {
                    Object crystalItem = be.getClass().getMethod("getCrystalItem").invoke(be);
                    if (crystalItem == null || ((ItemStack) crystalItem).isEmpty()) return false;
                } catch (Exception e) {
                    RSIntegrationMod.LOGGER.warn("[RSI-Batch-WR] Failed to check crystal item", e);
                }
                break;
            }
            default:
                return false;
        }
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
            network = null;
        }
        return ok;
    }

    // ── Wissen Crystallizer ──────────────────────────────────────

    private boolean tryStartWissenCrystallizer(ServerPlayer player, List<Ingredient> ingredients) {
        int totalSlots = getContainerSize(be);
        if (totalSlots <= 0 || ingredients.size() > totalSlots) return false;

        // Phase 1: reserve all ingredients + validate slots
        List<ItemStack> templates = new ArrayList<>();
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

        // Phase 2: commit all extractions atomically
        if (!ledger.commit(network, player)) return false;

        // Phase 3: place items
        for (int i = 0; i < templates.size(); i++) {
            ItemStack taken = templates.get(i);
            if (!taken.isEmpty()) {
                setContainerItem(be, i, taken);
            }
        }

        craftStarted = true;
        try {
            be.getClass().getMethod("wissenWandFunction").invoke(be);
        } catch (Exception ignored) {
            clearFilledSlots();
            refundAll();
            return false;
        }

        return true;
    }

    // ── Arcane Iterator ──────────────────────────────────────────

    private boolean tryStartArcaneIterator(ServerPlayer player, List<Ingredient> ingredients) {
        List<?> pedestals;
        try {
            pedestals = (List<?>) be.getClass().getMethod("getPedestals").invoke(be);
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

        // Phase 2: commit
        if (!ledger.commit(network, player)) return false;

        // Phase 3: place items
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
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.warn("[RSI-Batch-WR] Failed to place item on ArcaneIterator pedestal {}: {}", i, e.getMessage());
                clearFilledPedestals();
                refundAll();
                return false;
            }
        }

        craftStarted = true;
        try {
            be.getClass().getMethod("wissenWandFunction").invoke(be);
        } catch (Exception ignored) {
            clearFilledPedestals();
            refundAll();
            return false;
        }

        return true;
    }

    // ── Arcane Workbench ─────────────────────────────────────────

    private boolean tryStartArcaneWorkbench(ServerPlayer player, List<Ingredient> ingredients) {
        ItemStackHandler itemHandler;
        try {
            itemHandler = (ItemStackHandler) be.getClass().getField("itemHandler").get(be);
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-WR] Failed to get itemHandler from ArcaneWorkbench", e);
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

        // Phase 2: commit
        if (!ledger.commit(network, player)) return false;

        // Phase 3: place items
        for (int i = 0; i < templates.size(); i++) {
            ItemStack taken = templates.get(i);
            if (!taken.isEmpty()) {
                itemHandler.setStackInSlot(i, taken);
            }
        }

        craftStarted = true;
        try {
            be.getClass().getMethod("wissenWandFunction").invoke(be);
        } catch (Exception ignored) {
            clearFilledSlotsForHandler(itemHandler);
            refundAll();
            return false;
        }

        return true;
    }

    // ── Crystal Ritual ───────────────────────────────────────────

    private boolean tryStartCrystalRitual(ServerPlayer player, List<Ingredient> ingredients) {
        Object ritual = extractRitual(recipe);
        if (ritual == null) {
            try {
                ritual = be.getClass().getMethod("getCrystalRitual").invoke(be);
            } catch (Exception ignored) {}
        }
        if (ritual == null) {
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-WR] Failed to extract ritual data");
            return false;
        }

        Object area;
        try {
            area = ritual.getClass().getMethod("getArea", be.getClass()).invoke(ritual, be);
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-WR] Failed to get ritual area", e);
            return false;
        }

        List<?> pedestals;
        try {
            ServerLevel level = player.serverLevel();
            pedestals = (List<?>) crystalRitualClass
                    .getMethod("getPedestalsWithArea", Level.class, BlockPos.class, ritualAreaClass)
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
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.warn("[RSI-Batch-WR] Failed to place item on crystal pedestal {}: {}", i, e.getMessage());
                clearFilledPedestals();
                refundAll();
                return false;
            }
        }

        craftStarted = true;
        try {
            be.getClass().getMethod("wissenWandFunction").invoke(be);
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-WR] Failed to invoke wissenWandFunction on crystal block", e);
            clearFilledPedestals();
            refundAll();
            return false;
        }

        return true;
    }

    // ── Chain support: pre-reserved materials ────────────────────

    @Override
    @Nullable
    public List<IngredientSpec> getRequiredMaterials() {
        if (recipe == null) return null;
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
        int totalSlots = getContainerSize(be);
        if (totalSlots <= 0 || materials.size() > totalSlots) return false;

        // Place items in slots
        for (int i = 0; i < materials.size(); i++) {
            ItemStack stack = materials.get(i);
            if (stack.isEmpty()) continue;
            ItemStack existing = getContainerItem(be, i);
            if (!existing.isEmpty()) {
                if (ItemStack.isSameItemSameTags(stack, existing)) continue;
                return false;
            }
            setContainerItem(be, i, stack);
            filledSlotIndices.add(i);
        }

        craftStarted = true;
        try {
            be.getClass().getMethod("wissenWandFunction").invoke(be);
        } catch (Exception ignored) {
            clearFilledSlots();
            return false;
        }
        return true;
    }

    private boolean startIteratorWithMaterials(ServerPlayer player, List<ItemStack> materials) {
        List<?> pedestals;
        try {
            pedestals = (List<?>) be.getClass().getMethod("getPedestals").invoke(be);
        } catch (Exception e) {
            return false;
        }
        this.pedestalRefs = pedestals;
        if (pedestals.size() < materials.size()) return false;

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
            } catch (Exception e) {
                clearFilledPedestals();
                return false;
            }
        }

        craftStarted = true;
        try {
            be.getClass().getMethod("wissenWandFunction").invoke(be);
        } catch (Exception ignored) {
            clearFilledPedestals();
            return false;
        }
        return true;
    }

    private boolean startWorkbenchWithMaterials(ServerPlayer player, List<ItemStack> materials) {
        ItemStackHandler itemHandler;
        try {
            itemHandler = (ItemStackHandler) be.getClass().getField("itemHandler").get(be);
        } catch (Exception e) {
            return false;
        }
        if (materials.size() > itemHandler.getSlots()) return false;

        for (int i = 0; i < materials.size(); i++) {
            ItemStack stack = materials.get(i);
            if (stack.isEmpty()) continue;
            ItemStack existing = itemHandler.getStackInSlot(i);
            if (!existing.isEmpty()) {
                if (ItemStack.isSameItemSameTags(stack, existing)) continue;
                return false;
            }
            itemHandler.setStackInSlot(i, stack);
            filledSlotIndices.add(i);
        }

        craftStarted = true;
        try {
            be.getClass().getMethod("wissenWandFunction").invoke(be);
        } catch (Exception ignored) {
            clearFilledSlotsForHandler(itemHandler);
            return false;
        }
        return true;
    }

    private boolean startCrystalRitualWithMaterials(ServerPlayer player, List<ItemStack> materials) {
        Object ritual = extractRitual(recipe);
        if (ritual == null) {
            try {
                ritual = be.getClass().getMethod("getCrystalRitual").invoke(be);
            } catch (Exception ignored) {}
        }
        if (ritual == null) return false;

        Object area;
        try {
            area = ritual.getClass().getMethod("getArea", be.getClass()).invoke(ritual, be);
        } catch (Exception e) {
            return false;
        }

        List<?> pedestals;
        try {
            ServerLevel level = player.serverLevel();
            pedestals = (List<?>) crystalRitualClass
                    .getMethod("getPedestalsWithArea", Level.class, BlockPos.class, ritualAreaClass)
                    .invoke(null, level, myPos, area);
        } catch (Exception e) {
            return false;
        }
        if (pedestals.size() < materials.size()) return false;

        this.pedestalRefs = pedestals;
        for (int i = 0; i < materials.size(); i++) {
            ItemStack stack = materials.get(i);
            if (stack.isEmpty()) continue;
            Object ped = pedestals.get(i);
            ItemStack existing = getContainerItem(ped, 0);
            if (!existing.isEmpty()) {
                ItemHandlerHelper.giveItemToPlayer(player, existing.copy());
            }
            try {
                setContainerItem(ped, 0, stack);
                filledPedestals.add(ped);
            } catch (Exception e) {
                clearFilledPedestals();
                return false;
            }
        }

        craftStarted = true;
        try {
            be.getClass().getMethod("wissenWandFunction").invoke(be);
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
            case ARCANE_WORKBENCH:
                // Check if all slots we filled are now empty
                for (int idx : filledSlotIndices) {
                    ItemStack stack;
                    if (machineType == MachineType.WISSEN_CRYSTALLIZER) {
                        stack = getContainerItem(be, idx);
                    } else {
                        try {
                            ItemStackHandler handler = (ItemStackHandler) be.getClass()
                                    .getField("itemHandler").get(be);
                            stack = handler.getStackInSlot(idx);
                        } catch (Exception e) {
                            return false;
                        }
                    }
                    if (!stack.isEmpty()) return false;
                }
                return true;

            case ARCANE_ITERATOR:
            case CRYSTAL_RITUAL:
                // Check if all pedestals we filled are now empty
                for (Object ped : filledPedestals) {
                    ItemStack stack = getContainerItem(ped, 0);
                    if (!stack.isEmpty()) return false;
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
                int size = getContainerSize(be);
                if (size > 0) {
                    ItemStack result = getContainerItem(be, size - 1);
                    if (!result.isEmpty()) {
                        setContainerItem(be, size - 1, ItemStack.EMPTY);
                        fromMachine = result;
                    }
                }
                break;
            }
            case ARCANE_ITERATOR: {
                try {
                    ItemStack result = getContainerItem(be, 0);
                    if (!result.isEmpty()) {
                        setContainerItem(be, 0, ItemStack.EMPTY);
                        fromMachine = result;
                    }
                } catch (Exception ignored) {}
                break;
            }
            case ARCANE_WORKBENCH: {
                try {
                    ItemStackHandler handler = (ItemStackHandler) be.getClass()
                            .getField("itemHandler").get(be);
                    int lastSlot = handler.getSlots() - 1;
                    for (int i = lastSlot; i >= 0; i--) {
                        ItemStack stack = handler.getStackInSlot(i);
                        if (!stack.isEmpty() && !isInputSlot(i)) {
                            handler.setStackInSlot(i, ItemStack.EMPTY);
                            fromMachine = stack;
                            break;
                        }
                    }
                } catch (Exception ignored) {}
                break;
            }
            case CRYSTAL_RITUAL: {
                try {
                    ItemStack result = getContainerItem(be, 0);
                    if (!result.isEmpty()) {
                        setContainerItem(be, 0, ItemStack.EMPTY);
                        fromMachine = result;
                    }
                } catch (Exception ignored) {}
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
        } catch (Exception ignored) {}

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
        clearFilledSlots();
        clearFilledPedestals();
        if (!usingSharedLedger) {
            refundAll();
        }
        ledger = null;
        sharedLedger = null;
        network = null;
        usingSharedLedger = false;
        craftStarted = false;
    }

    @Override
    public void onBatchFinished(ServerPlayer player) {
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

    private void clearFilledSlots() {
        if (filledSlotIndices == null) return;
        for (int idx : filledSlotIndices) {
            try {
                switch (machineType) {
                    case WISSEN_CRYSTALLIZER:
                        setContainerItem(be, idx, ItemStack.EMPTY);
                        break;
                    case ARCANE_WORKBENCH:
                        try {
                            ItemStackHandler handler = (ItemStackHandler) be.getClass()
                                    .getField("itemHandler").get(be);
                            handler.setStackInSlot(idx, ItemStack.EMPTY);
                        } catch (Exception ignored) {}
                        break;
                }
            } catch (Exception ignored) {}
        }
    }

    private void clearFilledSlotsForHandler(ItemStackHandler handler) {
        if (filledSlotIndices == null) return;
        for (int idx : filledSlotIndices) {
            try {
                handler.setStackInSlot(idx, ItemStack.EMPTY);
            } catch (Exception ignored) {}
        }
    }

    private void clearFilledPedestals() {
        if (filledPedestals == null) return;
        for (Object ped : filledPedestals) {
            try {
                setContainerItem(ped, 0, ItemStack.EMPTY);
            } catch (Exception ignored) {}
        }
    }

    // ── Refund ───────────────────────────────────────────────────

    private void refundAll() {
        if (ledger == null || !ledger.isCommitted()) return;
        List<Ingredient> ingredients = CraftPacketUtils.extractIngredients(recipe);
        if (ingredients == null) return;
        for (Ingredient ing : ingredients) {
            if (ing.isEmpty()) continue;
            ItemStack[] opts = ing.getItems();
            if (opts.length > 0 && !opts[0].isEmpty()) {
                ItemStack refund = opts[0].copyWithCount(1);
                if (network != null) {
                    network.insertItem(refund, 1, com.refinedmods.refinedstorage.api.util.Action.PERFORM);
                } else if (player != null) {
                    ItemHandlerHelper.giveItemToPlayer(player, refund);
                }
            }
        }
    }

    // ── Container helpers ────────────────────────────────────────

    private static int getContainerSize(Object be) {
        try {
            return (int) be.getClass().getMethod("getContainerSize").invoke(be);
        } catch (Exception e) {
            return -1;
        }
    }

    private static ItemStack getContainerItem(Object be, int slot) {
        try {
            return (ItemStack) be.getClass().getMethod("getItem", int.class).invoke(be, slot);
        } catch (Exception e) {
            try {
                Object handler = be.getClass().getMethod("getItemHandler").invoke(be);
                return (ItemStack) handler.getClass().getMethod("getStackInSlot", int.class).invoke(handler, slot);
            } catch (Exception e2) {
                return ItemStack.EMPTY;
            }
        }
    }

    private static void setContainerItem(Object be, int slot, ItemStack stack) {
        try {
            be.getClass().getMethod("setItem", int.class, ItemStack.class).invoke(be, slot, stack);
        } catch (Exception e) {
            try {
                Object handler = be.getClass().getMethod("getItemHandler").invoke(be);
                handler.getClass().getMethod("setStackInSlot", int.class, ItemStack.class)
                        .invoke(handler, slot, stack);
            } catch (Exception e2) {
                RSIntegrationMod.LOGGER.warn("[RSI-Batch-WR] Failed to set container item", e2);
            }
        }
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
                    } catch (Exception ignored) {}
                }
            }
            clazz = clazz.getSuperclass();
        }
        return null;
    }
}
