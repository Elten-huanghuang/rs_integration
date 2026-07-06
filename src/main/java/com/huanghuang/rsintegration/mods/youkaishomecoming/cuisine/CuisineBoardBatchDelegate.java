package com.huanghuang.rsintegration.mods.youkaishomecoming.cuisine;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.batch.IBatchDelegate;
import com.huanghuang.rsintegration.reflection.probes.YHKReflection;
import com.refinedmods.refinedstorage.api.network.INetwork;
import com.refinedmods.refinedstorage.api.util.Action;
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
import net.minecraftforge.common.world.ForgeChunkManager;
import net.minecraftforge.items.ItemHandlerHelper;

import javax.annotation.Nullable;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class CuisineBoardBatchDelegate implements IBatchDelegate {

    private ServerPlayer player;
    private ServerLevel myLevel;
    private ResourceKey<Level> myDim;
    private BlockPos myPos;
    private Recipe<?> recipe;
    private INetwork network;
    private boolean craftDone;
    private ItemStack resultItem = ItemStack.EMPTY;

    private static volatile Method addItemMethod;
    private static volatile Method addToPlayerMethod;
    private static volatile Method clearMethod;
    private static volatile Method performToolActionMethod;
    private static volatile Method getModelMethod;
    private static volatile Method completeMethod;
    private static volatile Method doTransformMethod;
    private static volatile Method getCustomIngredientsMethod;
    private static volatile Method getResultMethod;
    private static volatile Method recreateMethod;
    private static volatile Method collectIngredientsMethod;
    private static volatile boolean reflectionProbed;

    @Override
    public boolean validateAndInit(ServerPlayer player, ResourceLocation recipeId,
                                   @Nullable ResourceLocation dim, BlockPos pos) {
        ServerLevel level = CraftPacketUtils.resolveLevel(player.server, dim, player);
        if (level == null) {
            player.sendSystemMessage(Component.translatable("rsi.generic.error.dim_not_found"));
            return false;
        }
        this.myLevel = level;
        this.myDim = level.dimension();
        this.myPos = pos;
        this.player = player;

        Recipe<?> found = level.getRecipeManager().byKey(recipeId).orElse(null);
        if (found == null || !isCuisineRecipe(found)) {
            player.sendSystemMessage(Component.translatable("rsi.generic.error.recipe_not_found", recipeId.toString()));
            return false;
        }
        this.recipe = found;
        this.craftDone = false;
        this.resultItem = ItemStack.EMPTY;
        return true;
    }

    @Nullable
    @Override
    public List<IngredientSpec> getRequiredMaterials() {
        probeReflection();
        // Use recreate() + collectIngredients() to get ALL required items
        // including the base model ingredients (rice, kelp/nori, etc.) that
        // getCustomIngredients() doesn't include -- it only returns the
        // recipe-specific input list.
        List<Ingredient> ingredients = collectAllIngredients(recipe);
        if (ingredients.isEmpty()) {
            ingredients = getCustomIngredients(recipe);
            if (!ingredients.isEmpty()) {
                RSIntegrationMod.LOGGER.debug("[RSI-Cuisine] Using getCustomIngredients: {} items",
                        ingredients.size());
            }
        }
        if (ingredients.isEmpty()) {
            ingredients = recipe.getIngredients();
            if (!ingredients.isEmpty()) {
                RSIntegrationMod.LOGGER.debug("[RSI-Cuisine] Using recipe.getIngredients: {} items",
                        ingredients.size());
            }
        }
        if (ingredients.isEmpty()) {
            RSIntegrationMod.LOGGER.warn("[RSI-Cuisine] No ingredients found for {}", recipe.getId());
            return null;
        }
        List<IngredientSpec> specs = new ArrayList<>();
        for (Ingredient ing : ingredients) {
            if (!ing.isEmpty()) specs.add(new IngredientSpec(ing, 1));
        }
        return specs.isEmpty() ? null : specs;
    }

    @Override
    public boolean tryStartSingleCraft(ServerPlayer player) {
        List<IngredientSpec> specs = getRequiredMaterials();
        if (specs == null || specs.isEmpty()) return false;

        List<ItemStack> materials = new ArrayList<>();
        ExtractionLedger ledger = new ExtractionLedger();
        this.network = CraftPacketUtils.resolveNetworkForCraft(player, myDim, myPos);
        if (this.network == null) return false;

        for (IngredientSpec spec : specs) {
            if (spec.isEmpty()) continue;
            ItemStack reserved = CraftPacketUtils.ensureMaterialAvailable(
                    player, myDim, myPos, spec.ingredient(), spec.count(), ledger);
            if (reserved.isEmpty()) {
                ledger.rollback(player);
                return false;
            }
            materials.add(reserved.copy());
        }

        if (!ledger.commit(network, player)) return false;

        if (!tryStartWithMaterials(player, materials, ledger)) {
            for (ItemStack mat : materials) {
                if (!mat.isEmpty())
                    network.insertItem(mat.copy(), mat.getCount(), Action.PERFORM);
            }
            return false;
        }
        return true;
    }

    @Override
    public boolean tryStartWithMaterials(ServerPlayer player, List<ItemStack> materials,
                                         ExtractionLedger sharedLedger) {
        this.player = player;
        this.craftDone = false;
        this.resultItem = ItemStack.EMPTY;

        forceChunkLoad(true);
        myLevel.getChunk(myPos);

        BlockEntity be = myLevel.getBlockEntity(myPos);
        if (be == null || !isCuisineBE(be)) {
            RSIntegrationMod.LOGGER.warn("[RSI-Cuisine] BE not found or not CuisineBE at {} class={}",
                    myPos, be != null ? be.getClass().getName() : "null");
            player.sendSystemMessage(Component.translatable("rsi.generic.error.machine_not_found"));
            forceChunkLoad(false);
            return false;
        }

        probeReflection();

        // Clear any previous items still on the board
        if (getModel(be) != null) {
            addToPlayer(be, player);
        }

        // Place items via addItem().  The model tree has transition states
        // with EMPTY ingredient (RICE->SUSHI->GUNKAN etc.) that require
        // empty-hand clicks to advance through.  When a material fails to
        // place, try an empty click to advance the model; when all materials
        // are placed, continue empty-clicking until the model stops changing.
        List<ItemStack> pending = new ArrayList<>();
        for (ItemStack mat : materials) {
            if (!mat.isEmpty()) pending.add(mat.copyWithCount(1));
        }
        int stuck = 0;
        int maxSteps = pending.size() * 3 + 10; // generous upper bound
        while (!pending.isEmpty() && stuck < 10 && maxSteps-- > 0) {
            boolean progress = false;

            // Try placing each pending material
            var it = pending.iterator();
            while (it.hasNext()) {
                ItemStack single = it.next();
                int result = addItem(be, single);
                if (result <= 0) continue;
                it.remove();
                progress = true;
                RSIntegrationMod.LOGGER.debug("[RSI-Cuisine] addItem ok, consumed {} of {}",
                        result, single.getHoverName().getString());
            }

            if (pending.isEmpty()) break;

            // Material placement failed -- try empty click to advance model
            int er = addItem(be, ItemStack.EMPTY);
            if (er > 0) {
                progress = true;
                RSIntegrationMod.LOGGER.debug("[RSI-Cuisine] empty click advanced model");
            }

            if (!progress) stuck++;
            else stuck = 0;
        }

        // Advance through remaining transition states with empty clicks
        for (int i = 0; i < 20 && getModel(be) != null; i++) {
            int r = addItem(be, ItemStack.EMPTY);
            if (r <= 0) break;
        }

        if (!pending.isEmpty()) {
            RSIntegrationMod.LOGGER.warn("[RSI-Cuisine] {} items could not be placed:",
                    pending.size());
            for (ItemStack left : pending) {
                RSIntegrationMod.LOGGER.warn("[RSI-Cuisine]   - {}", left.getHoverName().getString());
            }
            addToPlayer(be, player);
            forceChunkLoad(false);
            return false;
        }

        be.setChanged();
        RSIntegrationMod.LOGGER.debug("[RSI-Cuisine] {} items placed on board", materials.size());
        return true;
    }

    @Override
    public boolean isCraftComplete(ServerLevel level) {
        if (craftDone) return true;

        BlockEntity be = level.getBlockEntity(myPos);
        if (be == null || !isCuisineBE(be)) return false;

        // Keep advancing the model with empty clicks.  The model tree has
        // transition states (EMPTY ingredient) that require empty-hand
        // right-clicks to pass through; each addItem(EMPTY) simulates that.
        for (int i = 0; i < 20; i++) {
            Object model = getModel(be);
            if (model == null) {
                craftDone = true;
                return true;
            }
            int r = addItem(be, ItemStack.EMPTY);
            if (r <= 0) break;
            RSIntegrationMod.LOGGER.debug("[RSI-Cuisine] empty click advanced model (step {})", i);
        }

        // Try complete -- returns result when model is fully assembled
        Object model = getModel(be);
        if (model == null) {
            craftDone = true;
            return true;
        }

        resultItem = completeModelGetResult(model, myLevel);
        if (!resultItem.isEmpty()) {
            RSIntegrationMod.LOGGER.debug("[RSI-Cuisine] Assembly complete, result={}",
                    resultItem.getHoverName().getString());
            be.setChanged();
            return true;
        }

        return false;
    }

    @Override
    public ItemStack collectResult(ServerPlayer player) {
        BlockEntity be = myLevel.getBlockEntity(myPos);

        if (!resultItem.isEmpty()) {
            ItemStack r = resultItem.copy();
            RSIntegrationMod.LOGGER.debug("[RSI-Cuisine] collectResult: returning {}",
                    r.getHoverName().getString());
            craftDone = true;
            resultItem = ItemStack.EMPTY;
            // Remove the completed dish model from the board
            if (be != null && isCuisineBE(be)) {
                clearModel(be);
                be.setChanged();
            }
            forceChunkLoad(false);
            return r;
        }

        if (be != null && isCuisineBE(be)) {
            Object model = getModel(be);
            if (model == null) {
                // Board is empty -- nothing to collect, craft was manually completed
                RSIntegrationMod.LOGGER.debug("[RSI-Cuisine] collectResult: board empty, already taken");
                craftDone = true;
                forceChunkLoad(false);
                return ItemStack.EMPTY;
            }
            // Last-resort: try complete one final time
            ItemStack s = completeModelGetResult(model, myLevel);
            if (!s.isEmpty()) {
                be.setChanged();
                craftDone = true;
                forceChunkLoad(false);
                return s.copy();
            }
        }

        craftDone = true;
        forceChunkLoad(false);
        RSIntegrationMod.LOGGER.warn("[RSI-Cuisine] collectResult: no result available");
        return recipe.getResultItem(myLevel.registryAccess()).copy();
    }

    @Override
    public void onBatchFailed(ServerPlayer player, String reason) {
        clearAndRefund();
        forceChunkLoad(false);
        craftDone = false;
        resultItem = ItemStack.EMPTY;
        network = null;
    }

    @Override
    public void onBatchFinished(ServerPlayer player) {
        BlockEntity be = myLevel.getBlockEntity(myPos);
        if (be != null && isCuisineBE(be)) {
            clearModel(be);
            be.setChanged();
        }
        forceChunkLoad(false);
        craftDone = false;
        resultItem = ItemStack.EMPTY;
        network = null;
    }

    @Override
    public BlockPos getMachinePos() { return myPos; }

    // -- plan helpers --

    public static void addFuelIfNeeded(@Nullable String recipeModTypeId,
                                       Map<Item, Integer> itemAvailable,
                                       Map<Item, Ingredient> itemSource,
                                       Map<Item, Integer> neededCounts,
                                       int repeatCount) {}

    public static List<String> getPlanWarnings(ServerPlayer player, Recipe<?> recipe,
                                                @Nullable ResourceLocation dim,
                                                @Nullable BlockPos pos) {
        return new ArrayList<>();
    }

    // -- recipe accessors --

    @SuppressWarnings("unchecked")
    private static List<Ingredient> getCustomIngredients(Recipe<?> recipe) {
        if (getCustomIngredientsMethod != null) {
            try {
                return (List<Ingredient>) getCustomIngredientsMethod.invoke(recipe);
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.warn("[RSI-CuisineBoard] getCustomIngredients reflection failed: {}", e.toString());
            }
        }
        return List.of();
    }

    private static ItemStack getRecipeResult(Recipe<?> recipe) {
        if (getResultMethod != null) {
            try {
                return (ItemStack) getResultMethod.invoke(recipe);
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.warn("[RSI-CuisineBoard] getRecipeResult reflection failed: {}", e.toString());
            }
        }
        return ItemStack.EMPTY;
    }

    // -- complete ingredient collection --
    //
    // TableItem.collectIngredients(List) is a default no-op (verified from
    // 2.6.1 bytecode).  The correct API is VariantTableItemBase.collectIngredients
    // (List, List) on the base model, which delegates to IngredientTableItem
    // .collectIngredients(List) -- that override recurses through the tree and
    // actually adds ingredients.  We union base-model ingredients with
    // recipe-specific ingredients from getCustomIngredients().

    @SuppressWarnings("unchecked")
    private static List<Ingredient> collectAllIngredients(Recipe<?> recipe) {
        List<Ingredient> all = new ArrayList<>();

        // 1. Base-model ingredients from VariantTableItemBase.MAP (or IngredientTableItem.FIXED)
        try {
            Method baseMethod = recipe.getClass().getMethod("base");
            ResourceLocation baseId = (ResourceLocation) baseMethod.invoke(recipe);

            // Preferred path: VariantTableItemBase (covers most recipes)
            if (YHKReflection.variantTableItemBaseClass != null) {
                try {
                    java.lang.reflect.Field mapField = YHKReflection.variantTableItemBaseClass.getField("MAP");
                    Map<?, ?> map = (Map<?, ?>) mapField.get(null);
                    Object vtb = map.get(baseId);
                    if (vtb != null) {
                        List<Ingredient> baseList = new ArrayList<>();
                        List<Ingredient> extraList = new ArrayList<>();
                        java.lang.reflect.Method cm = YHKReflection.variantTableItemBaseClass.getMethod(
                                "collectIngredients", List.class, List.class);
                        cm.invoke(vtb, baseList, extraList);
                        for (Ingredient ing : baseList) {
                            if (!ing.isEmpty()) all.add(ing);
                        }
                    }
                } catch (Exception e) {
                    RSIntegrationMod.LOGGER.debug("[RSI-Cuisine] VTB collect failed: {}",
                            e.toString());
                }
            }

            // Fallback: IngredientTableItem.FIXED (standalone fixed items)
            if (all.isEmpty() && YHKReflection.ingredientTableItemClass != null) {
                try {
                    java.lang.reflect.Field fixedField = YHKReflection.ingredientTableItemClass.getField("FIXED");
                    Map<?, ?> fixedMap = (Map<?, ?>) fixedField.get(null);
                    Object fixed = fixedMap.get(baseId);
                    if (fixed != null) {
                        java.lang.reflect.Method cm = YHKReflection.ingredientTableItemClass.getMethod(
                                "collectIngredients", List.class);
                        cm.invoke(fixed, all);
                    }
                } catch (Exception e) {
                    RSIntegrationMod.LOGGER.debug("[RSI-Cuisine] FIXED collect failed: {}",
                            e.toString());
                }
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-Cuisine] base lookup failed: {}", e.toString());
        }

        // 2. Recipe-specific ingredients (custom per-recipe)
        try {
            java.lang.reflect.Method gci = recipe.getClass().getMethod("getCustomIngredients");
            List<Ingredient> custom = (List<Ingredient>) gci.invoke(recipe);
            if (custom != null) {
                for (Ingredient ing : custom) {
                    if (!ing.isEmpty()) all.add(ing);
                }
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-CuisineBoard] collectAllIngredients reflection failed: {}", e.toString());
        }

        if (!all.isEmpty()) {
            RSIntegrationMod.LOGGER.debug("[RSI-Cuisine] collectAllIngredients: {} items", all.size());
        }
        return all;
    }

    // -- reflection --

    private static void probeReflection() {
        if (reflectionProbed) return;
        reflectionProbed = true;
        if (YHKReflection.cuisineBoardBEClass == null) {
            RSIntegrationMod.LOGGER.warn("[RSI-Cuisine] YHKReflection cuisine probe not ready");
            return;
        }
        try {
            addItemMethod = YHKReflection.cuisineBoardBEClass.getMethod("addItem", ItemStack.class);
            addItemMethod.setAccessible(true);

            addToPlayerMethod = YHKReflection.cuisineBoardBEClass.getMethod("addToPlayer",
                    net.minecraft.world.entity.player.Player.class);
            addToPlayerMethod.setAccessible(true);

            clearMethod = YHKReflection.cuisineBoardBEClass.getMethod("clear");
            clearMethod.setAccessible(true);

            performToolActionMethod = YHKReflection.cuisineBoardBEClass.getMethod("performToolAction", ItemStack.class);
            performToolActionMethod.setAccessible(true);

            getModelMethod = YHKReflection.cuisineBoardBEClass.getMethod("getModel");
            getModelMethod.setAccessible(true);

            if (YHKReflection.tableItemClass != null) {
                completeMethod = YHKReflection.tableItemClass.getMethod("complete", Level.class);
                doTransformMethod = YHKReflection.tableItemClass.getMethod("doTransform");
            }

            if (YHKReflection.cuisineRecipeClass != null) {
                getCustomIngredientsMethod = YHKReflection.cuisineRecipeClass.getMethod("getCustomIngredients");
                getCustomIngredientsMethod.setAccessible(true);
                getResultMethod = YHKReflection.cuisineRecipeClass.getMethod("getResult");
                getResultMethod.setAccessible(true);
                recreateMethod = YHKReflection.cuisineRecipeClass.getMethod("recreate");
                recreateMethod.setAccessible(true);
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-Cuisine] Reflection probe failed: {}", e.toString());
        }
    }

    @Nullable
    private static Method findMethodInHierarchy(Class<?> clazz, String name, Class<?>... paramTypes) {
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            try {
                return current.getDeclaredMethod(name, paramTypes);
            } catch (NoSuchMethodException e) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    private static boolean isCuisineBE(BlockEntity be) {
        probeReflection();
        if (YHKReflection.cuisineBoardBEClass != null && YHKReflection.cuisineBoardBEClass.isAssignableFrom(be.getClass())) return true;
        Class<?> clazz = be.getClass();
        while (clazz != null) {
            if (YHKReflection.cuisineBoardBEClass != null && YHKReflection.cuisineBoardBEClass.getName().equals(clazz.getName())) return true;
            clazz = clazz.getSuperclass();
        }
        return false;
    }

    private static int addItem(BlockEntity be, ItemStack stack) {
        if (addItemMethod != null) {
            try {
                return (int) addItemMethod.invoke(be, stack);
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.warn("[RSI-CuisineBoard] addItem reflection failed: {}", e.toString());
            }
        }
        return -1;
    }

    private static boolean addToPlayer(BlockEntity be, ServerPlayer player) {
        if (addToPlayerMethod != null) {
            try {
                return (boolean) addToPlayerMethod.invoke(be, player);
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.warn("[RSI-CuisineBoard] addToPlayer reflection failed: {}", e.toString());
            }
        }
        return false;
    }

    private static boolean performToolAction(BlockEntity be, ItemStack tool) {
        if (performToolActionMethod != null) {
            try {
                return (boolean) performToolActionMethod.invoke(be, tool);
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.warn("[RSI-CuisineBoard] performToolAction reflection failed: {}", e.toString());
            }
        }
        return false;
    }

    @Nullable
    private static Object getModel(BlockEntity be) {
        if (getModelMethod != null) {
            try {
                return getModelMethod.invoke(be);
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.warn("[RSI-CuisineBoard] getModel reflection failed: {}", e.toString());
            }
        }
        return null;
    }

    @Nullable
    private static ItemStack completeModelGetResult(Object model, Level level) {
        if (completeMethod != null && model != null) {
            try {
                Object result = completeMethod.invoke(model, level);
                if (result instanceof java.util.Optional<?> opt && opt.isPresent()) {
                    if (opt.get() instanceof ItemStack s && !s.isEmpty()) {
                        return s;
                    }
                }
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.warn("[RSI-CuisineBoard] completeModelGetResult reflection failed: {}", e.toString());
            }
        }
        return ItemStack.EMPTY;
    }

    private static void clearModel(BlockEntity be) {
        if (clearMethod != null) {
            try {
                clearMethod.invoke(be);
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.warn("[RSI-CuisineBoard] clearModel reflection failed: {}", e.toString());
            }
        }
    }

    private static boolean isCuisineRecipe(Recipe<?> recipe) {
        if (YHKReflection.cuisineRecipeClass == null) return false;
        if (YHKReflection.cuisineRecipeClass.isAssignableFrom(recipe.getClass())) return true;
        Class<?> clazz = recipe.getClass();
        while (clazz != null) {
            if (YHKReflection.cuisineRecipeClass.getName().equals(clazz.getName())) return true;
            clazz = clazz.getSuperclass();
        }
        return false;
    }

    // -- cleanup --

    private void clearAndRefund() {
        myLevel.getChunk(myPos);
        BlockEntity be = myLevel.getBlockEntity(myPos);
        if (be == null || !isCuisineBE(be)) return;

        // If the model is gone (player manually completed the craft and took
        // the result), don't refund anything -- that would duplicate items.
        Object model = getModel(be);
        if (model == null) {
            RSIntegrationMod.LOGGER.debug("[RSI-Cuisine] clearAndRefund: board empty, skipping refund");
            return;
        }

        if (player != null && !player.isRemoved()) {
            addToPlayer(be, player);
        }
        be.setChanged();
    }

    private void forceChunkLoad(boolean load) {
        try {
            int cx = myPos.getX() >> 4;
            int cz = myPos.getZ() >> 4;
            ForgeChunkManager.forceChunk(myLevel, RSIntegrationMod.MOD_ID, myPos, cx, cz, load, true);
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-Cuisine] Chunk load failed: {}", e.toString());
        }
    }
}
