package com.huanghuang.rsintegration.mods.youkaishomecoming.moka;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.batch.AbstractBatchDelegate;
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
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraftforge.common.world.ForgeChunkManager;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemHandlerHelper;

import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Batch delegate for Youkais Homecoming Moka Pot. */
public final class MokaPotBatchDelegate extends AbstractBatchDelegate {

    // BasePotBlockEntity layout (INVENTORY_SIZE = 7):
    //   0-3: input ingredients  4: meal display
    //   5: CONTAINER_SLOT       6: OUTPUT_SLOT
    private static final int INPUT_SLOTS = 4;
    private static final int MEAL_DISPLAY_SLOT = 4;
    private static final int CONTAINER_SLOT = 5;
    private static final int OUTPUT_SLOT = 6;
    private static final int INVENTORY_SIZE = 7;

    private ServerPlayer player;
    private ServerLevel myLevel;
    private ResourceKey<Level> myDim;
    private BlockPos myPos;
    private Recipe<?> recipe;
    private boolean craftDone;

    private static volatile Method inventoryMethod;
    private static volatile Method isHeatedMethod;
    private static volatile Method addItemMethod;
    private static volatile Field waterPropertyField;
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
        if (found == null || YHKReflection.mokaRecipeClass == null || !YHKReflection.mokaRecipeClass.isInstance(found)) {
            player.sendSystemMessage(Component.translatable("rsi.generic.error.recipe_not_found", recipeId.toString()));
            return false;
        }
        this.recipe = found;
        this.craftDone = false;
        return true;
    }

    @Nullable
    @Override
    public List<IngredientSpec> getRequiredMaterials() {
        List<Ingredient> ingredients = recipe.getIngredients();
        if (ingredients.isEmpty()) return null;
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

    @Override
    public boolean tryStartWithMaterials(ServerPlayer player, List<ItemStack> materials,
                                         ExtractionLedger sharedLedger) {
        this.player = player;
        this.usingSharedLedger = true;
        this.craftDone = false;

        forceChunkLoad(true);
        myLevel.getChunk(myPos);

        BlockEntity be = myLevel.getBlockEntity(myPos);
        if (be == null || !isMokaBE(be)) {
            RSIntegrationMod.LOGGER.warn("[RSI-Moka] BE not found or not MokaBE at {} class={}",
                    myPos, be != null ? be.getClass().getName() : "null");
            player.sendSystemMessage(Component.translatable("rsi.generic.error.machine_not_found"));
            forceChunkLoad(false);
            return false;
        }

        probeReflection();

        if (!isHeated(be)) {
            RSIntegrationMod.LOGGER.debug("[RSI-Moka] Not heated at {}", myPos);
            player.sendSystemMessage(Component.translatable("rsi.youkaishomecoming.no_heat"));
            forceChunkLoad(false);
            return false;
        }

        if (!hasWater()) {
            var state = myLevel.getBlockState(myPos);
            setWaterProperty(state, true);
        }

        IItemHandler handler = getInventory(be);
        if (handler == null || handler.getSlots() < INVENTORY_SIZE) {
            RSIntegrationMod.LOGGER.warn("[RSI-Moka] Inventory not found or too small: handler={} slots={}",
                    handler != null ? handler.getClass().getName() : "null",
                    handler != null ? handler.getSlots() : -1);
            forceChunkLoad(false);
            return false;
        }

        // Step 1: Place container (cup, bottle, etc.) into CONTAINER_SLOT.
        // Do NOT use addItem() -- it just calls ItemHandlerHelper.insertItem
        // which routes to the first empty slot (slot 0, an ingredient slot).
        ItemStack container = getOutputContainer(recipe);
        if (!container.isEmpty() && handler.getStackInSlot(CONTAINER_SLOT).isEmpty()) {
            this.network = CraftPacketUtils.resolveNetworkForCraft(player, myDim, myPos);
            if (this.network != null) {
                ItemStack extracted = network.extractItem(container.copyWithCount(1), 1, Action.PERFORM);
                if (!extracted.isEmpty()) {
                    handler.insertItem(CONTAINER_SLOT, extracted, false);
                }
            }
        }

        // Step 2: Add ingredients via addItem() -> ItemHandlerHelper.insertItem
        // into the first available input slot (0-3).
        for (ItemStack mat : materials) {
            if (mat.isEmpty()) continue;
            ItemStack single = mat.copyWithCount(1);
            ItemStack remainder = addItem(be, single);
            if (!remainder.isEmpty()) {
                RSIntegrationMod.LOGGER.warn("[RSI-Moka] addItem rejected {} -- remainder={}",
                        single.getHoverName().getString(), remainder.getHoverName().getString());
                // Rollback input slots and container slot
                for (int i = 0; i < INPUT_SLOTS; i++) {
                    ItemStack refund = handler.extractItem(i, 64, false);
                    if (!refund.isEmpty() && !usingSharedLedger && network != null)
                        network.insertItem(refund, refund.getCount(), Action.PERFORM);
                }
                ItemStack refundC = handler.extractItem(CONTAINER_SLOT, 64, false);
                if (!refundC.isEmpty() && !usingSharedLedger && network != null)
                    network.insertItem(refundC, refundC.getCount(), Action.PERFORM);
                be.setChanged();
                forceChunkLoad(false);
                return false;
            }
        }
        be.setChanged();
        return true;
    }

    @Override
    protected boolean isMachineCraftFinished(ServerLevel level, BlockEntity be) {
        if (!isMokaBE(be)) return false;

        IItemHandler handler = getInventory(be);
        if (handler == null) return false;

        return !handler.getStackInSlot(OUTPUT_SLOT).isEmpty();
    }

    @Override
    public ItemStack collectResult(ServerPlayer player) {
        BlockEntity be = myLevel.getBlockEntity(myPos);
        if (be == null) return ItemStack.EMPTY;

        IItemHandler handler = getInventory(be);
        if (handler == null) return ItemStack.EMPTY;

        ItemStack result = handler.extractItem(OUTPUT_SLOT, 64, false);
        be.setChanged();
        craftDone = true;
        return result;
    }

    @Override
    protected void clearMachineState(BlockEntity be, ServerPlayer player) {
        clearAndRefund();
        forceChunkLoad(false);
        craftDone = false;
        network = null;
    }

    @Override
    public void onBatchFinished(ServerPlayer player) {
        clearAndRefund();
        forceChunkLoad(false);
        craftDone = false;
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
        List<String> warnings = new ArrayList<>();
        ItemStack container = getOutputContainer(recipe);
        if (!container.isEmpty()) {
            warnings.add(Component.translatable("rsi.farmersdelight.container_needed",
                    container.getHoverName().getString()).getString());
        }
        warnings.add(Component.translatable("rsi.youkaishomecoming.heat_warning").getString());
        return warnings;
    }

    // -- reflection (one-time probe) --

    private static void probeReflection() {
        if (reflectionProbed) return;
        reflectionProbed = true;
        if (YHKReflection.mokaMakerBEClass == null) {
            RSIntegrationMod.LOGGER.warn("[RSI-Moka] YHKReflection moka probe not ready");
            return;
        }
        try {
            // try getMethod first (finds public inherited), then declared walk
            try {
                inventoryMethod = YHKReflection.mokaMakerBEClass.getMethod("getInventory");
            } catch (NoSuchMethodException e) {
                inventoryMethod = findMethodInHierarchy(YHKReflection.mokaMakerBEClass, "getInventory");
            }
            if (inventoryMethod != null) inventoryMethod.setAccessible(true);

            // isHeated may be on the BE class itself or a parent class/interface.
            // Try multiple signatures: no-arg first, then (Level, BlockPos).
            isHeatedMethod = findMethodInHierarchy(YHKReflection.mokaMakerBEClass, "isHeated");
            if (isHeatedMethod == null) {
                isHeatedMethod = findMethodInHierarchy(YHKReflection.mokaMakerBEClass,
                        "isHeated", Level.class, BlockPos.class);
            }
            if (isHeatedMethod == null) {
                // Last resort: scan for any method named isHeated
                for (Method m : YHKReflection.mokaMakerBEClass.getMethods()) {
                    if (m.getName().equals("isHeated")) {
                        isHeatedMethod = m;
                        break;
                    }
                }
            }
            if (isHeatedMethod != null) {
                isHeatedMethod.setAccessible(true);
            } else {
                RSIntegrationMod.LOGGER.warn("[RSI-Moka] isHeated method not found on {} hierarchy",
                        YHKReflection.mokaMakerBEClass.getName());
            }

            // addItem() is on BasePotBlockEntity
            addItemMethod = findMethodInHierarchy(YHKReflection.mokaMakerBEClass, "addItem", ItemStack.class);
            if (addItemMethod != null) addItemMethod.setAccessible(true);

            // WATER property on MokaMakerBlock
            if (YHKReflection.mokaMakerBlockClass != null) {
                try {
                    waterPropertyField = findFieldInHierarchy(YHKReflection.mokaMakerBlockClass, "WATER");
                    if (waterPropertyField != null) waterPropertyField.setAccessible(true);
                } catch (Exception e) {
                    RSIntegrationMod.LOGGER.warn("[RSI-MokaPot] WATER property reflection failed", e);
                }
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-Moka] Reflection probe failed", e);
        }
    }

    private static boolean isMokaBE(BlockEntity be) {
        probeReflection();
        if (YHKReflection.mokaMakerBEClass != null && YHKReflection.mokaMakerBEClass.isAssignableFrom(be.getClass())) return true;
        // Fallback: class name walk
        Class<?> clazz = be.getClass();
        while (clazz != null) {
            if (YHKReflection.mokaMakerBEClass != null && YHKReflection.mokaMakerBEClass.getName().equals(clazz.getName())) return true;
            clazz = clazz.getSuperclass();
        }
        return false;
    }

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

    private static Field findFieldInHierarchy(Class<?> clazz, String name) {
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    /** Cache for BasePotBlockEntity.inventory field -- the full ItemStackHandler. */
    @Nullable
    private static volatile Field inventoryField;

    private static IItemHandler getInventory(BlockEntity be) {
        probeReflection();
        // Primary: direct field access to BasePotBlockEntity.inventory (private).
        // The public getInventory() method returns a capability-wrapped handler
        // with only 7 slots; the private ItemStackHandler field has all 9.
        if (inventoryField == null && YHKReflection.mokaMakerBEClass != null) {
            try {
                inventoryField = YHKReflection.mokaMakerBEClass.getSuperclass().getDeclaredField("inventory");
                inventoryField.setAccessible(true);
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.warn("[RSI-MokaPot] inventoryField access failed", e);
            }
        }
        if (inventoryField != null) {
            try {
                Object val = inventoryField.get(be);
                if (val instanceof IItemHandler h) return h;
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.warn("[RSI-MokaPot] inventoryField get failed", e);
            }
        }
        // Fallback: getInventory() public method (capability wrapper, ~7 slots)
        if (inventoryMethod != null) {
            try {
                return (IItemHandler) inventoryMethod.invoke(be);
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.warn("[RSI-MokaPot] inventoryMethod invoke failed", e);
            }
        }
        // Last resort: capability
        return be.getCapability(net.minecraftforge.common.capabilities.ForgeCapabilities.ITEM_HANDLER)
                .resolve().orElse(null);
    }

    private boolean isHeated(BlockEntity be) {
        if (isHeatedMethod == null) return true; // can't check, assume heated
        try {
            int paramCount = isHeatedMethod.getParameterCount();
            if (paramCount == 0) {
                return (boolean) isHeatedMethod.invoke(be);
            } else if (paramCount == 2) {
                return (boolean) isHeatedMethod.invoke(
                        java.lang.reflect.Modifier.isStatic(isHeatedMethod.getModifiers()) ? null : be,
                        myLevel, myPos);
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-Moka] isHeated invoke failed", e);
        }
        return true; // can't check, assume heated so we don't block
    }

    private static ItemStack addItem(BlockEntity be, ItemStack stack) {
        probeReflection();
        if (addItemMethod != null) {
            try {
                return (ItemStack) addItemMethod.invoke(be, stack);
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.warn("[RSI-MokaPot] addItemMethod invoke failed", e);
            }
        }
        // Fallback: insert into inventory directly
        IItemHandler handler = getInventory(be);
        if (handler != null) {
            return ItemHandlerHelper.insertItem(handler, stack, false);
        }
        return stack;
    }

    private boolean hasWater() {
        var state = myLevel.getBlockState(myPos);
        if (waterPropertyField != null) {
            try {
                Object prop = waterPropertyField.get(null);
                if (prop instanceof BooleanProperty bp && state.hasProperty(bp))
                    return state.getValue(bp);
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.warn("[RSI-MokaPot] hasWater reflection failed", e);
            }
        }
        for (var prop : state.getProperties()) {
            if (prop.getName().equalsIgnoreCase("water") && prop instanceof BooleanProperty bp)
                return state.getValue(bp);
        }
        return true; // assume water present if uncheckable
    }

    private void setWaterProperty(net.minecraft.world.level.block.state.BlockState state, boolean value) {
        if (waterPropertyField != null) {
            try {
                Object prop = waterPropertyField.get(null);
                if (prop instanceof BooleanProperty bp && state.hasProperty(bp)) {
                    myLevel.setBlock(myPos, state.setValue(bp, value), 3);
                    return;
                }
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.warn("[RSI-MokaPot] setWaterProperty reflection failed", e);
            }
        }
        for (var prop : state.getProperties()) {
            if (prop.getName().equalsIgnoreCase("water") && prop instanceof BooleanProperty bp) {
                myLevel.setBlock(myPos, state.setValue(bp, value), 3);
                return;
            }
        }
    }

    private static ItemStack getOutputContainer(Recipe<?> recipe) {
        try {
            Method m = recipe.getClass().getMethod("getOutputContainer");
            Object result = m.invoke(recipe);
            if (result instanceof ItemStack s && !s.isEmpty()) return s;
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-MokaPot] getOutputContainer method failed", e);
        }
        try {
            Field f = recipe.getClass().getDeclaredField("container");
            f.setAccessible(true);
            Object v = f.get(recipe);
            if (v instanceof ItemStack s && !s.isEmpty()) return s;
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-MokaPot] getOutputContainer field failed", e);
        }
        return ItemStack.EMPTY;
    }

    // -- cleanup --

    private void clearAndRefund() {
        myLevel.getChunk(myPos);
        BlockEntity be = myLevel.getBlockEntity(myPos);
        if (be == null || !isMokaBE(be)) return;

        IItemHandler handler = getInventory(be);
        if (handler == null || handler.getSlots() < INVENTORY_SIZE) return;

        for (int slot = 0; slot < INPUT_SLOTS; slot++) {
            ItemStack s = handler.extractItem(slot, 64, false);
            if (!s.isEmpty() && !usingSharedLedger) refund(s);
        }
        ItemStack container = handler.extractItem(CONTAINER_SLOT, 64, false);
        if (!container.isEmpty() && !usingSharedLedger) refund(container);
        ItemStack out = handler.extractItem(OUTPUT_SLOT, 64, false);
        if (!out.isEmpty() && !usingSharedLedger) refund(out);
        be.setChanged();
    }

    private void refund(ItemStack stack) {
        if (network != null) {
            ItemStack leftover = network.insertItem(stack.copy(), stack.getCount(), Action.PERFORM);
            if (!leftover.isEmpty() && player != null)
                ItemHandlerHelper.giveItemToPlayer(player, leftover);
        } else if (player != null) {
            ItemHandlerHelper.giveItemToPlayer(player, stack.copy());
        }
    }

    private void forceChunkLoad(boolean load) {
        try {
            int cx = myPos.getX() >> 4;
            int cz = myPos.getZ() >> 4;
            ForgeChunkManager.forceChunk(myLevel, RSIntegrationMod.MOD_ID, myPos, cx, cz, load, true);
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-Moka] Chunk load failed", e);
        }
    }
}
