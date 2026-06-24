package com.huanghuang.rsintegration.mods.malum;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.batch.IBatchDelegate;
import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.ExtractionLedger;
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
    private ItemStack capturedResult = ItemStack.EMPTY; // recipe result from completeManually()

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

        // Validate idle
        Boolean crafting = (Boolean) getField(altar, "isCrafting");
        if (Boolean.TRUE.equals(crafting)) {
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
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-Malum] isEmpty() check failed", e);
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
        if (Boolean.TRUE.equals(crafting)) return false;

        try {
            boolean mainEmpty = (boolean) invMain.getClass().getMethod("isEmpty").invoke(invMain);
            if (!mainEmpty) return false;
        } catch (Exception ex) { RSIntegrationMod.LOGGER.debug("[RSI] Reflection probe failed", ex); }

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
                    Item spiritItem;
                    try {
                        spiritItem = (Item) swc.getClass().getMethod("getItem").invoke(swc);
                    } catch (Exception e) {
                        break extraction;
                    }
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

        // Phase 3: manually complete the craft (Eidolon-style).
        // We never call altar.craft() because it spawns the result as an
        // ItemEntity.  Instead we consume the placed items and read the
        // result directly from the recipe, leaving the altar ready for
        // the next operation — whether that is another delegated craft or
        // a manual player interaction.
        capturedResult = ItemStack.EMPTY;
        try {
            capturedResult = completeManually();
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.error("[RSI-Batch-Malum] Manual completion failed:", e);
            clearPedestals();
            refundAll();
            return false;
        }

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
        if (Boolean.TRUE.equals(crafting)) return false;

        try {
            boolean mainEmpty = (boolean) invMain.getClass().getMethod("isEmpty").invoke(invMain);
            if (!mainEmpty) return false;
        } catch (Exception ex) { RSIntegrationMod.LOGGER.debug("[RSI] Reflection probe failed", ex); }

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

        // Manually complete the craft (same as tryStartSingleCraft phase 3)
        capturedResult = ItemStack.EMPTY;
        try {
            capturedResult = completeManually();
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.error("[RSI-Batch-Malum] Manual completion failed:", e);
            clearPedestals();
            try { setIHandlerSlot(invMain, 0, ItemStack.EMPTY); } catch (Exception ex) { RSIntegrationMod.LOGGER.debug("[RSI] Reflection probe failed", ex); }
            if (spirits != null) {
                for (int i = 0; i < spiritCount; i++) {
                    try { setIHandlerSlot(invSpirit, i, ItemStack.EMPTY); } catch (Exception ex) { RSIntegrationMod.LOGGER.debug("[RSI] Reflection probe failed", ex); }
                }
            }
            return false;
        }

        return true;
    }

    @Override
    public boolean isCraftComplete(ServerLevel level) {
        return !capturedResult.isEmpty();
    }

    @Override
    public ItemStack collectResult(ServerPlayer player) {
        ItemStack result = capturedResult.copy();
        capturedResult = ItemStack.EMPTY;
        return result;
    }

    @Override
    public void onBatchFailed(ServerPlayer player, String reason) {
        capturedResult = ItemStack.EMPTY;
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
        if (network != null) {
            network.insertItem(stack, stack.getCount(),
                    com.refinedmods.refinedstorage.api.util.Action.PERFORM);
        } else if (player != null) {
            ItemHandlerHelper.giveItemToPlayer(player, stack);
        }
    }

    @Override
    public void onBatchFinished(ServerPlayer player) {
        capturedResult = ItemStack.EMPTY;
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

    // ── Manual craft completion ────────────────────────────────────

    /**
     * Consumes the placed items from the altar and returns the recipe result
     * directly, without ever calling {@code altar.craft()}.  This avoids the
     * altar spawning an ItemEntity and keeps the altar ready for subsequent
     * operations — delegated or manual.
     */
    private ItemStack completeManually() throws Exception {
        ItemStack result = com.huanghuang.rsintegration.crafting.ModRecipeIndex
                .tryGetResultItem(recipe, myLevel.registryAccess()).copy();
        if (result.isEmpty()) {
            throw new IllegalStateException("Recipe has no output");
        }

        // Consume center slot
        setIHandlerSlot(invMain, 0, ItemStack.EMPTY);

        // Consume spirit slots
        List<?> spirits = (List<?>) getField(recipe, "spirits");
        if (spirits != null) {
            for (int i = 0; i < spirits.size(); i++) {
                setIHandlerSlot(invSpirit, i, ItemStack.EMPTY);
            }
        }

        // Consume pedestal slots
        clearPedestals();

        // Reset altar state — init() recalculates available recipes
        altar.getClass().getMethod("init").invoke(altar);
        setField(altar, "isCrafting", false);

        RSIntegrationMod.LOGGER.debug("[RSI-Batch-Malum] Manual completion produced: {} x{}",
                result.getHoverName().getString(), result.getCount());
        return result;
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

    // ── Refund ───────────────────────────────────────────────────

    private void refundAll() {
        if (ledger == null || !ledger.isCommitted()) return;
        // Refund center ingredient
        Object inputObj = getField(recipe, "input");
        if (inputObj != null) {
            Ingredient centerIng = (Ingredient) getField(inputObj, "ingredient");
            if (centerIng != null && !centerIng.isEmpty()) {
                ItemStack[] opts = centerIng.getItems();
                if (opts.length > 0 && !opts[0].isEmpty()) {
                    ItemStack refund = opts[0].copyWithCount(CraftPacketUtils.readIngredientCount(inputObj, 1));
                    if (network != null) {
                        network.insertItem(refund, refund.getCount(), com.refinedmods.refinedstorage.api.util.Action.PERFORM);
                    } else if (player != null) {
                        ItemHandlerHelper.giveItemToPlayer(player, refund);
                    }
                }
            }
        }
        // Refund extra items
        List<?> extraItems = (List<?>) getField(recipe, "extraItems");
        if (extraItems != null) {
            for (Object eItem : extraItems) {
                Ingredient ing = (Ingredient) getField(eItem, "ingredient");
                if (ing == null || ing.isEmpty()) continue;
                ItemStack[] opts = ing.getItems();
                if (opts.length > 0 && !opts[0].isEmpty()) {
                    ItemStack refund = opts[0].copyWithCount(CraftPacketUtils.readIngredientCount(eItem, 1));
                    if (network != null) {
                        network.insertItem(refund, refund.getCount(), com.refinedmods.refinedstorage.api.util.Action.PERFORM);
                    } else if (player != null) {
                        ItemHandlerHelper.giveItemToPlayer(player, refund);
                    }
                }
            }
        }
        // Refund spirits
        List<?> spirits = (List<?>) getField(recipe, "spirits");
        if (spirits != null) {
            for (Object swc : spirits) {
                try {
                    Item spiritItem = (Item) swc.getClass().getMethod("getItem").invoke(swc);
                    int sCount = CraftPacketUtils.readIngredientCount(swc, 1);
                    ItemStack refund = new ItemStack(spiritItem, sCount); // spiritItem from reflection, no template ItemStack available
                    if (network != null) {
                        network.insertItem(refund, refund.getCount(), com.refinedmods.refinedstorage.api.util.Action.PERFORM);
                    } else if (player != null) {
                        ItemHandlerHelper.giveItemToPlayer(player, refund);
                    }
                } catch (Exception ex) { RSIntegrationMod.LOGGER.debug("[RSI] Reflection probe failed", ex); }
            }
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
}
