package com.huanghuang.rsintegration.batch.delegate;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.batch.IBatchDelegate;
import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.ModRecipeIndex;
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

import javax.annotation.Nullable;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

public final class GoetyBatchDelegate implements IBatchDelegate {

    // ── Shared class refs ────────────────────────────────────────
    private static volatile boolean classesLoaded;
    private static volatile Class<?> darkAltarBEClass;
    private static volatile Class<?> pedestalBEClass;
    private static volatile Class<?> ritualRecipeClass;
    private static volatile Class<?> ritualClass;
    private static volatile Class<?> seHelperClass;       // com.Polarice3.Goety.utils.SEHelper

    private static void ensureClasses() {
        if (classesLoaded) return;
        classesLoaded = true;
        try {
            darkAltarBEClass = Class.forName(
                    "com.Polarice3.Goety.common.blocks.entities.DarkAltarBlockEntity");
            pedestalBEClass = Class.forName(
                    "com.Polarice3.Goety.common.blocks.entities.PedestalBlockEntity");
            ritualRecipeClass = Class.forName(
                    "com.Polarice3.Goety.common.crafting.RitualRecipe");
            ritualClass = Class.forName(
                    "com.Polarice3.Goety.common.ritual.Ritual");
            seHelperClass = Class.forName(
                    "com.Polarice3.Goety.utils.SEHelper");
        } catch (ClassNotFoundException e) {
            RSIntegrationMod.LOGGER.error("[RSI-Batch-Goety] Failed to load Goety classes", e);
        }
    }

    // ── Instance state ───────────────────────────────────────────
    private ServerPlayer player;
    private ResourceKey<Level> myDim;
    private BlockPos myPos;
    private Object altar;                // DarkAltarBlockEntity
    private Recipe<?> recipe;            // RitualRecipe
    private ExtractionLedger ledger;
    private ExtractionLedger sharedLedger;
    private INetwork network;
    private List<Object> filledPedestals;
    private int soulCost;
    private boolean soulsConsumed;
    private boolean usingSharedLedger;
    private boolean ritualEverSeenActive; // guards against premature completion

    // ── IBatchDelegate impl ───────────────────────────────────────

    @Override
    public boolean validateAndInit(ServerPlayer player, ResourceLocation recipeId,
                                   @Nullable ResourceLocation dim, BlockPos pos) {
        ensureClasses();
        if (darkAltarBEClass == null || seHelperClass == null) {
            player.sendSystemMessage(Component.translatable("rsi.batch.error.mod_missing", "Goety"));
            return false;
        }

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
        if (be == null || !darkAltarBEClass.isInstance(be)) {
            player.sendSystemMessage(Component.translatable("rsi.goety.error.altar_not_found"));
            return false;
        }
        this.altar = be;

        // Look up recipe from vanilla RecipeManager (Goety RitualRecipe extends vanilla Recipe)
        Recipe<?> foundRecipe = level.getRecipeManager().byKey(recipeId).orElse(null);
        if (foundRecipe == null) {
            player.sendSystemMessage(Component.translatable("rsi.generic.error.recipe_not_found", recipeId.toString()));
            return false;
        }
        if (!ritualRecipeClass.isInstance(foundRecipe)) {
            player.sendSystemMessage(Component.translatable("rsi.generic.error.wrong_recipe_type"));
            return false;
        }
        this.recipe = foundRecipe;
        this.ritualEverSeenActive = false;

        // Read soul cost from recipe
        this.soulCost = readSoulCost(foundRecipe);

        // Validate idle: getCurrentRitualRecipe() == null
        try {
            Object currentRitual = getMethod(darkAltarBEClass, "getCurrentRitualRecipe")
                    .invoke(altar);
            if (currentRitual != null) {
                player.sendSystemMessage(Component.translatable("rsi.goety.warn.altar_busy"));
                return false;
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-Goety] Failed to check altar state", e);
        }

        RSIntegrationMod.LOGGER.debug("[RSI-Batch-Goety] validateAndInit OK: recipe={}", recipeId);
        return true;
    }

    @Override
    public boolean tryStartSingleCraft(ServerPlayer player) {
        this.player = player;
        this.ledger = new ExtractionLedger();
        this.network = CraftPacketUtils.resolveNetworkForCraft(player, myDim, myPos);

        // Re-validate idle
        try {
            Object currentRitual = getMethod(darkAltarBEClass, "getCurrentRitualRecipe")
                    .invoke(altar);
            if (currentRitual != null) return false;
        } catch (Exception ignored) {}

        this.filledPedestals = new ArrayList<>();

        // Consume soul energy
        if (!checkAndConsumeSouls(player, soulCost)) {
            return false;
        }

        // Get the ritual object from recipe
        Object ritual;
        try {
            ritual = getGetter(ritualRecipeClass, "getRitual").invoke(recipe);
            validateRitual(ritual);
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.error("[RSI-Batch-Goety] Failed to get/validate ritual from recipe", e);
            returnSouls(player, soulCost);
            return false;
        }

        // Collect required ingredients
        List<IngredientSpec> specList = collectIngredients(recipe);
        if (specList.isEmpty()) {
            if (!checkRitualPrerequisites(ritual)) {
                returnSouls(player, soulCost);
                return false;
            }
            try {
                startRitual(player, ritual);
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.error("[RSI-Batch-Goety] Failed to start ritual", e);
                returnSouls(player, soulCost);
                return false;
            }
            return true;
        }

        // Find pedestals
        List<Object> availablePedestals = findAvailablePedestals(player.serverLevel());
        if (availablePedestals.size() < specList.size()) {
            returnSouls(player, soulCost);
            return false;
        }

        // Phase 1: reserve all ingredients via ledger (virtual — no extraction yet)
        List<ItemStack> templates = new ArrayList<>();
        for (int i = 0; i < specList.size(); i++) {
            IngredientSpec spec = specList.get(i);
            if (spec.isEmpty()) {
                templates.add(ItemStack.EMPTY);
                continue;
            }
            ItemStack stack = CraftPacketUtils.ensureMaterialAvailable(player, myDim, myPos, spec.ingredient(), spec.count(), ledger);
            if (stack.isEmpty()) {
                returnSouls(player, soulCost);
                return false;
            }
            templates.add(stack);
        }

        // Validate ritual prerequisites (scrolls, biome, etc.) before committing
        // to avoid extracting items for a ritual that can't start.
        if (!checkRitualPrerequisites(ritual)) {
            returnSouls(player, soulCost);
            return false;
        }

        // Phase 2: commit all extractions atomically
        if (!ledger.commit(network, player)) {
            RSIntegrationMod.LOGGER.error("[RSI-Batch-Goety] Ledger commit failed");
            returnSouls(player, soulCost);
            return false;
        }

        // Phase 3: place items and start ritual
        try {
            for (int i = 0; i < templates.size(); i++) {
                ItemStack stack = templates.get(i);
                if (stack.isEmpty()) continue;
                Object ped = availablePedestals.get(i);
                ped.getClass().getMethod("setItem", int.class, ItemStack.class).invoke(ped, 0, stack);
                filledPedestals.add(ped);
            }

            ItemStack activationItem = getActivationItem(recipe);
            if (activationItem == null || activationItem.isEmpty()) {
                activationItem = ItemStack.EMPTY;
            }
            startRitual(player, activationItem, ritual);
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.error("[RSI-Batch-Goety] Extraction/placement failed:", e);
            clearFilledPedestals();
            refundAll();
            ledger = null;
            returnSouls(player, soulCost);
            return false;
        }

        return true;
    }

    // ── Chain support: pre-reserved materials ────────────────────

    @Override
    @Nullable
    public List<IngredientSpec> getRequiredMaterials() {
        if (recipe == null) return null;
        List<IngredientSpec> specs = collectIngredients(recipe);
        return specs.isEmpty() ? null : specs;
    }

    @Override
    public boolean tryStartWithMaterials(ServerPlayer player, List<ItemStack> materials,
                                         ExtractionLedger sharedLedger) {
        this.player = player;
        this.sharedLedger = sharedLedger;
        this.usingSharedLedger = true;

        // Re-validate idle
        try {
            Object currentRitual = getMethod(darkAltarBEClass, "getCurrentRitualRecipe")
                    .invoke(altar);
            if (currentRitual != null) return false;
        } catch (Exception ignored) {}

        this.filledPedestals = new ArrayList<>();

        // Consume soul energy
        if (!checkAndConsumeSouls(player, soulCost)) return false;

        // Get the ritual object from recipe
        Object ritual;
        try {
            ritual = getGetter(ritualRecipeClass, "getRitual").invoke(recipe);
            validateRitual(ritual);
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.error("[RSI-Batch-Goety] Failed to get/validate ritual from recipe", e);
            returnSouls(player, soulCost);
            return false;
        }

        // Validate ritual prerequisites before any item placement
        if (!checkRitualPrerequisites(ritual)) {
            returnSouls(player, soulCost);
            return false;
        }

        // If no materials, just start the ritual
        if (materials.isEmpty()) {
            try {
                startRitual(player, ritual);
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.error("[RSI-Batch-Goety] Failed to start ritual", e);
                returnSouls(player, soulCost);
                return false;
            }
            return true;
        }

        // Find pedestals (filter out empty ones)
        List<Object> availablePedestals = findAvailablePedestals(player.serverLevel());
        if (availablePedestals.size() < materials.size()) {
            returnSouls(player, soulCost);
            return false;
        }

        // Place materials and start ritual
        try {
            for (int i = 0; i < materials.size(); i++) {
                ItemStack stack = materials.get(i);
                if (stack.isEmpty()) continue;
                Object ped = availablePedestals.get(i);
                ped.getClass().getMethod("setItem", int.class, ItemStack.class).invoke(ped, 0, stack);
                filledPedestals.add(ped);
            }

            ItemStack activationItem = getActivationItem(recipe);
            if (activationItem == null || activationItem.isEmpty()) {
                activationItem = ItemStack.EMPTY;
            }
            startRitual(player, activationItem, ritual);
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.error("[RSI-Batch-Goety] Material placement/start failed:", e);
            clearFilledPedestals();
            returnSouls(player, soulCost);
            return false;
        }

        return true;
    }

    @Override
    public boolean isCraftComplete(ServerLevel level) {
        try {
            Object currentRitual = getMethod(darkAltarBEClass, "getCurrentRitualRecipe")
                    .invoke(altar);
            if (currentRitual != null) {
                ritualEverSeenActive = true;
                return false;
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-Goety] isCraftComplete check failed", e);
        }
        if (!ritualEverSeenActive && player != null) {
            // Synchronous completion: result already delivered to player
            ItemStack expected = ModRecipeIndex.tryGetResultItem(recipe, level.registryAccess());
            if (!expected.isEmpty()) {
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
        ItemStack expected = ModRecipeIndex.tryGetResultItem(recipe, player.serverLevel().registryAccess());
        if (expected.isEmpty()) return ItemStack.EMPTY;

        // 1. Check player inventory (Goety auto-delivers the result to the player)
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
        } catch (Throwable ignored) {}

        for (ItemStack stack : all) {
            if (ItemStack.isSameItemSameTags(stack, expected)) {
                ItemStack collected = stack.split(expected.getCount());
                return collected;
            }
        }

        // 2. Fallback: scan for ItemEntity near the altar (result may have spawned
        //    in the world rather than going directly to player inventory)
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

        return ItemStack.EMPTY;
    }

    @Override
    public void onBatchFailed(ServerPlayer player, String reason) {
        ritualEverSeenActive = false;
        clearFilledPedestals();
        if (!usingSharedLedger) {
            refundAll();
        }
        returnSouls(player, soulCost);
        ledger = null;
        sharedLedger = null;
        network = null;
        usingSharedLedger = false;
    }

    @Override
    public void onBatchFinished(ServerPlayer player) {
        ritualEverSeenActive = false;
        soulsConsumed = false;
        clearFilledPedestals();
        ledger = null;
        sharedLedger = null;
        network = null;
        usingSharedLedger = false;
    }

    @Override
    public BlockPos getMachinePos() {
        return myPos;
    }

    // ── Soul energy ──────────────────────────────────────────────

    private static int readSoulCost(Object recipe) {
        try {
            return (int) recipe.getClass().getMethod("getSoulCost").invoke(recipe);
        } catch (Exception ignored) {
            return 0;
        }
    }

    private boolean checkAndConsumeSouls(ServerPlayer player, int cost) {
        if (cost <= 0) return true;
        try {
            double currentSouls = ((Number) seHelperClass.getMethod("getSESouls",
                    net.minecraft.world.entity.player.Player.class).invoke(null, player))
                    .doubleValue();
            if (currentSouls < cost) {
                player.displayClientMessage(
                        Component.translatable("rsi.goety.error.insufficient_souls", cost,
                                String.format("%.0f", currentSouls)), true);
                return false;
            }
            double newSouls = currentSouls - cost;
            try {
                seHelperClass.getMethod("setSESouls",
                        net.minecraft.world.entity.player.Player.class, double.class)
                        .invoke(null, player, newSouls);
            } catch (NoSuchMethodException e) {
                seHelperClass.getMethod("setSESouls",
                        net.minecraft.world.entity.player.Player.class, int.class)
                        .invoke(null, player, (int) newSouls);
            }
            soulsConsumed = true;
            return true;
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.error("[RSI-Batch-Goety] Failed to consume souls", e);
            return false;
        }
    }

    private void returnSouls(ServerPlayer player, int cost) {
        if (cost <= 0 || !soulsConsumed) return;
        soulsConsumed = false;
        try {
            double currentSouls = ((Number) seHelperClass.getMethod("getSESouls",
                    net.minecraft.world.entity.player.Player.class).invoke(null, player))
                    .doubleValue();
            try {
                seHelperClass.getMethod("setSESouls",
                        net.minecraft.world.entity.player.Player.class, double.class)
                        .invoke(null, player, currentSouls + cost);
            } catch (NoSuchMethodException e) {
                seHelperClass.getMethod("setSESouls",
                        net.minecraft.world.entity.player.Player.class, int.class)
                        .invoke(null, player, (int) (currentSouls + cost));
            }
        } catch (Exception ignored) {}
    }

    // ── Recipe ingredient collection ─────────────────────────────

    @SuppressWarnings("unchecked")
    private static List<IngredientSpec> collectIngredients(Recipe<?> recipe) {
        List<IngredientSpec> specs = CraftPacketUtils.extractIngredientSpecs(recipe);
        if (specs != null) return specs;

        // Fallback: try getIngredientsList method
        try {
            Object list = recipe.getClass().getMethod("getIngredientsList").invoke(recipe);
            if (list instanceof List<?> l) {
                List<IngredientSpec> result = new ArrayList<>();
                for (Object obj : l) {
                    if (obj instanceof Ingredient ing) {
                        result.add(new IngredientSpec(ing, 1));
                    }
                }
                if (!result.isEmpty()) return result;
            }
        } catch (Exception ignored) {}

        return new ArrayList<>();
    }

    // ── Pedestal helpers ─────────────────────────────────────────

    private List<Object> findAvailablePedestals(ServerLevel level) {
        List<Object> result = new ArrayList<>();
        try {
            BlockPos.betweenClosedStream(
                    myPos.offset(-5, -3, -5),
                    myPos.offset(5, 3, 5)
            ).forEach(cp -> {
                BlockEntity be = level.getBlockEntity(cp);
                if (be != null && pedestalBEClass.isInstance(be)) {
                    try {
                        ItemStack stack = (ItemStack) be.getClass().getMethod("getItem", int.class)
                                .invoke(be, 0);
                        if (stack.isEmpty()) {
                            result.add(be);
                        }
                    } catch (Exception ignored) {}
                }
            });
        } catch (Exception ignored) {}
        return result;
    }

    private void clearFilledPedestals() {
        if (filledPedestals == null) return;
        for (Object ped : filledPedestals) {
            try {
                ped.getClass().getMethod("setItem", int.class, ItemStack.class)
                        .invoke(ped, 0, ItemStack.EMPTY);
            } catch (Exception ignored) {}
        }
    }

    // ── Prerequisite validation ─────────────────────────────────

    /**
     * Validate ritual prerequisites (scroll reading, biome, weather, etc.)
     * BEFORE committing any items. Must be called after {@code ritual} is
     * obtained but before {@code ledger.commit()}.
     */
    private boolean checkRitualPrerequisites(Object ritual) {
        try {
            // Goety Ritual.canStart(Level, BlockPos, Player)
            java.lang.reflect.Method canStart = ritualClass.getMethod("canStart",
                    Level.class, BlockPos.class, net.minecraft.world.entity.player.Player.class);
            boolean ok = (boolean) canStart.invoke(ritual, player.serverLevel(), myPos, player);
            if (!ok) {
                player.displayClientMessage(
                        Component.translatable("rsi.goety.error.ritual_prerequisites"), true);
                return false;
            }
        } catch (NoSuchMethodException ignored) {
            // Older Goety versions may not have canStart — fall through
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-Batch-Goety] Prerequisite check error", e);
        }
        return true;
    }

    // ── Start ritual ─────────────────────────────────────────────

    /**
     * Validate that {@code ritual} is a non-null instance of {@code ritualClass}
     * (or a subclass thereof). Returns the ritual object if valid, throws otherwise.
     */
    private static Object validateRitual(Object ritual) throws Exception {
        if (ritual == null) {
            throw new IllegalArgumentException("Ritual object is null");
        }
        if (!ritualClass.isInstance(ritual)) {
            throw new IllegalArgumentException(
                    "Ritual object is not a Ritual instance: " + ritual.getClass().getName());
        }
        return ritual;
    }

    /**
     * Call {@code DarkAltarBlockEntity.startRitual(Player, ItemStack, Ritual)}.
     * Tries the exact class of the ritual object first, then falls back to the
     * base {@code Ritual} class, to handle Goety subclasses like
     * {@code SummonRitual}, {@code CraftRitual}, etc.
     */
    private void startRitual(ServerPlayer player, Object ritual) throws Exception {
        startRitual(player, ItemStack.EMPTY, ritual);
    }

    private void startRitual(ServerPlayer player, ItemStack activationItem, Object ritual) throws Exception {
        ritual = validateRitual(ritual);
        // Try exact ritual class first (handles subclasses like SummonRitual)
        java.lang.reflect.Method method = findStartRitualMethod(ritual.getClass());
        if (method == null) {
            // Fallback: try base Ritual class
            method = findStartRitualMethod(ritualClass);
        }
        if (method == null) {
            throw new NoSuchMethodException("startRitual(Player, ItemStack, " +
                    ritualClass.getSimpleName() + ") not found on DarkAltarBlockEntity");
        }
        method.invoke(altar, player, activationItem, ritual);
    }

    @Nullable
    private java.lang.reflect.Method findStartRitualMethod(Class<?> ritualParamType) {
        try {
            return darkAltarBEClass.getMethod("startRitual",
                    net.minecraft.world.entity.player.Player.class,
                    ItemStack.class,
                    ritualParamType);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    // ── Activation item ──────────────────────────────────────────

    @Nullable
    private static ItemStack getActivationItem(Recipe<?> recipe) {
        try {
            // Try getActivationItem()
            Object item = recipe.getClass().getMethod("getActivationItem").invoke(recipe);
            if (item instanceof ItemStack stack) return stack;
        } catch (Exception ignored) {}
        // Try reading 'activationItem' field
        try {
            java.lang.reflect.Field f = getField(recipe.getClass(), "activationItem");
            if (f != null) {
                f.setAccessible(true);
                return (ItemStack) f.get(recipe);
            }
        } catch (Exception ignored) {}
        return ItemStack.EMPTY;
    }

    // ── Reflection helpers ───────────────────────────────────────

    private static Method getMethod(Class<?> clazz, String name) throws NoSuchMethodException {
        return clazz.getMethod(name);
    }

    private static Method getGetter(Class<?> clazz, String name) throws NoSuchMethodException {
        return clazz.getMethod(name);
    }

    @Nullable
    private static java.lang.reflect.Field getField(Class<?> clazz, String name) {
        Class<?> scan = clazz;
        while (scan != null && scan != Object.class) {
            try { return scan.getDeclaredField(name); }
            catch (NoSuchFieldException e) { scan = scan.getSuperclass(); }
        }
        return null;
    }

    // ── Refund ───────────────────────────────────────────────────

    private void refundAll() {
        if (ledger == null || !ledger.isCommitted()) return;
        List<IngredientSpec> specs = collectIngredients(recipe);
        for (IngredientSpec spec : specs) {
            if (spec.isEmpty()) continue;
            ItemStack[] opts = spec.ingredient().getItems();
            if (opts.length > 0 && !opts[0].isEmpty()) {
                ItemStack refund = opts[0].copyWithCount(spec.count());
                if (network != null) {
                    network.insertItem(refund, spec.count(), com.refinedmods.refinedstorage.api.util.Action.PERFORM);
                } else if (player != null) {
                    ItemHandlerHelper.giveItemToPlayer(player, refund);
                }
            }
        }
    }
}
