package com.huanghuang.rsintegration.mods.malum;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.batch.IBatchDelegate;
import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.ExtractionLedger;
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
import net.minecraftforge.items.ItemHandlerHelper;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class MalumBatchDelegate implements IBatchDelegate {

    // ── Shared class refs ────────────────────────────────────────
    private static volatile boolean classesLoaded;
    private static volatile Class<?> spiritAltarBEClass;
    private static volatile Class<?> altarCraftingHelperClass;

    private static void ensureClasses() {
        if (classesLoaded) return;
        try {
            spiritAltarBEClass = Class.forName(
                    "com.sammy.malum.common.block.curiosities.spirit_altar.SpiritAltarBlockEntity");
            altarCraftingHelperClass = Class.forName(
                    "com.sammy.malum.common.block.curiosities.spirit_altar.AltarCraftingHelper");
            classesLoaded = true;
        } catch (ClassNotFoundException e) {
            RSIntegrationMod.LOGGER.error("[RSI-Batch-Malum] Failed to load Malum classes", e);
        }
    }

    // ── Instance state ───────────────────────────────────────────
    private ServerPlayer player;
    private ServerLevel myLevel;
    private ResourceKey<Level> myDim;
    private BlockPos myPos;
    private Object altar;                 // SpiritAltarBlockEntity
    private Object invMain;              // main inventory
    private Object invSpirit;            // spirit inventory
    private Recipe<?> recipe;
    private ExtractionLedger ledger;
    private ExtractionLedger sharedLedger; // set by tryStartWithMaterials
    private INetwork network;
    private List<Integer> filledPedestalIndices;
    private List<?> pedestals;           // captured pedestal list
    private boolean usingSharedLedger;
    private boolean craftStarted;
    private boolean craftWasSeenActive;

    // ── IBatchDelegate impl ───────────────────────────────────────

    @Override
    public boolean validateAndInit(ServerPlayer player, ResourceLocation recipeId,
                                   @Nullable ResourceLocation dim, BlockPos pos) {
        ensureClasses();
        if (spiritAltarBEClass == null || altarCraftingHelperClass == null) {
            player.sendSystemMessage(Component.translatable("rsi.batch.error.mod_missing", "Malum"));
            return false;
        }

        ServerLevel level = CraftPacketUtils.resolveLevel(player.server, dim, player);
        if (level == null) {
            player.sendSystemMessage(Component.translatable("rsi.generic.error.dim_not_found"));
            return false;
        }
        this.myDim = level.dimension();
        this.myLevel = level;
        this.myPos = pos;
        this.player = player;

        if (!level.isLoaded(pos)) level.getChunk(pos);
        BlockEntity be = level.getBlockEntity(pos);
        if (be == null || !spiritAltarBEClass.isInstance(be)) {
            player.sendSystemMessage(Component.translatable("rsi.malum.error.altar_not_found"));
            return false;
        }
        this.altar = be;

        Recipe<?> foundRecipe = level.getRecipeManager().byKey(recipeId).orElse(null);
        if (foundRecipe == null) {
            player.sendSystemMessage(Component.translatable("rsi.generic.error.recipe_not_found", recipeId.toString()));
            return false;
        }
        this.recipe = foundRecipe;

        // Resolve inventories
        this.invMain = getField(altar, "inventory");
        this.invSpirit = getField(altar, "spiritInventory");
        if (invMain == null || invSpirit == null) {
            player.sendSystemMessage(Component.translatable("rsi.malum.error.inventory_error"));
            return false;
        }

        // Validate idle — null means we couldn't read the field; assume busy
        Boolean crafting = (Boolean) getField(altar, "isCrafting");
        if (crafting == null || Boolean.TRUE.equals(crafting)) {
            player.sendSystemMessage(Component.translatable("rsi.malum.warn.already_crafting"));
            return false;
        }
        try {
            boolean mainEmpty = (boolean) invMain.getClass().getMethod("isEmpty").invoke(invMain);
            if (!mainEmpty) {
                player.sendSystemMessage(Component.translatable("rsi.malum.warn.not_empty"));
                return false;
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-Malum] isEmpty() check failed — assuming busy", e);
            player.sendSystemMessage(Component.translatable("rsi.malum.warn.not_empty"));
            return false;
        }

        // Check pedestals are empty
        try {
            this.pedestals = capturePedestals();
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-Malum] Cannot capture pedestals — assuming busy", e);
            player.sendSystemMessage(Component.translatable("rsi.malum.warn.not_empty"));
            return false;
        }
        if (pedestals != null) {
            int emptyPedCount = countEmptyPedestalSlots(pedestals);
            if (emptyPedCount < pedestals.size()) {
                player.sendSystemMessage(Component.translatable("rsi.malum.warn.not_empty"));
                return false;
            }
        }

        RSIntegrationMod.LOGGER.debug("[RSI-Batch-Malum] validateAndInit OK: recipe={}", recipeId);
        return true;
    }

    @Override
    public boolean tryStartSingleCraft(ServerPlayer player) {
        this.player = player;
        this.ledger = new ExtractionLedger();
        this.network = CraftPacketUtils.resolveNetworkForCraft(player, myDim, myPos);

        Boolean crafting = (Boolean) getField(altar, "isCrafting");
        if (crafting == null || Boolean.TRUE.equals(crafting)) return false;

        try {
            boolean mainEmpty = (boolean) invMain.getClass().getMethod("isEmpty").invoke(invMain);
            if (!mainEmpty) return false;
        } catch (Exception ex) {
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-Malum] isEmpty() check failed in re-validation", ex);
            return false;
        }

        this.filledPedestalIndices = new ArrayList<>();

        try {
            pedestals = capturePedestals();
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.error("[RSI-Batch-Malum] Failed to capture pedestals", e);
            return false;
        }
        int emptyPedestalCount = countEmptyPedestalSlots(pedestals);

        Object inputObj = getField(recipe, "input");
        int centerCount = CraftPacketUtils.readIngredientCount(inputObj, 1);

        List<?> extraItems = (List<?>) getField(recipe, "extraItems");
        List<?> spirits = (List<?>) getField(recipe, "spirits");

        int extraCount = extraItems != null ? extraItems.size() : 0;
        int spiritCount = spirits != null ? spirits.size() : 0;

        if (extraCount > emptyPedestalCount) return false;

        // Phase 1: reserve all items via ledger
        // Use a labeled break so every failure path (exception or
        // material-shortage) runs the same cleanup that clears center
        // slot, pedestals, and spirit slots.
        boolean extractionOk = false;
        extraction: try {
            if (inputObj != null) {
                Ingredient centerIng = (Ingredient) getField(inputObj, "ingredient");
                if (centerIng != null) {
                    ItemStack stack = CraftPacketUtils.ensureMaterialAvailable(player, myDim, myPos, centerIng, centerCount, ledger);
                    if (stack.isEmpty()) break extraction;
                    setIHandlerSlot(invMain, 0, stack);
                }
            }

            int pedIdx = 0;
            if (extraItems != null) {
                for (int i = 0; i < extraCount; i++) {
                    Object eItem = extraItems.get(i);
                    Ingredient ing = (Ingredient) getField(eItem, "ingredient");
                    if (ing == null) continue;
                    int itemCount = CraftPacketUtils.readIngredientCount(eItem, 1);
                    ItemStack stack = CraftPacketUtils.ensureMaterialAvailable(player, myDim, myPos, ing, itemCount, ledger);
                    if (stack.isEmpty()) break extraction;
                    int placedIdx = placeOnNextEmptyPedestal(pedestals, pedIdx, stack);
                    filledPedestalIndices.add(placedIdx - 1);
                    pedIdx = placedIdx;
                }
            }

            if (spirits != null) {
                for (int i = 0; i < spiritCount; i++) {
                    Object swc = spirits.get(i);
                    int sCount = CraftPacketUtils.readIngredientCount(swc, 1);
                    Optional<Object> itemOpt = Reflect.invoke(swc, "getItem");
                    if (itemOpt.isEmpty() || !(itemOpt.get() instanceof Item)) {
                        break extraction;
                    }
                    Item spiritItem = (Item) itemOpt.get();
                    Ingredient spiritIng = Ingredient.of(spiritItem);
                    ItemStack stack = CraftPacketUtils.ensureMaterialAvailable(player, myDim, myPos, spiritIng, sCount, ledger);
                    if (stack.isEmpty()) break extraction;
                    setIHandlerSlot(invSpirit, i, stack);
                }
            }

            extractionOk = true;
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.error("[RSI-Batch-Malum] Extraction/placement failed:", e);
        }

        if (!extractionOk) {
            clearPedestals();
            try { setIHandlerSlot(invMain, 0, ItemStack.EMPTY); } catch (Exception ex) { RSIntegrationMod.LOGGER.debug("[RSI] Reflection probe failed", ex); }
            if (spirits != null) {
                for (int i = 0; i < spirits.size(); i++) {
                    try { setIHandlerSlot(invSpirit, i, ItemStack.EMPTY); } catch (Exception ex) { RSIntegrationMod.LOGGER.debug("[RSI] Reflection probe failed", ex); }
                }
            }
            return false;
        }

        // Phase 2: commit all extractions atomically
        if (!ledger.commit(network, player)) {
            RSIntegrationMod.LOGGER.error("[RSI-Batch-Malum] Ledger commit failed");
            clearPedestals();
            return false;
        }

        // Phase 3: let the altar's native tick() drive the crafting animation.
        // We call init() to force recipe recalculation so the altar recognizes
        // the items we just placed; its tick() will then set isCrafting=true,
        // increment progress, call consume(), and eventually call craft() which
        // spawns the result as an ItemEntity at the altar position.
        try {
            altar.getClass().getMethod("init").invoke(altar);
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.error("[RSI-Batch-Malum] init() after placement failed:", e);
            recoverFromAltar();
            return false;
        }

        Object recipeObj = getField(altar, "recipe");
        if (recipeObj == null) {
            RSIntegrationMod.LOGGER.error("[RSI-Batch-Malum] Altar did not recognize recipe after placement");
            player.sendSystemMessage(Component.translatable("rsi.batch.error.machine_mismatch"));
            recoverFromAltar();
            return false;
        }

        craftStarted = true;
        craftWasSeenActive = false;
        RSIntegrationMod.LOGGER.debug("[RSI-Batch-Malum] Craft started via native tick: recipe={}", recipe.getId());
        return true;
    }

    // ── Chain support: pre-reserved materials ────────────────────

    @Override
    @Nullable
    public List<com.huanghuang.rsintegration.crafting.IngredientSpec> getRequiredMaterials() {
        if (recipe == null) return null;
        return com.huanghuang.rsintegration.crafting.CraftPacketUtils.extractIngredientSpecs(recipe);
    }

    @Override
    public boolean tryStartWithMaterials(ServerPlayer player, List<ItemStack> materials,
                                         ExtractionLedger sharedLedger) {
        this.player = player;
        this.sharedLedger = sharedLedger;
        this.usingSharedLedger = true;

        Boolean crafting = (Boolean) getField(altar, "isCrafting");
        if (crafting == null || Boolean.TRUE.equals(crafting)) return false;

        try {
            boolean mainEmpty = (boolean) invMain.getClass().getMethod("isEmpty").invoke(invMain);
            if (!mainEmpty) return false;
        } catch (Exception ex) {
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-Malum] isEmpty() check failed in re-validation", ex);
            return false;
        }

        Object inputObj = getField(recipe, "input");
        List<?> extraItems = (List<?>) getField(recipe, "extraItems");
        List<?> spirits = (List<?>) getField(recipe, "spirits");

        int extraCount = extraItems != null ? extraItems.size() : 0;
        int spiritCount = spirits != null ? spirits.size() : 0;

        try {
            pedestals = capturePedestals();
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.error("[RSI-Batch-Malum] Failed to capture pedestals", e);
            return false;
        }
        int emptyPedestalCount = countEmptyPedestalSlots(pedestals);
        if (extraCount > emptyPedestalCount) return false;

        this.filledPedestalIndices = new ArrayList<>();

        // Materials order: [center, extra1..extraN, spirit1..spiritM]
        int matIdx = 0;

        try {
            // Center
            if (inputObj != null && matIdx < materials.size()) {
                ItemStack stack = materials.get(matIdx++);
                if (!stack.isEmpty()) {
                    setIHandlerSlot(invMain, 0, stack);
                }
            }

            // Extra items → pedestals
            int pedIdx = 0;
            if (extraItems != null) {
                for (int i = 0; i < extraCount && matIdx < materials.size(); i++) {
                    ItemStack stack = materials.get(matIdx++);
                    if (stack.isEmpty()) continue;
                    int placedIdx = placeOnNextEmptyPedestal(pedestals, pedIdx, stack);
                    filledPedestalIndices.add(placedIdx - 1);
                    pedIdx = placedIdx;
                }
            }

            // Spirits → invSpirit
            if (spirits != null) {
                for (int i = 0; i < spiritCount && matIdx < materials.size(); i++) {
                    ItemStack stack = materials.get(matIdx++);
                    if (!stack.isEmpty()) {
                        setIHandlerSlot(invSpirit, i, stack);
                    }
                }
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.error("[RSI-Batch-Malum] Material placement failed:", e);
            clearPedestals();
            try { setIHandlerSlot(invMain, 0, ItemStack.EMPTY); } catch (Exception ex) { RSIntegrationMod.LOGGER.debug("[RSI] Reflection probe failed", ex); }
            if (spirits != null) {
                for (int i = 0; i < spiritCount; i++) {
                    try { setIHandlerSlot(invSpirit, i, ItemStack.EMPTY); } catch (Exception ex) { RSIntegrationMod.LOGGER.debug("[RSI] Reflection probe failed", ex); }
                }
            }
            return false;
        }

        // Let the altar's native tick() drive the crafting animation
        try {
            altar.getClass().getMethod("init").invoke(altar);
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.error("[RSI-Batch-Malum] init() after placement failed:", e);
            clearPedestals();
            try { setIHandlerSlot(invMain, 0, ItemStack.EMPTY); } catch (Exception ex) { RSIntegrationMod.LOGGER.debug("[RSI] Reflection probe failed", ex); }
            if (spirits != null) {
                for (int i = 0; i < spiritCount; i++) {
                    try { setIHandlerSlot(invSpirit, i, ItemStack.EMPTY); } catch (Exception ex) { RSIntegrationMod.LOGGER.debug("[RSI] Reflection probe failed", ex); }
                }
            }
            return false;
        }

        Object recipeObj = getField(altar, "recipe");
        if (recipeObj == null) {
            RSIntegrationMod.LOGGER.error("[RSI-Batch-Malum] Altar did not recognize recipe after placement");
            player.sendSystemMessage(Component.translatable("rsi.batch.error.machine_mismatch"));
            clearPedestals();
            try { setIHandlerSlot(invMain, 0, ItemStack.EMPTY); } catch (Exception ex) { RSIntegrationMod.LOGGER.debug("[RSI] Reflection probe failed", ex); }
            if (spirits != null) {
                for (int i = 0; i < spiritCount; i++) {
                    try { setIHandlerSlot(invSpirit, i, ItemStack.EMPTY); } catch (Exception ex) { RSIntegrationMod.LOGGER.debug("[RSI] Reflection probe failed", ex); }
                }
            }
            return false;
        }

        craftStarted = true;
        craftWasSeenActive = false;
        RSIntegrationMod.LOGGER.debug("[RSI-Batch-Malum] Craft (with materials) started via native tick: recipe={}", recipe.getId());
        return true;
    }

    @Override
    public boolean isCraftComplete(ServerLevel level) {
        if (!craftStarted) return false;

        // The altar's tick() sets isCrafting=true when recipe is active,
        // and sets isCrafting=false when recipe becomes null (items consumed).
        // We detect the true→false transition to know the craft finished.
        Boolean crafting = (Boolean) getField(altar, "isCrafting");
        if (Boolean.TRUE.equals(crafting)) {
            craftWasSeenActive = true;
            return false;
        }

        if (craftWasSeenActive) {
            craftWasSeenActive = false;
            RSIntegrationMod.LOGGER.debug("[RSI-Batch-Malum] Native craft finished (isCrafting false transition)");
            return true;
        }

        // Fallback: scan for ItemEntity near the altar (it may have finished
        // between our poll ticks and we missed the transition)
        ItemStack expected = com.huanghuang.rsintegration.crafting.ModRecipeIndex
                .tryGetResultItem(recipe, level.registryAccess());
        if (!expected.isEmpty() && myPos != null && level.isLoaded(myPos)) {
            var entities = level.getEntitiesOfClass(
                    net.minecraft.world.entity.item.ItemEntity.class,
                    new net.minecraft.world.phys.AABB(myPos).inflate(3),
                    e -> ItemStack.isSameItemSameTags(e.getItem(), expected)
                            || ItemStack.isSameItem(e.getItem(), expected));
            if (!entities.isEmpty()) return true;
        }

        return false;
    }

    @Override
    public ItemStack collectResult(ServerPlayer player) {
        ItemStack expected = com.huanghuang.rsintegration.crafting.ModRecipeIndex
                .tryGetResultItem(recipe, player.serverLevel().registryAccess());
        if (expected.isEmpty()) return ItemStack.EMPTY;

        // 1. Scan for ItemEntity spawned by altar's craft() method
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
                RSIntegrationMod.LOGGER.debug("[RSI-Batch-Malum] Collected ItemEntity: {} x{}",
                        collected.getHoverName().getString(), collected.getCount());
                return collected;
            }
        }

        // 2. Fallback: check player inventory
        var inv = player.getInventory();
        for (ItemStack stack : inv.items) {
            if (ItemStack.isSameItemSameTags(stack, expected)
                    || ItemStack.isSameItem(stack, expected)) {
                return stack.split(expected.getCount());
            }
        }
        for (ItemStack stack : inv.offhand) {
            if (ItemStack.isSameItemSameTags(stack, expected)
                    || ItemStack.isSameItem(stack, expected)) {
                return stack.split(expected.getCount());
            }
        }

        return ItemStack.EMPTY;
    }

    @Override
    public void onBatchFailed(ServerPlayer player, String reason) {
        craftStarted = false;
        craftWasSeenActive = false;
        // Retrieve items from altar slots before clearing, so we can return
        // them to their source instead of creating duplicate items.
        if (!usingSharedLedger && ledger != null && ledger.isCommitted()) {
            recoverFromAltar();
        } else {
            clearPedestals();
            try { setIHandlerSlot(invMain, 0, ItemStack.EMPTY); } catch (Exception ex) { RSIntegrationMod.LOGGER.debug("[RSI] Reflection probe failed", ex); }
            try {
                List<?> spirits = (List<?>) getField(recipe, "spirits");
                if (spirits != null) {
                    for (int i = 0; i < spirits.size(); i++) {
                        setIHandlerSlot(invSpirit, i, ItemStack.EMPTY);
                    }
                }
            } catch (Exception ex) { RSIntegrationMod.LOGGER.debug("[RSI] Reflection probe failed", ex); }
        }
        ledger = null;
        sharedLedger = null;
        network = null;
        usingSharedLedger = false;
    }

    /** Retrieve items from altar slots and return them to the network/player. */
    private void recoverFromAltar() {
        // Recover main slot
        try {
            ItemStack mainStack = (ItemStack) invMain.getClass().getMethod("getStackInSlot", int.class).invoke(invMain, 0);
            if (mainStack != null && !mainStack.isEmpty()) {
                returnItem(mainStack);
            }
            setIHandlerSlot(invMain, 0, ItemStack.EMPTY);
        } catch (Exception ex) { RSIntegrationMod.LOGGER.debug("[RSI] Reflection probe failed", ex); }

        // Recover pedestal slots
        if (pedestals != null && filledPedestalIndices != null) {
            for (int idx : filledPedestalIndices) {
                if (idx < 0 || idx >= pedestals.size()) continue;
                try {
                    Object ap = pedestals.get(idx);
                    Object inv = ap.getClass().getMethod("getSuppliedInventory").invoke(ap);
                    ItemStack stack = (ItemStack) inv.getClass().getMethod("getStackInSlot", int.class).invoke(inv, 0);
                    if (stack != null && !stack.isEmpty()) {
                        returnItem(stack);
                    }
                    inv.getClass().getMethod("setStackInSlot", int.class, ItemStack.class).invoke(inv, 0, ItemStack.EMPTY);
                } catch (Exception ex) { RSIntegrationMod.LOGGER.debug("[RSI] Reflection probe failed", ex); }
            }
        }

        // Recover spirit slots
        try {
            List<?> spirits = (List<?>) getField(recipe, "spirits");
            if (spirits != null) {
                for (int i = 0; i < spirits.size(); i++) {
                    try {
                        ItemStack stack = (ItemStack) invSpirit.getClass().getMethod("getStackInSlot", int.class).invoke(invSpirit, i);
                        if (stack != null && !stack.isEmpty()) {
                            returnItem(stack);
                        }
                        setIHandlerSlot(invSpirit, i, ItemStack.EMPTY);
                    } catch (Exception ex) { RSIntegrationMod.LOGGER.debug("[RSI] Reflection probe failed", ex); }
                }
            }
        } catch (Exception ex) { RSIntegrationMod.LOGGER.debug("[RSI] Reflection probe failed", ex); }

        // Reset altar state
        try {
            altar.getClass().getMethod("init").invoke(altar);
            setField(altar, "isCrafting", false);
        } catch (Exception ex) { RSIntegrationMod.LOGGER.debug("[RSI] Reflection probe failed", ex); }
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

    @Override
    public void onBatchFinished(ServerPlayer player) {
        craftStarted = false;
        craftWasSeenActive = false;
        clearPedestals();
        try {
            setIHandlerSlot(invMain, 0, ItemStack.EMPTY);
        } catch (Exception ex) { RSIntegrationMod.LOGGER.debug("[RSI] Reflection probe failed", ex); }
        try {
            List<?> spirits = (List<?>) getField(recipe, "spirits");
            if (spirits != null) {
                for (int i = 0; i < spirits.size(); i++) {
                    setIHandlerSlot(invSpirit, i, ItemStack.EMPTY);
                }
            }
        } catch (Exception ex) { RSIntegrationMod.LOGGER.debug("[RSI] Reflection probe failed", ex); }
        ledger = null;
        sharedLedger = null;
        network = null;
        usingSharedLedger = false;
    }

    @Override
    public BlockPos getMachinePos() {
        return myPos;
    }

    // ── Pedestal helpers ─────────────────────────────────────────

    private List<?> capturePedestals() throws Exception {
        return (List<?>) altarCraftingHelperClass.getMethod("capturePedestals", Level.class, BlockPos.class)
                .invoke(null, myLevel, myPos);
    }

    private int countEmptyPedestalSlots(List<?> pedestals) {
        int count = 0;
        for (Object ap : pedestals) {
            try {
                Object inv = ap.getClass().getMethod("getSuppliedInventory").invoke(ap);
                boolean empty = (boolean) inv.getClass().getMethod("isEmpty").invoke(inv);
                if (empty) count++;
            } catch (Exception ex) { RSIntegrationMod.LOGGER.debug("[RSI] Reflection probe failed", ex); }
        }
        return count;
    }

    private int placeOnNextEmptyPedestal(List<?> pedestals, int startIdx, ItemStack stack) throws Exception {
        for (int i = startIdx; i < pedestals.size(); i++) {
            Object ap = pedestals.get(i);
            Object inv = ap.getClass().getMethod("getSuppliedInventory").invoke(ap);
            boolean empty = (boolean) inv.getClass().getMethod("isEmpty").invoke(inv);
            if (empty) {
                inv.getClass().getMethod("setStackInSlot", int.class, ItemStack.class)
                        .invoke(inv, 0, stack);
                return i + 1;
            }
        }
        throw new IllegalStateException("No empty pedestal slot found from index " + startIdx);
    }

    private void clearPedestals() {
        if (pedestals == null) return;
        for (int idx : filledPedestalIndices != null ? filledPedestalIndices : List.<Integer>of()) {
            if (idx < 0 || idx >= pedestals.size()) continue;
            try {
                Object ap = pedestals.get(idx);
                Object inv = ap.getClass().getMethod("getSuppliedInventory").invoke(ap);
                inv.getClass().getMethod("setStackInSlot", int.class, ItemStack.class)
                        .invoke(inv, 0, ItemStack.EMPTY);
            } catch (Exception ex) { RSIntegrationMod.LOGGER.debug("[RSI] Reflection probe failed", ex); }
        }
    }

    // ── Reflection helpers ───────────────────────────────────────

    @Nullable
    private static Object getField(Object obj, String name) {
        Class<?> clazz = obj.getClass();
        while (clazz != null && clazz != Object.class) {
            try {
                java.lang.reflect.Field f = clazz.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(obj);
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.debug("[RSI-Batch-Malum] getFieldValue probe failed", e);
                return null;
            }
        }
        return null;
    }

    private static void setField(Object obj, String name, Object value) throws Exception {
        Class<?> clazz = obj.getClass();
        while (clazz != null && clazz != Object.class) {
            try {
                java.lang.reflect.Field f = clazz.getDeclaredField(name);
                f.setAccessible(true);
                f.set(obj, value);
                return;
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static void setIHandlerSlot(Object handler, int slot, ItemStack stack) throws Exception {
        handler.getClass().getMethod("setStackInSlot", int.class, ItemStack.class).invoke(handler, slot, stack);
    }

    // ── Plan warnings ─────────────────────────────────────────────

    public static List<String> getPlanWarnings(ServerPlayer player, Recipe<?> recipe,
                                               @Nullable ResourceLocation dim,
                                               @Nullable BlockPos pos) {
        List<String> warnings = new ArrayList<>();
        ensureClasses();

        // Check for spirit requirements on the recipe
        List<?> spirits = null;
        try {
            java.lang.reflect.Field f = recipe.getClass().getDeclaredField("spirits");
            f.setAccessible(true);
            spirits = (List<?>) f.get(recipe);
        } catch (Exception e) { /* recipe has no spirit requirements */ }
        if (spirits == null) return warnings;

        List<String> spiritNames = new ArrayList<>();
        for (Object swc : spirits) {
            try {
                java.lang.reflect.Field typeF = swc.getClass().getDeclaredField("type");
                typeF.setAccessible(true);
                Object type = typeF.get(swc);
                java.lang.reflect.Field countF = swc.getClass().getDeclaredField("count");
                countF.setAccessible(true);
                int count = countF.getInt(swc);
                java.lang.reflect.Field idF = type.getClass().getDeclaredField("identifier");
                idF.setAccessible(true);
                String id = (String) idF.get(type);
                spiritNames.add(count + "x " + id);
            } catch (Exception e) { /* skip malformed entry */ }
        }

        // If altar is bound, check actual spirit inventory
        if (dim != null && pos != null && spiritAltarBEClass != null) {
            try {
                ServerLevel level = CraftPacketUtils.resolveLevel(player.server, dim, player);
                if (level != null && level.isLoaded(pos)) {
                    BlockEntity be = level.getBlockEntity(pos);
                    if (be != null && spiritAltarBEClass.isInstance(be)) {
                        Object spiritInv = getFieldStatic(be, "spiritInventory");
                        if (spiritInv != null) {
                            int slots = (int) spiritInv.getClass().getMethod("getSlots").invoke(spiritInv);
                            if (slots < spirits.size()) {
                                warnings.add(Component.translatable(
                                        "rsi.malum.warn.spirit_slots_insufficient",
                                        spirits.size(), slots).getString());
                            }
                        }
                    }
                }
            } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI-Batch-Malum] Plan spirit check failed", e); }
        }

        if (!spiritNames.isEmpty()) {
            warnings.add(Component.translatable(
                    "rsi.malum.warn.spirit_required",
                    String.join(", ", spiritNames)).getString());
        }

        return warnings;
    }

    private static Object getFieldStatic(Object obj, String name) {
        Class<?> clazz = obj.getClass();
        while (clazz != null && clazz != Object.class) {
            try {
                java.lang.reflect.Field f = clazz.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(obj);
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }
}
