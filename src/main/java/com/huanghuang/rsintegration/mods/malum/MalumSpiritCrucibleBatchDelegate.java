package com.huanghuang.rsintegration.mods.malum;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.reflection.probes.MalumReflection;
import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.batch.AbstractBatchDelegate;

import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.recipe.ModRecipeHandlers;
import com.huanghuang.rsintegration.util.Reflect;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemHandlerHelper;

import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * Batch delegate for Malum Spirit Crucible (SpiritFocusingRecipe).
 *
 * Inventory layout (per SpiritCrucibleCoreBlockEntity):
 *   inventory           — 1 slot  (catalyst/tool, durability cost)
 *   spiritInventory     — 4 slots (spirit shards, consumed)
 *   augmentInventory    — 4 slots (augments)
 *   coreAugmentInventory— 1 slot  (core augment)
 *
 * Output spawns as ItemEntity in the world — collected via AABB scan.
 */
public final class MalumSpiritCrucibleBatchDelegate extends AbstractBatchDelegate {

    private static java.lang.reflect.Field iwcIngField;
    private static java.lang.reflect.Field iwcCountField;

    private static synchronized void ensureIWCFields() {
        if (iwcIngField != null || MalumReflection.ingredientWithCountClass == null) return;
        try {
            iwcIngField = MalumReflection.ingredientWithCountClass.getDeclaredField("ingredient");
            iwcIngField.setAccessible(true);
            iwcCountField = MalumReflection.ingredientWithCountClass.getDeclaredField("count");
            iwcCountField.setAccessible(true);
        } catch (NoSuchFieldException e) {
            RSIntegrationMod.LOGGER.warn("[RSI-Crucible] IngredientWithCount fields not found", e);
        }
    }

    // ── Instance state ────────────────────────────────────────────
    private ServerPlayer player;
    private ServerLevel myLevel;
    private ResourceLocation myDim;
    private BlockPos myPos;
    private Object crucibleBE;
    private IItemHandler invCatalyst;
    private IItemHandler invSpirits;
    private Recipe<?> recipe;
    private ItemStack expectedOutput;
    private boolean craftStarted;
    private boolean craftWasSeenActive;

    // ── IBatchDelegate ────────────────────────────────────────────

    @Override
    public boolean validateAndInit(ServerPlayer player, ResourceLocation recipeId,
                                   @Nullable ResourceLocation dim, BlockPos pos) {
        if (!MalumReflection.isAvailable()) {
            player.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                    "rsi.malum_crucible.error.mod_missing"));
            return false;
        }

        this.player = player;
        this.myDim = dim;
        this.myPos = pos;

        // Resolve level
        ServerLevel level;
        if (dim != null) {
            net.minecraft.resources.ResourceKey<Level> key =
                    net.minecraft.resources.ResourceKey.create(
                            net.minecraft.core.registries.Registries.DIMENSION, dim);
            level = player.getServer().getLevel(key);
        } else {
            level = player.serverLevel();
        }
        if (level == null) {
            player.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                    "rsi.generic.error.dim_not_found"));
            return false;
        }
        this.myLevel = level;

        // Validate block entity — Spirit Crucible is a multi-block; the player
        // may have clicked on a component block rather than the core.  Scan a
        // 2-block radius for the core BE.
        if (pos == null) {
            player.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                    "rsi.generic.error.machine_not_found"));
            return false;
        }
        BlockEntity be = level.getBlockEntity(pos);
        if (be == null || !MalumReflection.crucibleBEClass.isInstance(be)) {
            // Scan for core BE — the bound position may be a component block
            BlockPos corePos = findCrucibleCore(level, pos);
            if (corePos != null) {
                this.myPos = corePos;
                be = level.getBlockEntity(corePos);
            }
        }
        if (be == null || !MalumReflection.crucibleBEClass.isInstance(be)) {
            player.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                    "rsi.malum_crucible.error.not_crucible"));
            return false;
        }
        this.crucibleBE = be;

        // Resolve recipe
        this.recipe = level.getRecipeManager().byKey(recipeId).orElse(null);
        if (recipe == null) {
            player.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                    "rsi.generic.error.recipe_not_found", recipeId.toString()));
            return false;
        }

        // Pre-compute expected output
        this.expectedOutput = ModRecipeHandlers.tryGetResultItem(recipe, level.registryAccess());
        if (expectedOutput.isEmpty()) {
            // Fallback: read output field directly
            Reflect.findField(recipe.getClass(), "output").ifPresent(f -> {
                try {
                    Object v = f.get(recipe);
                    if (v instanceof ItemStack s && !s.isEmpty())
                        this.expectedOutput = s.copy();
                } catch (Exception e) {
                    RSIntegrationMod.LOGGER.warn("[RSI-Malum] IngredientWithCount parse failed", e);
                }
            });
        }

        // Read inventories
        this.invCatalyst = readHandler(be, "inventory");
        this.invSpirits = readHandler(be, "spiritInventory");
        if (invCatalyst == null || invSpirits == null) {
            player.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                    "rsi.malum_crucible.error.inventory_error"));
            return false;
        }

        // Resolve network early for stray item recovery
        this.network = com.huanghuang.rsintegration.crafting.CraftPacketUtils
                .resolveNetworkForCraft(player, level.dimension(), pos);

        // Check crucible is idle (no active recipe)
        Object currentRecipe = Reflect.getField(be, "recipe").orElse(null);
        if (currentRecipe != null) {
            player.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                    "rsi.malum_crucible.warn.already_crafting"));
            return false;
        }

        boolean hadStray = false;

        // ── Catalyst slot: auto-recover stray items ──
        ItemStack existing = invCatalyst.getStackInSlot(0);
        if (!existing.isEmpty()) {
            Field inputField = Reflect.findField(recipe.getClass(), "input").orElse(null);
            boolean matches = false;
            if (inputField != null) {
                inputField.setAccessible(true);
                try {
                    Object val = inputField.get(recipe);
                    if (val != null) {
                        net.minecraft.world.item.crafting.Ingredient ri = null;
                        if (MalumReflection.ingredientWithCountClass != null && MalumReflection.ingredientWithCountClass.isInstance(val)) {
                            Object ing = iwcIngField.get(val);
                            if (ing instanceof net.minecraft.world.item.crafting.Ingredient r) ri = r;
                        } else if (val instanceof net.minecraft.world.item.crafting.Ingredient r) {
                            ri = r;
                        }
                        if (ri != null && ri.test(existing)) {
                            matches = true; // reuse matching catalyst
                        }
                    }
                } catch (Exception e) {
                    RSIntegrationMod.LOGGER.warn("[RSI-Malum] IngredientWithCount parse failed", e);
                }
            }
            if (!matches) {
                returnCrucibleItem(existing);
                setSlot(invCatalyst, 0, ItemStack.EMPTY);
                hadStray = true;
            }
        }

        // ── Spirit slots: auto-recover any leftover spirits ──
        for (int i = 0; i < invSpirits.getSlots(); i++) {
            ItemStack spirit = invSpirits.getStackInSlot(i);
            if (!spirit.isEmpty()) {
                returnCrucibleItem(spirit);
                setSlot(invSpirits, i, ItemStack.EMPTY);
                hadStray = true;
            }
        }

        if (hadStray) {
            be.setChanged();
            RSIntegrationMod.LOGGER.debug("[RSI-Crucible] Recovered stray items from crucible at {}", pos);
        }

        this.craftStarted = false;
        this.craftWasSeenActive = false;
        return true;
    }

    // ── tryStartSingleCraft ──────────────────────────────────────

    @Override
    public boolean tryStartSingleCraft(ServerPlayer player) {
        return tryStartWithExtraction(player, false, null);
    }

    @Override
    public boolean tryStartSingleCraft(ServerPlayer player, ExtractionLedger sharedLedger) {
        this.sharedLedger = sharedLedger;
        this.usingSharedLedger = true;
        return tryStartWithExtraction(player, false, sharedLedger);
    }

    @Override
    public boolean tryStartWithMaterials(ServerPlayer player, List<ItemStack> materials,
                                         ExtractionLedger sharedLedger) {
        this.sharedLedger = sharedLedger;
        this.usingSharedLedger = true;
        return tryStartWithMaterialsImpl(player, materials);
    }

    @Nullable
    @Override
    public List<IngredientSpec> getRequiredMaterials() {
        if (recipe == null) return null;
        ensureIWCFields();
        List<IngredientSpec> result = new ArrayList<>();

        // 1. Catalyst (input) — handle both IngredientWithCount (older Malum)
        //    and plain Ingredient (SpiritFocusingRecipe in malum 1.6.6+).
        Field inputField = Reflect.findField(recipe.getClass(), "input").orElse(null);
        if (inputField != null) {
            inputField.setAccessible(true);
            try {
                Object val = inputField.get(recipe);
                if (val != null) {
                    if (MalumReflection.ingredientWithCountClass != null && MalumReflection.ingredientWithCountClass.isInstance(val)) {
                        Object ing = iwcIngField.get(val);
                        int count = iwcCountField.getInt(val);
                        if (ing instanceof net.minecraft.world.item.crafting.Ingredient ri && count > 0) {
                            result.add(new IngredientSpec(ri, count));
                        }
                    } else if (val instanceof net.minecraft.world.item.crafting.Ingredient ri) {
                        result.add(new IngredientSpec(ri, 1));
                    }
                }
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.debug("[RSI-Crucible] getRequiredMaterials input probe failed", e);
            }
        }

        // 2. Spirits
        Reflect.findField(recipe.getClass(), "spirits").ifPresent(f -> {
            try {
                List<?> spirits = (List<?>) f.get(recipe);
                if (spirits != null) {
                    for (Object swc : spirits) {
                        int count = Reflect.getIntField(swc, "count").orElse(1);
                        Object itemObj = Reflect.invoke(swc, "getItem").orElse(null);
                        if (itemObj instanceof net.minecraft.world.item.Item it && count > 0) {
                            result.add(new IngredientSpec(
                                    net.minecraft.world.item.crafting.Ingredient.of(it), count));
                        }
                    }
                }
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.debug("[RSI-Crucible] getRequiredMaterials spirits probe failed", e);
            }
        });

        return result.isEmpty() ? null : result;
    }

    // ── Internal: extraction + placement ─────────────────────────

    private boolean tryStartWithExtraction(ServerPlayer player, boolean usingPreReserved,
                                           @Nullable ExtractionLedger sharedLedger) {
        if (myLevel == null || crucibleBE == null || recipe == null) return false;

        // Re-validate BE still exists and is idle
        BlockEntity be = myLevel.getBlockEntity(myPos);
        if (be == null || !MalumReflection.crucibleBEClass.isInstance(be)) {
            player.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                    "rsi.malum_crucible.error.not_crucible"));
            return false;
        }
        this.crucibleBE = be;
        this.invCatalyst = readHandler(be, "inventory");
        this.invSpirits = readHandler(be, "spiritInventory");
        if (invCatalyst == null || invSpirits == null) {
            player.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                    "rsi.malum_crucible.error.inventory_error"));
            return false;
        }

        // Check still idle
        Object currentRecipe = Reflect.getField(be, "recipe").orElse(null);
        if (currentRecipe != null) {
            player.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                    "rsi.malum_crucible.warn.already_crafting"));
            return false;
        }

        ExtractionLedger localLedger = usingSharedLedger ? sharedLedger : new ExtractionLedger();
        boolean ownsLedger = !usingSharedLedger || sharedLedger == null;
        try {
        if (!usingSharedLedger || sharedLedger == null) {
            this.ledger = localLedger;
            this.usingSharedLedger = false;
        } else {
            this.ledger = null;
            this.usingSharedLedger = true;
        }

        // ── 1. Place catalyst (MUST succeed before spirits are placed) ──
        if (invCatalyst.getStackInSlot(0).isEmpty()) {
            Field inputField = Reflect.findField(recipe.getClass(), "input").orElse(null);
            if (inputField != null) {
                inputField.setAccessible(true);
                try {
                    Object val = inputField.get(recipe);
                    if (val != null) {
                        if (MalumReflection.ingredientWithCountClass != null && MalumReflection.ingredientWithCountClass.isInstance(val)) {
                            // IngredientWithCount path (older Malum recipes)
                            Object ing = iwcIngField.get(val);
                            int count = iwcCountField.getInt(val);
                            if (ing instanceof net.minecraft.world.item.crafting.Ingredient ri && count > 0) {
                                ItemStack extracted = extractFromRS(player, ri, count,
                                        localLedger, usingSharedLedger);
                                if (!extracted.isEmpty()) {
                                    setSlot(invCatalyst, 0, extracted);
                                }
                            }
                        } else if (val instanceof net.minecraft.world.item.crafting.Ingredient ri) {
                            // Plain Ingredient path (SpiritFocusingRecipe)
                            ItemStack extracted = extractFromRS(player, ri, 1,
                                    localLedger, usingSharedLedger);
                            if (!extracted.isEmpty()) {
                                setSlot(invCatalyst, 0, extracted);
                            }
                        }
                    }
                } catch (Exception e) {
                    RSIntegrationMod.LOGGER.debug("[RSI-Crucible] catalyst extract failed", e);
                }
            }
        }

        // Verify catalyst was placed before touching spirits
        if (invCatalyst.getStackInSlot(0).isEmpty()) {
            player.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                    "rsi.malum_crucible.error.no_catalyst"));
            return false;
        }

        // ── 2. Place spirits ──
        Reflect.findField(recipe.getClass(), "spirits").ifPresent(f -> {
            try {
                List<?> spirits = (List<?>) f.get(recipe);
                if (spirits == null) return;
                int slot = 0;
                for (Object swc : spirits) {
                    if (slot >= invSpirits.getSlots()) break;
                    int count = Reflect.getIntField(swc, "count").orElse(1);
                    Object itemObj = Reflect.invoke(swc, "getItem").orElse(null);
                    if (itemObj instanceof net.minecraft.world.item.Item it && count > 0) {
                        net.minecraft.world.item.crafting.Ingredient si =
                                net.minecraft.world.item.crafting.Ingredient.of(it);
                        for (int c = 0; c < count && slot < invSpirits.getSlots(); c++) {
                            ItemStack extracted = extractFromRS(player, si, 1,
                                    localLedger, usingSharedLedger);
                            if (!extracted.isEmpty()) {
                                setSlot(invSpirits, slot, extracted);
                            }
                        }
                    }
                    slot++;
                }
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.debug("[RSI-Crucible] spirits extract failed", e);
            }
        });

        // ── Commit ──
        if (!usingSharedLedger) {
            if (!localLedger.commit(this.network, player)) {
                clearAllSlots();
                player.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                        "rsi.generic.error.craft_failed", "Ledger commit failed"));
                return false;
            }
        }

        be.setChanged();
        this.craftStarted = true;
        this.craftWasSeenActive = false;
        RSIntegrationMod.LOGGER.debug("[RSI-Crucible] Craft started for {} at {}", recipe.getId(), myPos);
        return true;
        } finally {
            if (ownsLedger) {
                localLedger.close();
            }
        }
    }

    private boolean tryStartWithMaterialsImpl(ServerPlayer player, List<ItemStack> materials) {
        if (myLevel == null || crucibleBE == null || recipe == null) return false;

        BlockEntity be = myLevel.getBlockEntity(myPos);
        if (be == null || !MalumReflection.crucibleBEClass.isInstance(be)) return false;
        this.crucibleBE = be;
        this.invCatalyst = readHandler(be, "inventory");
        this.invSpirits = readHandler(be, "spiritInventory");
        if (invCatalyst == null || invSpirits == null) return false;

        // Place materials in order: [catalyst, spirit1, spirit2, ...]
        int matIdx = 0;

        if (!materials.isEmpty()) {
            // Catalyst
            ItemStack mat = materials.get(matIdx++);
            if (!mat.isEmpty()) {
                setSlot(invCatalyst, 0, mat.copy());
            }
        }
        // Spirits — one per slot, each spirit count may need splitting
        int spiritSlot = 0;
        while (matIdx < materials.size() && spiritSlot < invSpirits.getSlots()) {
            ItemStack mat = materials.get(matIdx++);
            if (!mat.isEmpty()) {
                setSlot(invSpirits, spiritSlot, mat.copy());
            }
            spiritSlot++;
        }

        be.setChanged();
        this.craftStarted = true;
        this.craftWasSeenActive = false;
        RSIntegrationMod.LOGGER.debug("[RSI-Crucible] Craft started with materials for {} at {}",
                recipe.getId(), myPos);
        return true;
    }

    // ── isMachineCraftFinished ───────────────────────────────────

    @Override
    protected boolean isMachineCraftFinished(ServerLevel level, BlockEntity be) {
        if (!craftStarted) return false;

        if (!MalumReflection.crucibleBEClass.isInstance(be)) return true; // BE gone → consider done

        Object currentRecipe = Reflect.getField(be, "recipe").orElse(null);
        if (currentRecipe != null) {
            craftWasSeenActive = true;
            return false;
        }

        // Recipe went null — if we saw it active before, craft is complete
        if (craftWasSeenActive) return true;

        // Fallback: scan for ItemEntity result
        if (!expectedOutput.isEmpty()) {
            BlockPos pos = be.getBlockPos();
            List<ItemEntity> entities = level.getEntitiesOfClass(ItemEntity.class,
                    new AABB(pos).inflate(3),
                    e -> net.minecraft.world.item.ItemStack.isSameItemSameTags(
                            e.getItem(), expectedOutput)
                            || net.minecraft.world.item.ItemStack.isSameItem(
                                    e.getItem(), expectedOutput));
            if (!entities.isEmpty()) return true;
        }

        return false;
    }

    // ── collectResult ─────────────────────────────────────────────

    @Override
    public ItemStack collectResult(ServerPlayer player) {
        if (myLevel == null || myPos == null) return ItemStack.EMPTY;

        ItemStack target = expectedOutput;
        if (target.isEmpty()) {
            target = ModRecipeHandlers.tryGetResultItem(recipe, myLevel.registryAccess());
        }
        final ItemStack scanTarget = target;

        // Scan for ItemEntity matching the expected output
        List<ItemEntity> entities = myLevel.getEntitiesOfClass(ItemEntity.class,
                new AABB(myPos).inflate(3),
                e -> {
                    if (!e.isAlive()) return false;
                    ItemStack ei = e.getItem();
                    if (scanTarget.isEmpty()) return !ei.isEmpty();
                    return net.minecraft.world.item.ItemStack.isSameItemSameTags(ei, scanTarget)
                            || net.minecraft.world.item.ItemStack.isSameItem(ei, scanTarget);
                });

        if (!entities.isEmpty()) {
            ItemEntity entity = entities.get(0);
            ItemStack result = entity.getItem().copy();
            entity.getItem().shrink(result.getCount());
            entity.setItem(entity.getItem().copy());
            if (entity.getItem().isEmpty()) entity.discard();
            RSIntegrationMod.LOGGER.debug("[RSI-Crucible] Collected {}x{} from world",
                    result.getHoverName().getString(), result.getCount());
            return result;
        }

        // Fallback: check player inventory
        if (!target.isEmpty()) {
            for (var inv : new net.minecraft.world.item.ItemStack[]{
                    player.getInventory().getSelected(),
                    player.getInventory().offhand.get(0)}) {
                var slot = inv;
                if (net.minecraft.world.item.ItemStack.isSameItemSameTags(slot, target)) {
                    ItemStack result = slot.copy();
                    result.setCount(Math.min(result.getCount(), target.getMaxStackSize()));
                    slot.shrink(result.getCount());
                    if (!result.isEmpty()) return result;
                }
            }
        }

        return ItemStack.EMPTY;
    }

    // ── cleanup ───────────────────────────────────────────────────

    @Override
    protected void clearMachineState(BlockEntity be, ServerPlayer player) {
        RSIntegrationMod.LOGGER.warn("[RSI-Crucible] Batch failed");
        clearAllSlots();
        resetState();
    }

    @Override
    public void onBatchFinished(ServerPlayer player) {
        clearAllSlots();
        resetState();
    }

    @Override
    public BlockPos getMachinePos() {
        return myPos;
    }

    // ── helpers ───────────────────────────────────────────────────

    private static IItemHandler readHandler(Object be, String fieldName) {
        return Reflect.getField(be, fieldName)
                .filter(IItemHandler.class::isInstance)
                .map(IItemHandler.class::cast)
                .orElse(null);
    }

    private static void setSlot(IItemHandler handler, int slot, ItemStack stack) {
        try {
            handler.getClass().getMethod("setStackInSlot", int.class, ItemStack.class)
                    .invoke(handler, slot, stack.copy());
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-Crucible] setSlot failed", e);
        }
    }

    private void clearAllSlots() {
        // Shared ledger handles refund — do not double-insert from crucible slots
        final boolean refund = !usingSharedLedger;
        if (invCatalyst != null) {
            for (int i = 0; i < invCatalyst.getSlots(); i++) {
                ItemStack s = invCatalyst.getStackInSlot(i);
                if (!s.isEmpty()) {
                    if (refund) returnCrucibleItem(s);
                    setSlot(invCatalyst, i, ItemStack.EMPTY);
                }
            }
        }
        if (invSpirits != null) {
            for (int i = 0; i < invSpirits.getSlots(); i++) {
                ItemStack s = invSpirits.getStackInSlot(i);
                if (!s.isEmpty()) {
                    if (refund) returnCrucibleItem(s);
                    setSlot(invSpirits, i, ItemStack.EMPTY);
                }
            }
        }
        if (crucibleBE instanceof net.minecraft.world.level.block.entity.BlockEntity be) {
            be.setChanged();
        }
    }

    private void returnCrucibleItem(ItemStack stack) {
        if (stack.isEmpty()) return;
        if (network == null) {
            network = com.huanghuang.rsintegration.crafting.CraftPacketUtils
                    .resolveNetworkForCraft(player, myLevel.dimension(), myPos);
        }
        if (network != null) {
            ItemStack leftover = network.insertItem(stack.copy(), stack.getCount(),
                    com.refinedmods.refinedstorage.api.util.Action.PERFORM);
            if (!leftover.isEmpty() && player != null) {
                net.minecraftforge.items.ItemHandlerHelper.giveItemToPlayer(player, leftover);
            }
        } else if (player != null) {
            net.minecraftforge.items.ItemHandlerHelper.giveItemToPlayer(player, stack.copy());
        }
    }

    private ItemStack extractFromRS(ServerPlayer player, net.minecraft.world.item.crafting.Ingredient ingredient,
                                    int count, ExtractionLedger ledger, boolean useShared) {
        if (this.network == null) {
            this.network = com.huanghuang.rsintegration.crafting.CraftPacketUtils
                    .resolveNetworkForCraft(player, myLevel.dimension(), myPos);
        }
        if (this.network == null) return ItemStack.EMPTY;

        return ledger.reserveFromNetwork(ingredient, count, this.network);
    }

    // ── plan warnings ─────────────────────────────────────────────

    public static List<String> getPlanWarnings(ServerPlayer player, Recipe<?> recipe,
                                               @Nullable ResourceLocation dim,
                                               @Nullable BlockPos pos) {
        List<String> warnings = new ArrayList<>();
        if (!MalumReflection.isAvailable()) return warnings;

        // Spirit requirements
        List<?> spirits = null;
        try {
            java.lang.reflect.Field f = recipe.getClass().getDeclaredField("spirits");
            f.setAccessible(true);
            spirits = (List<?>) f.get(recipe);
        } catch (Exception e) { /* no spirit field */ }
        if (spirits != null && !spirits.isEmpty()) {
            List<String> names = new ArrayList<>();
            for (Object swc : spirits) {
                try {
                    Object type = Reflect.getField(swc, "type").orElse(null);
                    if (type != null) {
                        String name = type.toString();
                        // Extract spirit name from identifier
                        if (name.contains(":")) {
                            String[] parts = name.split(":");
                            name = parts[parts.length - 1].replace("_", " ");
                        }
                        int count = Reflect.getIntField(swc, "count").orElse(0);
                        names.add(count + "x " + name);
                    }
                } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI-Crucible] spirit probe failed", e); }
            }
            if (!names.isEmpty()) {
                warnings.add(net.minecraft.network.chat.Component.translatable(
                        "rsi.malum_crucible.warn.spirit_required",
                        String.join(", ", names)).getString());
            }
        }

        // Check spirit slot count if crucible is bound
        if (pos != null && MalumReflection.crucibleBEClass != null) {
            ServerLevel level = null;
            if (dim != null) {
                net.minecraft.resources.ResourceKey<Level> key =
                        net.minecraft.resources.ResourceKey.create(
                                net.minecraft.core.registries.Registries.DIMENSION, dim);
                level = player.getServer().getLevel(key);
            } else {
                level = player.serverLevel();
            }
            if (level != null) {
                BlockEntity be = level.getBlockEntity(pos);
                if (be != null && MalumReflection.crucibleBEClass.isInstance(be)) {
                    IItemHandler spiritInv = readHandler(be, "spiritInventory");
                    if (spiritInv != null && spirits != null && spirits.size() > spiritInv.getSlots()) {
                        warnings.add(net.minecraft.network.chat.Component.translatable(
                                "rsi.malum_crucible.warn.spirit_slots_insufficient",
                                spiritInv.getSlots(), spirits.size()).getString());
                    }
                }
            }
        }

        return warnings;
    }

    /**
     * Scan up to 2 blocks away for a SpiritCrucibleCoreBlockEntity.
     * The Spirit Crucible is a Lodestone multi-block; the player may
     * have shift+clicked a component block instead of the core.
     */
    private static BlockPos findCrucibleCore(Level level, BlockPos pos) {
        if (!MalumReflection.isAvailable()) return null;
        int r = 2;
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;
                    BlockPos scan = pos.offset(dx, dy, dz);
                    BlockEntity be = level.getBlockEntity(scan);
                    if (be != null && MalumReflection.crucibleBEClass.isInstance(be)) return scan;
                }
            }
        }
        return null;
    }
}
