package com.huanghuang.rsintegration.mods.malum;

import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.CraftStorageEndpoint;
import com.huanghuang.rsintegration.crafting.CraftStorageEndpoints;
import com.huanghuang.rsintegration.crafting.batch.BatchConcurrencyCapabilities;
import com.huanghuang.rsintegration.crafting.batch.IBatchDelegate;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.reflection.probes.MalumReflection;
import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.batch.AbstractBatchDelegate;

import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.graph.DemandRole;
import com.huanghuang.rsintegration.recipe.ModRecipeHandlers;
import com.huanghuang.rsintegration.util.PlayerUtils;
import com.huanghuang.rsintegration.util.Reflect;
import com.refinedmods.refinedstorage.api.network.INetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
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
import org.jetbrains.annotations.NotNull;

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
    @Nullable
    private CraftStorageEndpoint catalystReturnEndpoint;
    @Nullable
    private INetwork catalystReturnNetwork;

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

        // Resolve the selected backend early for stray item recovery.  A BD
        // endpoint must remain authoritative and must never be replaced by an
        // RS lookup during preparation.
        if (storageEndpoint() == null) {
            this.network = CraftPacketUtils
                    .resolveNetworkForCraft(player, level.dimension(), pos);
        }
        // onBatchFinished resets AbstractBatchDelegate before the surrounding
        // parallel group releases its worker-reusable catalyst. Preserve the
        // exact backend route until releaseReusableMaterials runs, otherwise a
        // later order can leave the catalyst outside its originating storage.
        this.catalystReturnEndpoint = storageEndpoint();
        this.catalystReturnNetwork = this.network;

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
                        if (ri != null && ri.test(existing) && hasReusableCatalystInput()) {
                            matches = true;
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
        if (storageEndpoint() == null && network == null) {
            network = CraftPacketUtils.resolveNetworkForCraft(player, myLevel.dimension(), myPos);
        }
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
        if (sharedLedger != null) setStorageEndpoint(sharedLedger.storageEndpoint());
        return tryStartWithMaterialsImpl(player, materials);
    }

    @Nullable
    @Override
    public List<IngredientSpec> getRequiredMaterials() {
        if (recipe == null) return null;
        var handler = ModRecipeHandlers.handlerFor(recipe);
        if (handler != null) {
            List<IngredientSpec> specs = handler.getIngredients(recipe);
            if (specs != null && !specs.isEmpty()) return specs;
        }
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

    @Override
    public List<IBatchDelegate.MaterialReservationScope>
    getMaterialReservationScopes() {
        List<IngredientSpec> specs = getRequiredMaterials();
        return materialReservationScopes(specs);
    }

    @Override
    public BatchConcurrencyCapabilities concurrencyCapabilities() {
        return new BatchConcurrencyCapabilities(
                BatchConcurrencyCapabilities.MaterialOwnership.CHAIN_RESERVED,
                BatchConcurrencyCapabilities.OutputOwnership.OWNED_WORLD_CAPTURE,
                BatchConcurrencyCapabilities.CleanupContract.SEPARABLE_OFFLINE,
                BatchConcurrencyCapabilities.SideEffects.LOCAL_WORLD_ITEMS,
                BatchConcurrencyCapabilities.PreparationContract.RETRY_SAFE,
                List.of());
    }

    @Override
    public boolean supportsConcurrentNodeExecution() {
        return true;
    }

    @Override
    public boolean allowsOverlappingOutputCaptureOrigins() {
        return true;
    }

    static List<IBatchDelegate.MaterialReservationScope> materialReservationScopes(
            @Nullable List<IngredientSpec> specs) {
        if (specs == null || specs.isEmpty()) return List.of();
        List<IBatchDelegate.MaterialReservationScope> scopes =
                new ArrayList<>(specs.size());
        scopes.add(specs.get(0).role() == DemandRole.CATALYST
                ? IBatchDelegate.MaterialReservationScope.PER_WORKER_REUSABLE
                : IBatchDelegate.MaterialReservationScope.PER_OPERATION);
        for (int i = 1; i < specs.size(); i++) {
            scopes.add(IBatchDelegate.MaterialReservationScope.PER_OPERATION);
        }
        return scopes;
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

        // Reserve the catalyst now, but place it only after every spirit. Malum
        // resolves the recipe when the center slot changes, so writing it first
        // can lock in a recipe that matches only the first spirit stack.
        ItemStack catalystToPlace = ItemStack.EMPTY;
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
                                    catalystToPlace = extracted;
                                }
                            }
                        } else if (val instanceof net.minecraft.world.item.crafting.Ingredient ri) {
                            // Plain Ingredient path (SpiritFocusingRecipe)
                            ItemStack extracted = extractFromRS(player, ri, 1,
                                    localLedger, usingSharedLedger);
                            if (!extracted.isEmpty()) {
                                catalystToPlace = extracted;
                            }
                        }
                    }
                } catch (Exception e) {
                    RSIntegrationMod.LOGGER.debug("[RSI-Crucible] catalyst extract failed", e);
                }
            }
        }

        if (invCatalyst.getStackInSlot(0).isEmpty() && catalystToPlace.isEmpty()) {
            player.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                    "rsi.malum_crucible.error.no_catalyst"));
            return false;
        }

        List<ItemStack> spiritStacks = reserveSpiritStacks(player, localLedger, usingSharedLedger);
        if (spiritStacks == null) {
            clearUncommittedPlacements();
            player.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                    "rsi.generic.error.missing_materials", recipe.getId().toString()));
            return false;
        }
        placePreparedInputs(invSpirits, spiritStacks, invCatalyst, catalystToPlace);

        if (!activateExpectedRecipe(be, player)) {
            clearUncommittedPlacements();
            return false;
        }

        // ── Commit ──
        if (!usingSharedLedger) {
            if (!localLedger.commit(this.network, player)) {
                clearUncommittedPlacements();
                player.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                        "rsi.generic.error.craft_failed", "Ledger commit failed"));
                return false;
            }
        }

        be.setChanged();
        this.craftStarted = true;
        RSIntegrationMod.LOGGER.debug("[RSI-Crucible] Craft started for {} at {}", recipe.getId(), myPos);
        return true;
        } finally {
            if (ownsLedger) {
                localLedger.close();
            }
        }
    }

    @Nullable
    private List<ItemStack> reserveSpiritStacks(ServerPlayer player, ExtractionLedger ledger,
                                                boolean useShared) {
        Field field = Reflect.findField(recipe.getClass(), "spirits").orElse(null);
        if (field == null) return List.of();
        try {
            field.setAccessible(true);
            List<?> spirits = (List<?>) field.get(recipe);
            if (spirits == null || spirits.isEmpty()) return List.of();
            if (spirits.size() > invSpirits.getSlots()) return null;
            List<ItemStack> reserved = new ArrayList<>(spirits.size());
            for (Object swc : spirits) {
                int count = Reflect.getIntField(swc, "count").orElse(1);
                Object itemObj = Reflect.invoke(swc, "getItem").orElse(null);
                if (!(itemObj instanceof net.minecraft.world.item.Item item) || count <= 0) {
                    return null;
                }
                ItemStack extracted = extractFromRS(player,
                        net.minecraft.world.item.crafting.Ingredient.of(item), count,
                        ledger, useShared);
                if (extracted.isEmpty() || extracted.getCount() < count) return null;
                reserved.add(extracted);
            }
            return reserved;
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-Crucible] spirits extract failed", e);
            return null;
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
        if (Reflect.getField(be, "recipe").orElse(null) != null) return false;

        // Material lists remain [catalyst, spirit1, spirit2, ...], while the
        // physical placement order is spirits first and catalyst last.
        int matIdx = materials.isEmpty() ? 0 : 1;
        ItemStack catalystToPlace = ItemStack.EMPTY;

        if (!materials.isEmpty()) {
            boolean reusable = hasReusableCatalystInput();
            ItemStack existing = invCatalyst.getStackInSlot(0);
            if (!existing.isEmpty() && !reusable) {
                returnCrucibleItem(existing.copy());
                setSlot(invCatalyst, 0, ItemStack.EMPTY);
                existing = ItemStack.EMPTY;
            }
            if (!existing.isEmpty()) {
                // A reusable catalyst can remain in place between operations.
            } else {
                catalystToPlace = materials.get(0).copy();
            }
        }

        List<ItemStack> spiritStacks = new ArrayList<>();
        while (matIdx < materials.size() && spiritStacks.size() < invSpirits.getSlots()) {
            spiritStacks.add(materials.get(matIdx++).copy());
        }
        placePreparedInputs(invSpirits, spiritStacks, invCatalyst, catalystToPlace);

        if (!activateExpectedRecipe(be, player)) return false;

        be.setChanged();
        this.craftStarted = true;
        RSIntegrationMod.LOGGER.debug("[RSI-Crucible] Craft started with materials for {} at {}",
                recipe.getId(), myPos);
        return true;
    }

    private boolean activateExpectedRecipe(BlockEntity be, ServerPlayer player) {
        if (refreshRecipeSelection(be, recipe)) {
            refreshAccelerators(be, myLevel, myPos);
            craftWasSeenActive = true;
            return true;
        }
        RSIntegrationMod.LOGGER.warn(
                "[RSI-Crucible] Core rejected recipe {} after material placement at {}",
                recipe.getId(), myPos);
        logRejectedState(be);
        player.sendSystemMessage(Component.translatable(
                "rsi.malum_crucible.error.recipe_rejected", recipe.getId().toString()));
        return false;
    }

    /** Malum resolves the active focusing recipe only when core.init() runs. */
    static boolean refreshRecipeSelection(Object crucible, Recipe<?> expected) {
        if (crucible == null || expected == null) return false;
        try {
            java.lang.reflect.Method init = findNoArgMethod(crucible.getClass(), "init");
            if (init == null) return false;
            init.setAccessible(true);
            init.invoke(crucible);

            Field recipeField = findField(crucible.getClass(), "recipe");
            if (recipeField == null) return false;
            recipeField.setAccessible(true);
            Object active = unwrapOptional(recipeField.get(crucible));
            if (sameRecipe(active, expected)) return true;

            // Malum selects the first recipe whose spirit counts are <= the
            // inserted stacks. Modpacks can therefore have a low-cost recipe
            // shadow a higher-cost recipe with the same catalyst and spirit
            // types. Only override that ambiguous choice after Malum's own
            // target-recipe predicates confirm the placed inputs.
            if (!matchesPlacedInputs(crucible, expected)) return false;
            Object selected = java.util.Optional.class.isAssignableFrom(recipeField.getType())
                    ? java.util.Optional.of(expected) : expected;
            recipeField.set(crucible, selected);
            return sameRecipe(unwrapOptional(recipeField.get(crucible)), expected);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    private static boolean matchesPlacedInputs(Object crucible, Recipe<?> expected)
            throws ReflectiveOperationException {
        IItemHandler catalyst = readHandlerDirect(crucible, "inventory");
        IItemHandler spirits = readHandlerDirect(crucible, "spiritInventory");
        if (catalyst == null || spirits == null || catalyst.getSlots() == 0) return false;

        java.lang.reflect.Method inputMatch = findCompatibleMethod(
                expected.getClass(), "doesInputMatch", ItemStack.class);
        java.lang.reflect.Method spiritMatch = findCompatibleMethod(
                expected.getClass(), "doSpiritsMatch", List.class);
        if (inputMatch == null || spiritMatch == null) return false;

        List<ItemStack> placedSpirits = new ArrayList<>();
        for (int slot = 0; slot < spirits.getSlots(); slot++) {
            ItemStack stack = spirits.getStackInSlot(slot);
            if (!stack.isEmpty()) placedSpirits.add(stack);
        }
        inputMatch.setAccessible(true);
        spiritMatch.setAccessible(true);
        return Boolean.TRUE.equals(inputMatch.invoke(expected, catalyst.getStackInSlot(0)))
                && Boolean.TRUE.equals(spiritMatch.invoke(expected, placedSpirits));
    }

    @Nullable
    private static IItemHandler readHandlerDirect(Object owner, String name)
            throws IllegalAccessException {
        Field field = findField(owner.getClass(), name);
        if (field == null) return null;
        field.setAccessible(true);
        Object value = field.get(owner);
        return value instanceof IItemHandler handler ? handler : null;
    }

    private static boolean sameRecipe(@Nullable Object active, Recipe<?> expected) {
        return active instanceof Recipe<?> activeRecipe
                && (activeRecipe == expected || activeRecipe.getId().equals(expected.getId()));
    }

    @Nullable
    private static Object unwrapOptional(@Nullable Object value) {
        return value instanceof java.util.Optional<?> optional ? optional.orElse(null) : value;
    }

    @Nullable
    private static Field findField(Class<?> type, String name) {
        for (Class<?> current = type; current != null && current != Object.class;
             current = current.getSuperclass()) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                // Continue through compatibility subclasses.
            }
        }
        return null;
    }

    @Nullable
    private static java.lang.reflect.Method findCompatibleMethod(
            Class<?> type, String name, Class<?> argumentType) {
        for (Class<?> current = type; current != null && current != Object.class;
             current = current.getSuperclass()) {
            for (java.lang.reflect.Method method : current.getDeclaredMethods()) {
                if (method.getName().equals(name) && method.getParameterCount() == 1
                        && method.getParameterTypes()[0].isAssignableFrom(argumentType)) {
                    return method;
                }
            }
        }
        return null;
    }

    private static java.lang.reflect.Method findNoArgMethod(Class<?> type, String name) {
        for (Class<?> current = type; current != null && current != Object.class;
             current = current.getSuperclass()) {
            for (java.lang.reflect.Method method : current.getDeclaredMethods()) {
                if (method.getName().equals(name) && method.getParameterCount() == 0) return method;
            }
        }
        return null;
    }

    private void logRejectedState(BlockEntity be) {
        Object active = Reflect.getField(be, "recipe").orElse(null);
        String activeDescription;
        if (active instanceof java.util.Optional<?> optional) active = optional.orElse(null);
        if (active instanceof Recipe<?> activeRecipe) {
            activeDescription = activeRecipe.getId().toString();
        } else {
            activeDescription = active == null ? "<none>" : active.getClass().getName();
        }
        RSIntegrationMod.LOGGER.warn(
                "[RSI-Crucible] Rejected state: activeRecipe={} catalyst={} spirits={}",
                activeDescription, describeHandler(invCatalyst), describeHandler(invSpirits));
    }

    private static String describeHandler(@Nullable IItemHandler handler) {
        if (handler == null) return "<missing>";
        List<String> stacks = new ArrayList<>();
        for (int i = 0; i < handler.getSlots(); i++) {
            ItemStack stack = handler.getStackInSlot(i);
            if (!stack.isEmpty()) stacks.add(i + "=" + stack.getItem() + "x" + stack.getCount());
        }
        return stacks.isEmpty() ? "<empty>" : String.join(",", stacks);
    }

    /** Mirror Malum's manual crucible interaction after programmatic material placement. */
    static boolean refreshAccelerators(Object crucible, Object level, Object pos) {
        if (crucible == null || level == null || pos == null) return false;
        try {
            java.lang.reflect.Method recalibrate = null;
            // getMethods() includes public default methods declared by Malum's
            // ICatalyzerAccelerationTarget interface.
            for (java.lang.reflect.Method method : crucible.getClass().getMethods()) {
                Class<?>[] parameters = method.getParameterTypes();
                if (method.getName().equals("recalibrateAccelerators")
                        && parameters.length == 2
                        && parameters[0].isInstance(level)
                        && parameters[1].isInstance(pos)) {
                    recalibrate = method;
                    break;
                }
            }
            for (Class<?> current = crucible.getClass();
                 current != null && current != Object.class && recalibrate == null;
                 current = current.getSuperclass()) {
                for (java.lang.reflect.Method method : current.getDeclaredMethods()) {
                    Class<?>[] parameters = method.getParameterTypes();
                    if (method.getName().equals("recalibrateAccelerators")
                            && parameters.length == 2
                            && parameters[0].isInstance(level)
                            && parameters[1].isInstance(pos)) {
                        recalibrate = method;
                        break;
                    }
                }
            }
            if (recalibrate == null) return false;
            recalibrate.setAccessible(true);
            recalibrate.invoke(crucible, level, pos);
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            RSIntegrationMod.LOGGER.debug(
                    "[RSI-Crucible] Accelerator recalibration failed at {}", pos, e);
            return false;
        }
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
    public void onBatchFinished(@NotNull ServerPlayer player) {
        clearSpiritSlots();
        if (!hasReusableCatalystInput()) {
            releaseCatalystSlot(player);
        }
        resetState();
    }

    @Override
    public void releaseReusableMaterials(@NotNull ServerPlayer player) {
        releaseCatalystSlot(player);
    }

    private void releaseCatalystSlot(@Nullable ServerPlayer returnPlayer) {
        if (invCatalyst == null) return;
        boolean allReturned = true;
        for (int i = 0; i < invCatalyst.getSlots(); i++) {
            ItemStack catalyst = invCatalyst.getStackInSlot(i);
            if (catalyst.isEmpty()) continue;
            if (tryReturnCrucibleItem(catalyst.copy(), returnPlayer)) {
                setSlot(invCatalyst, i, ItemStack.EMPTY);
            } else {
                allReturned = false;
            }
        }
        if (crucibleBE instanceof BlockEntity be) be.setChanged();
        if (allReturned) {
            catalystReturnEndpoint = null;
            catalystReturnNetwork = null;
        }
    }

    private boolean hasReusableCatalystInput() {
        List<IngredientSpec> specs = getRequiredMaterials();
        return specs != null && !specs.isEmpty()
                && specs.get(0).role() == DemandRole.CATALYST;
    }

    @Override
    public BlockPos getMachinePos() {
        return myPos;
    }

    @Override
    public ItemStack getExpectedOutput() {
        // Output drops as a world ItemEntity near the crucible — expose it so the
        // interceptor can grab it before any magnet does.
        return (expectedOutput != null && !expectedOutput.isEmpty()) ? expectedOutput : null;
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

    static void placePreparedInputs(IItemHandler spiritHandler,
                                    List<ItemStack> spiritStacks,
                                    IItemHandler catalystHandler,
                                    ItemStack catalystStack) {
        for (int slot = 0; slot < spiritStacks.size() && slot < spiritHandler.getSlots(); slot++) {
            ItemStack spirit = spiritStacks.get(slot);
            if (!spirit.isEmpty()) setSlot(spiritHandler, slot, spirit);
        }
        if (!catalystStack.isEmpty()) setSlot(catalystHandler, 0, catalystStack);
    }

    private void clearSpiritSlots() {
        if (invSpirits != null) {
            for (int i = 0; i < invSpirits.getSlots(); i++) {
                ItemStack s = invSpirits.getStackInSlot(i);
                if (!s.isEmpty()) {
                    if (!usingSharedLedger) returnCrucibleItem(s);
                    setSlot(invSpirits, i, ItemStack.EMPTY);
                }
            }
        }
        if (crucibleBE instanceof net.minecraft.world.level.block.entity.BlockEntity be) be.setChanged();
    }

    private void clearAllSlots() {
        // A committed shared ledger may only refund what was physically recovered
        // from the machine. Do not discard the stacks before the ledger sees them.
        final List<ItemStack> recovered = new ArrayList<>();
        if (invCatalyst != null) {
            for (int i = 0; i < invCatalyst.getSlots(); i++) {
                ItemStack s = invCatalyst.getStackInSlot(i);
                if (!s.isEmpty()) {
                    recovered.add(s.copy());
                    setSlot(invCatalyst, i, ItemStack.EMPTY);
                }
            }
        }
        if (invSpirits != null) {
            for (int i = 0; i < invSpirits.getSlots(); i++) {
                ItemStack s = invSpirits.getStackInSlot(i);
                if (!s.isEmpty()) {
                    recovered.add(s.copy());
                    setSlot(invSpirits, i, ItemStack.EMPTY);
                }
            }
        }
        if (usingSharedLedger) {
            recordFailureRecoveredInputs(recovered);
        } else {
            for (ItemStack stack : recovered) returnCrucibleItem(stack);
        }
        if (crucibleBE instanceof net.minecraft.world.level.block.entity.BlockEntity be) {
            be.setChanged();
        }
    }

    /** Remove templates placed before commit without minting refunds. */
    private void clearUncommittedPlacements() {
        // Shared-ledger callers have already committed the physical extraction
        // before invoking tryStartWithMaterials. Leave slots intact so the
        // terminal cleanup can recover and audit the exact stacks.
        if (usingSharedLedger) return;
        if (invCatalyst != null) {
            for (int i = 0; i < invCatalyst.getSlots(); i++) {
                setSlot(invCatalyst, i, ItemStack.EMPTY);
            }
        }
        if (invSpirits != null) {
            for (int i = 0; i < invSpirits.getSlots(); i++) {
                setSlot(invSpirits, i, ItemStack.EMPTY);
            }
        }
        if (crucibleBE instanceof BlockEntity be) be.setChanged();
    }

    private void returnCrucibleItem(ItemStack stack) {
        tryReturnCrucibleItem(stack, player);
    }

    private boolean tryReturnCrucibleItem(ItemStack stack,
                                          @Nullable ServerPlayer returnPlayer) {
        if (stack.isEmpty()) return true;
        CraftStorageEndpoint endpoint = storageEndpoint();
        if (endpoint == null) endpoint = catalystReturnEndpoint;
        INetwork returnNetwork = network != null ? network : catalystReturnNetwork;
        if (endpoint == null && returnNetwork == null && returnPlayer != null) {
            returnNetwork = CraftPacketUtils
                    .resolveNetworkForCraft(returnPlayer, myLevel.dimension(), myPos);
        }
        if (endpoint != null && returnPlayer != null) {
            ItemStack leftover = endpoint.insert(returnPlayer, stack.copy(), false)
                    .remainder().orElseGet(stack::copy);
            if (!leftover.isEmpty()) {
                PlayerUtils.safeGiveToPlayer(returnPlayer, leftover, returnNetwork);
            }
            return true;
        } else if (returnNetwork != null && returnPlayer != null) {
            ItemStack leftover = CraftStorageEndpoints.insertLegacy(
                    returnNetwork, returnPlayer, stack.copy(), false);
            if (!leftover.isEmpty()) {
                PlayerUtils.safeGiveToPlayer(returnPlayer, leftover, returnNetwork);
            }
            return true;
        } else if (returnPlayer != null) {
            PlayerUtils.safeGiveToPlayer(returnPlayer, stack.copy(), null);
            return true;
        }
        return false;
    }

    private ItemStack extractFromRS(ServerPlayer player, net.minecraft.world.item.crafting.Ingredient ingredient,
                                    int count, ExtractionLedger ledger, boolean useShared) {
        if (storageEndpoint() != null) {
            return ledger.reserveFromEndpoint(ingredient, count, storageEndpoint(), player);
        }
        if (this.network == null) {
            this.network = CraftPacketUtils
                    .resolveNetworkForCraft(player, myLevel.dimension(), myPos);
        }
        if (this.network == null) return ItemStack.EMPTY;

        return ledger.reserveFromNetwork(ingredient, count, this.network);
    }

    // ── plan warnings ─────────────────────────────────────────────

    public static List<Component> getPlanWarnings(ServerPlayer player, Recipe<?> recipe,
                                               @Nullable ResourceLocation dim,
                                               @Nullable BlockPos pos) {
        List<Component> warnings = new ArrayList<>();
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
                        String.join(", ", names)));
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
                                spiritInv.getSlots(), spirits.size()));
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
