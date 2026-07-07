package com.huanghuang.rsintegration.mods.farmersdelight;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.recipe.ModRecipeHandlers;
import com.huanghuang.rsintegration.reflection.probes.FarmersDelightReflection;
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
import net.minecraft.world.item.crafting.CampfireCookingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraftforge.common.world.ForgeChunkManager;
import net.minecraftforge.items.ItemHandlerHelper;

import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Batch delegate for Farmer's Delight Skillet (also handles campfire). */
public final class SkilletBatchDelegate extends com.huanghuang.rsintegration.crafting.batch.AbstractBatchDelegate {

    private static final String CAMPFIRE_BE =
            "net.minecraft.world.level.block.entity.CampfireBlockEntity";

    private ServerPlayer player;
    private ServerLevel myLevel;
    private ResourceKey<Level> myDim;
    private BlockPos myPos;
    private Recipe<?> recipe;
    private boolean craftDone;

    // Skillet-specific
    private boolean isSkillet;
    private int skilletPrevTime = -1;
    // Campfire-specific
    private int campfireSlot = -1;
    private Object campfireBE;
    private boolean campfireChunkForced;

    private static volatile Class<?> campfireClass;
    private static final Field CAMPFIRE_ITEMS;
    private static final Field CAMPFIRE_COOKING_PROGRESS;
    private static final Field CAMPFIRE_COOKING_TIME;
    private static volatile boolean reflectionProbed;

    static {
        Field items = null, cookingProgress = null, cookingTime = null;
        try {
            Class<?> cfb = Class.forName(CAMPFIRE_BE);
            items = resolveField(cfb, "items", "f_59042_");
            cookingProgress = resolveField(cfb, "cookingProgress", "f_59043_");
            cookingTime = resolveField(cfb, "cookingTime", "f_59044_");
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-Skillet] Reflection read failed", e);
        }
        CAMPFIRE_ITEMS = items;
        CAMPFIRE_COOKING_PROGRESS = cookingProgress;
        CAMPFIRE_COOKING_TIME = cookingTime;
    }

    private static Field resolveField(Class<?> clazz, String official, String srg) {
        for (String name : new String[]{official, srg}) {
            try {
                Field f = clazz.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException e) { RSIntegrationMod.LOGGER.debug("[RSI-Skillet] field not found", e); }
        }
        return null;
    }

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
        if (found == null) {
            player.sendSystemMessage(Component.translatable("rsi.generic.error.recipe_not_found", recipeId.toString()));
            return false;
        }
        if (!(found instanceof CampfireCookingRecipe)) {
            player.sendSystemMessage(Component.translatable("rsi.generic.error.recipe_not_found", recipeId.toString()));
            return false;
        }
        this.recipe = found;
        this.craftDone = false;

        BlockEntity be = level.getBlockEntity(pos);
        if (be != null && isSkilletBE(be)) {
            this.isSkillet = true;
        }
        return true;
    }

    @Nullable
    @Override
    public List<IngredientSpec> getRequiredMaterials() {
        var handler = ModRecipeHandlers.handlerFor(recipe);
        if (handler != null) {
            return handler.getIngredients(recipe);
        }
        return CraftPacketUtils.extractIngredientSpecs(recipe);
    }

    @Override
    public boolean tryStartSingleCraft(ServerPlayer player) {
        List<IngredientSpec> specs = getRequiredMaterials();
        if (specs == null || specs.isEmpty()) return false;

        List<ItemStack> materials = new ArrayList<>();
        try (ExtractionLedger ledger = new ExtractionLedger()) {
            this.network = CraftPacketUtils.resolveNetworkForCraft(player, myDim, myPos);
            if (this.network == null) return false;

            for (IngredientSpec spec : specs) {
                if (spec.isEmpty()) continue;
                ItemStack reserved = CraftPacketUtils.ensureMaterialAvailable(
                        player, myDim, myPos, spec.ingredient(), spec.count(), ledger);
                if (reserved.isEmpty()) {
                    return false;
                }
                materials.add(reserved.copy());
            }

            if (!ledger.commit(network, player)) return false;

            this.usingSharedLedger = false;
            if (!tryStartWithMaterials(player, materials, ledger)) {
                for (ItemStack mat : materials) {
                    if (!mat.isEmpty())
                        network.insertItem(mat.copy(), mat.getCount(), Action.PERFORM);
                }
                return false;
            }
            return true;
        }
    }

    @Override
    public boolean tryStartWithMaterials(ServerPlayer player, List<ItemStack> materials,
                                         ExtractionLedger sharedLedger) {
        this.player = player;
        this.usingSharedLedger = true;
        this.craftDone = false;

        BlockEntity be = myLevel.getBlockEntity(myPos);
        if (be == null) {
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-Skillet] BlockEntity missing at {}", myPos);
            return false;
        }

        if (materials.isEmpty()) return false;
        ItemStack input = materials.get(0).copyWithCount(1);

        if (isSkilletBE(be)) {
            return tryStartSkillet(be, input);
        } else if (isCampfireBE(be)) {
            return tryStartCampfire(be, input);
        }

        RSIntegrationMod.LOGGER.warn("[RSI-Batch-Skillet] Unknown BE type: {}", be.getClass().getName());
        return false;
    }

    private boolean tryStartSkillet(BlockEntity be, ItemStack input) {
        if (!isHeated(be)) {
            player.sendSystemMessage(Component.translatable("rsi.farmersdelight.no_heat"));
            return false;
        }

        try {
            // FD 1.2.8+ uses addItemToCook(ItemStack, Player); older versions
            // use addItemToCook(ItemStack, CampfireCookingRecipe).
            Method addItem;
            if (hasMethod(be.getClass(), "addItemToCook",
                    ItemStack.class, net.minecraft.world.entity.player.Player.class)) {
                addItem = be.getClass().getMethod("addItemToCook",
                        ItemStack.class, net.minecraft.world.entity.player.Player.class);
                addItem.invoke(be, input, player);
            } else {
                addItem = be.getClass().getMethod("addItemToCook",
                        ItemStack.class, CampfireCookingRecipe.class);
                addItem.invoke(be, input, (CampfireCookingRecipe) recipe);
            }
            be.setChanged();
            campfireForceLoad(true);
            skilletPrevTime = -1;
            RSIntegrationMod.LOGGER.debug("[RSI-Batch-Skillet] Item added to skillet");
            return true;
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.error("[RSI-Batch-Skillet] Failed to add item", e);
            return false;
        }
    }

    private static boolean hasMethod(Class<?> clazz, String name, Class<?>... paramTypes) {
        try {
            clazz.getMethod(name, paramTypes);
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private boolean tryStartCampfire(BlockEntity be, ItemStack input) {
        if (CAMPFIRE_ITEMS == null || CAMPFIRE_COOKING_PROGRESS == null
                || CAMPFIRE_COOKING_TIME == null) return false;

        int cookTime = recipe instanceof CampfireCookingRecipe ccr
                ? ccr.getCookingTime() : 600;

        try {
            var items = (net.minecraft.core.NonNullList<ItemStack>) CAMPFIRE_ITEMS.get(be);
            for (int i = 0; i < items.size(); i++) {
                if (items.get(i).isEmpty()) {
                    items.set(i, input.copy());
                    int[] prog = (int[]) CAMPFIRE_COOKING_PROGRESS.get(be);
                    prog[i] = 0;
                    int[] times = (int[]) CAMPFIRE_COOKING_TIME.get(be);
                    times[i] = cookTime;
                    campfireSlot = i;
                    campfireBE = be;
                    break;
                }
            }
            if (campfireSlot < 0) return false;

            var state = myLevel.getBlockState(myPos);
            if (state.hasProperty(BlockStateProperties.LIT) && !state.getValue(BlockStateProperties.LIT)) {
                myLevel.setBlock(myPos, state.setValue(BlockStateProperties.LIT, true), 3);
            }
            be.setChanged();
            myLevel.sendBlockUpdated(myPos,
                    myLevel.getBlockState(myPos), myLevel.getBlockState(myPos), 3);
            campfireForceLoad(true);
            return true;
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.error("[RSI-Batch-Skillet] Campfire placement failed", e);
            return false;
        }
    }

    @Override
    protected boolean isMachineCraftFinished(ServerLevel level, BlockEntity be) {
        if (isSkilletBE(be)) {
            // Use CAMPFIRE_ITEMS (already resolved in static init) to peek at
            // the stored item. hasStoredStack() returns true even for raw
            // ingredients, so compare against the expected input to determine
            // if cooking has transformed the item.
            if (CAMPFIRE_ITEMS != null) {
                try {
                    @SuppressWarnings("unchecked")
                    var items = (net.minecraft.core.NonNullList<ItemStack>) CAMPFIRE_ITEMS.get(be);
                    if (!items.isEmpty()) {
                        ItemStack stored = items.get(0);
                        if (!stored.isEmpty()) {
                            // If the stored item differs from the recipe input,
                            // cooking has finished and transformed the ingredient.
                            ItemStack rawInput = recipe.getIngredients().get(0).getItems().length > 0
                                    ? recipe.getIngredients().get(0).getItems()[0]
                                    : ItemStack.EMPTY;
                            if (!rawInput.isEmpty() && !ItemStack.isSameItem(stored, rawInput)) {
                                return true;
                            }
                            // If stored item matches input, still cooking.
                            return false;
                        }
                    }
                    // Item was consumed (popped off/smelted) → done cooking
                    return campfireSlot >= 0 && campfireSlot < items.size()
                            && items.get(campfireSlot).isEmpty();
                } catch (Exception e) {
                    RSIntegrationMod.LOGGER.debug("[RSI-Batch-Skillet] Item peek failed", e);
                }
            }
            return false;
        }

        if (isCampfireBE(be) && CAMPFIRE_ITEMS != null) {
            try {
                @SuppressWarnings("unchecked")
                var items = (net.minecraft.core.NonNullList<ItemStack>) CAMPFIRE_ITEMS.get(be);
                return campfireSlot >= 0 && campfireSlot < items.size()
                        && items.get(campfireSlot).isEmpty();
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.warn("[RSI-Skillet] Reflection read failed", e);
                return false;
            }
        }

        return false;
    }

    @Override
    public ItemStack collectResult(ServerPlayer player) {
        BlockEntity be = myLevel.getBlockEntity(myPos);
        if (be == null) return ItemStack.EMPTY;

        if (isSkilletBE(be)) {
            try {
                Method removeItem = be.getClass().getMethod("removeItem");
                Object result = removeItem.invoke(be);
                be.setChanged();
                craftDone = true;
                if (result instanceof ItemStack s) return s;
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.error("[RSI-Batch-Skillet] Failed to remove item", e);
            }
            return ItemStack.EMPTY;
        }

        if (isCampfireBE(be)) {
            // Campfire spawns result as ItemEntity — collect it
            craftDone = true;
            campfireForceLoad(false);
            ItemStack result = recipe.getResultItem(myLevel.registryAccess()).copy();
            // Clear the slot
            try {
                @SuppressWarnings("unchecked")
                var items = (net.minecraft.core.NonNullList<ItemStack>) CAMPFIRE_ITEMS.get(be);
                items.set(campfireSlot, ItemStack.EMPTY);
                int[] prog = (int[]) CAMPFIRE_COOKING_PROGRESS.get(be);
                prog[campfireSlot] = 0;
                int[] times = (int[]) CAMPFIRE_COOKING_TIME.get(be);
                times[campfireSlot] = 0;
                be.setChanged();
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.warn("[RSI-Skillet] Reflection read failed", e);
            }
            return result;
        }

        return ItemStack.EMPTY;
    }

    @Override
    protected void clearMachineState(BlockEntity be, ServerPlayer player) {
        if (isSkilletBE(be)) {
            try {
                Method isCooking = be.getClass().getMethod("isCooking");
                if ((Boolean) isCooking.invoke(be)) {
                    ItemStack recovered = collectResult(player);
                    if (!recovered.isEmpty()) refundToRSNetwork(recovered);
                }
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.warn("[RSI-Skillet] Reflection read failed", e);
            }
        }
        clearCampfireSlot();
        campfireForceLoad(false);
        craftDone = false;
        skilletPrevTime = -1;
    }

    @Override
    public void onBatchFinished(ServerPlayer player) {
        campfireForceLoad(false);
        craftDone = false;
        skilletPrevTime = -1;
        network = null;
    }

    @Override
    public BlockPos getMachinePos() { return myPos; }

    // ── plan helpers ──

    public static void addFuelIfNeeded(@Nullable String recipeModTypeId,
                                       Map<Item, Integer> itemAvailable,
                                       Map<Item, Ingredient> itemSource,
                                       Map<Item, Integer> neededCounts,
                                       int repeatCount) {}

    public static List<String> getPlanWarnings(ServerPlayer player, Recipe<?> recipe,
                                                @Nullable ResourceLocation dim,
                                                @Nullable BlockPos pos) {
        List<String> warnings = new ArrayList<>();
        warnings.add(Component.translatable("rsi.farmersdelight.heat_warning").getString());
        return warnings;
    }

    // ── reflection ──

    private static void ensureCampfireClass() {
        if (reflectionProbed) return;
        reflectionProbed = true;
        try {
            campfireClass = Class.forName(CAMPFIRE_BE);
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-Skillet] Reflection probe failed", e);
        }
    }

    private static boolean isSkilletBE(BlockEntity be) {
        return FarmersDelightReflection.skilletBEClass != null
                && FarmersDelightReflection.skilletBEClass.isAssignableFrom(be.getClass());
    }

    private static boolean isCampfireBE(BlockEntity be) {
        ensureCampfireClass();
        if (campfireClass != null && campfireClass.isAssignableFrom(be.getClass())) return true;
        Class<?> clazz = be.getClass();
        while (clazz != null) {
            if (clazz.getName().equals(CAMPFIRE_BE)) return true;
            clazz = clazz.getSuperclass();
        }
        return false;
    }

    private static boolean isHeated(BlockEntity be) {
        try {
            Method m = be.getClass().getMethod("isHeated");
            return (boolean) m.invoke(be);
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-Skillet] Reflection read failed", e);
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private void clearCampfireSlot() {
        if (campfireSlot < 0 || campfireBE == null || CAMPFIRE_ITEMS == null) return;
        try {
            var items = (net.minecraft.core.NonNullList<ItemStack>) CAMPFIRE_ITEMS.get(campfireBE);
            ItemStack leftover = items.get(campfireSlot);
            if (!leftover.isEmpty() && !usingSharedLedger) refundToRSNetwork(leftover);
            items.set(campfireSlot, ItemStack.EMPTY);
            int[] prog = (int[]) CAMPFIRE_COOKING_PROGRESS.get(campfireBE);
            prog[campfireSlot] = 0;
            int[] times = (int[]) CAMPFIRE_COOKING_TIME.get(campfireBE);
            times[campfireSlot] = 0;
            if (campfireBE instanceof BlockEntity be) be.setChanged();
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-Skillet] Reflection read failed", e);
        }
        campfireSlot = -1;
    }

    private void refundToRSNetwork(ItemStack stack) {
        if (network != null) {
            ItemStack leftover = network.insertItem(stack.copy(), stack.getCount(), Action.PERFORM);
            if (!leftover.isEmpty() && player != null) {
                ItemHandlerHelper.giveItemToPlayer(player, leftover);
            }
        } else if (player != null) {
            ItemHandlerHelper.giveItemToPlayer(player, stack.copy());
        }
    }

    private void campfireForceLoad(boolean load) {
        if (campfireChunkForced == load) return;
        try {
            int cx = myPos.getX() >> 4;
            int cz = myPos.getZ() >> 4;
            ForgeChunkManager.forceChunk(myLevel, RSIntegrationMod.MOD_ID, myPos, cx, cz, load, true);
            campfireChunkForced = load;
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-Batch-Skillet] Chunk load failed", e);
        }
    }
}
