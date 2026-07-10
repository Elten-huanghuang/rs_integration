package com.huanghuang.rsintegration.mods.youkaishomecoming.steamer;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.batch.AbstractBatchDelegate;
import com.huanghuang.rsintegration.reflection.probes.YHKReflection;
import com.refinedmods.refinedstorage.api.network.INetwork;
import com.refinedmods.refinedstorage.api.util.Action;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.world.ForgeChunkManager;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemHandlerHelper;

import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Batch delegate for Youkais Homecoming Steamer. */
public final class SteamerBatchDelegate extends AbstractBatchDelegate {

    private ServerPlayer player;
    private ServerLevel myLevel;
    private ResourceKey<Level> myDim;
    private BlockPos myPos;
    private BlockPos potBasePos;
    private AbstractCookingRecipe recipe;
    private boolean craftDone;

    private static final ResourceLocation KEY_POT =
            new ResourceLocation("youkaishomecoming", "steamer_pot");
    private static final ResourceLocation KEY_RACK =
            new ResourceLocation("youkaishomecoming", "steamer_rack");
    private static final ResourceLocation KEY_LID =
            new ResourceLocation("youkaishomecoming", "steamer_lid");

    // Reflection cache
    private static volatile Field racksField;
    private static volatile Method tryAddItemMethod;
    private static volatile Method tryTakeItemMethod;
    private static volatile Method mayExtractMethod;
    private static volatile Field stackField;
    private static volatile Method rackSetStackMethod;
    private static volatile Method setChangedMethod;
    private static volatile Method isHeatedMethod;
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
        if (!(found instanceof AbstractCookingRecipe)) {
            player.sendSystemMessage(Component.translatable("rsi.generic.error.recipe_not_found", recipeId.toString()));
            return false;
        }
        this.recipe = (AbstractCookingRecipe) found;
        this.craftDone = false;
        return true;
    }

    @Nullable
    @Override
    public List<IngredientSpec> getRequiredMaterials() {
        Ingredient ing = recipe.getIngredients().get(0);
        if (ing.isEmpty()) return null;
        return List.of(new IngredientSpec(ing, 1));
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

        if (materials.isEmpty()) return false;
        ItemStack input = materials.get(0).copyWithCount(1);

        forceChunkLoad(true);
        myLevel.getChunk(myPos);

        // Find pot base -- the BE lives in the bottom pot block.
        // myPos might be the rack or lid position if the user bound those.
        BlockEntity be = findPotBase();
        if (be == null) {
            RSIntegrationMod.LOGGER.warn("[RSI-Steamer] Pot base not found at myPos={}", myPos);
            player.sendSystemMessage(Component.translatable("rsi.generic.error.machine_not_found"));
            forceChunkLoad(false);
            return false;
        }

        probeReflection();

        if (!isHeated(be)) {
            RSIntegrationMod.LOGGER.debug("[RSI-Steamer] Not heated at {}", potPos());
            player.sendSystemMessage(Component.translatable("rsi.youkaishomecoming.no_heat"));
            forceChunkLoad(false);
            return false;
        }

        if (!hasWater()) {
            setWaterProperty(myLevel.getBlockState(potPos()), true);
        }

        // Verify racks exist and are valid
        List<?> racks = getRacks(be);
        if (racks == null || racks.isEmpty()) {
            RSIntegrationMod.LOGGER.warn("[RSI-Steamer] No racks at potBase={}", potBasePos);
            player.sendSystemMessage(Component.translatable("rsi.youkaishomecoming.rack_invalid"));
            forceChunkLoad(false);
            return false;
        }

        // Verify lid is present
        if (!hasLid(be)) {
            RSIntegrationMod.LOGGER.warn("[RSI-Steamer] Lid not found above potBase={}", potBasePos);
            player.sendSystemMessage(Component.translatable("rsi.youkaishomecoming.lid_missing"));
            forceChunkLoad(false);
            return false;
        }

        // Try to add item to each rack until one accepts it
        boolean inserted = false;
        for (Object rack : racks) {
            if (tryAddItemToRack(rack, be, input)) {
                inserted = true;
                break;
            }
        }

        if (!inserted) {
            RSIntegrationMod.LOGGER.debug("[RSI-Steamer] All racks full at {}", potBasePos);
            player.sendSystemMessage(Component.translatable("rsi.youkaishomecoming.steamer_full"));
            forceChunkLoad(false);
            return false;
        }

        be.setChanged();
        return true;
    }

    @Override
    protected boolean isMachineCraftFinished(ServerLevel level, BlockEntity be) {
        if (!isSteamerBE(be)) return false;

        List<?> racks = getRacks(be);
        if (racks == null) return false;

        for (Object rack : racks) {
            Object[] items = getRackItems(rack);
            if (items == null) continue;
            for (Object itemData : items) {
                if (itemData == null) continue;
                if (mayExtract(itemData)) return true;
            }
        }
        return false;
    }

    @Override
    public ItemStack collectResult(ServerPlayer player) {
        BlockEntity be = myLevel.getBlockEntity(potBasePos != null ? potBasePos : myPos);
        if (be == null) return ItemStack.EMPTY;

        List<?> racks = getRacks(be);
        if (racks == null) return ItemStack.EMPTY;

        // Only collect items that are ready -- mayExtract() ensures the
        // item has finished cooking and is not a raw ingredient.
        for (Object rack : racks) {
            Object[] items = getRackItems(rack);
            if (items == null) continue;
            for (Object itemData : items) {
                if (itemData == null) continue;
                if (!mayExtract(itemData)) continue;
                ItemStack s = getStack(itemData);
                if (!s.isEmpty()) {
                    ItemStack result = s.copy();
                    setStack(itemData, be, ItemStack.EMPTY);
                    markItemDirty(itemData);
                    be.setChanged();
                    craftDone = true;
                    return result;
                }
            }
        }

        craftDone = true;
        return recipe.getResultItem(myLevel.registryAccess()).copy();
    }

    @Override
    protected void clearMachineState(BlockEntity be, ServerPlayer player) {
        clearAndRefund();
        forceChunkLoad(false);
        craftDone = false;
        network = null;
        potBasePos = null;
    }

    @Override
    public void onBatchFinished(ServerPlayer player) {
        clearAndRefund();
        forceChunkLoad(false);
        craftDone = false;
        network = null;
        potBasePos = null;
    }

    @Override
    public BlockPos getMachinePos() { return potBasePos != null ? potBasePos : myPos; }

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
        warnings.add(Component.translatable("rsi.youkaishomecoming.heat_warning").getString());
        warnings.add(Component.translatable("rsi.youkaishomecoming.steamer_rack_warning").getString());
        warnings.add(Component.translatable("rsi.youkaishomecoming.lid_warning").getString());
        return warnings;
    }

    // -- multi-block structure --

    private BlockEntity findPotBase() {
        // Walk downward from myPos to find the steamer pot block.
        // The SteamerBlockEntity lives in the pot (bottom) block.
        BlockPos pos = myPos;
        for (int i = 0; i < 5; i++) {
            BlockState state = myLevel.getBlockState(pos);
            ResourceLocation key = BuiltInRegistries.BLOCK.getKey(state.getBlock());
            if (KEY_POT.equals(key)) {
                BlockEntity be = myLevel.getBlockEntity(pos);
                if (be != null && isSteamerBE(be)) {
                    this.potBasePos = pos.immutable();
                    return be;
                }
            }
            pos = pos.below();
        }
        return null;
    }

    private boolean hasLid(BlockEntity be) {
        List<?> racks = getRacks(be);
        if (racks == null) return false;
        // Scan upward from the pot base to find the lid -- don't assume an exact
        // offset because the number of racks may not match racks.size().
        BlockPos checkPos = potBasePos.above();
        for (int i = 0; i < 5; i++) {
            BlockState state = myLevel.getBlockState(checkPos);
            ResourceLocation key = BuiltInRegistries.BLOCK.getKey(state.getBlock());
            if (KEY_LID.equals(key)) return true;
            checkPos = checkPos.above();
        }
        return false;
    }

    // -- reflection --

    private static void probeReflection() {
        if (reflectionProbed) return;
        reflectionProbed = true;
        if (YHKReflection.steamerBEClass == null) {
            RSIntegrationMod.LOGGER.warn("[RSI-Steamer] YHKReflection steamer probe not ready");
            return;
        }
        try {
            racksField = YHKReflection.steamerBEClass.getField("racks");

            // isHeated(Level, BlockPos) is from HeatableBlockEntity interface (default method)
            isHeatedMethod = YHKReflection.steamerBEClass.getMethod("isHeated", Level.class, BlockPos.class);
            if (isHeatedMethod != null) isHeatedMethod.setAccessible(true);

            if (YHKReflection.rackDataClass != null) {
                tryAddItemMethod = YHKReflection.rackDataClass.getMethod("tryAddItem", YHKReflection.steamerBEClass, Level.class, ItemStack.class);
                tryAddItemMethod.setAccessible(true);
                tryTakeItemMethod = findMethodInHierarchy(YHKReflection.rackDataClass, "tryTakeItem",
                        YHKReflection.steamerBEClass, Level.class, net.minecraft.world.entity.player.Player.class,
                        InteractionHand.class);
                if (tryTakeItemMethod != null) tryTakeItemMethod.setAccessible(true);
            }

            if (YHKReflection.rackItemDataClass != null) {
                mayExtractMethod = YHKReflection.rackItemDataClass.getMethod("mayExtract");
                stackField = YHKReflection.rackItemDataClass.getField("stack");
                rackSetStackMethod = YHKReflection.rackItemDataClass.getMethod("setStack", YHKReflection.steamerBEClass, ItemStack.class);
                rackSetStackMethod.setAccessible(true);
                setChangedMethod = YHKReflection.rackItemDataClass.getMethod("setChanged");
            }

            if (YHKReflection.steamerPotBlockClass != null) {
                try {
                    // Try WATER (old YHK SteamerPotBlock) then WATERLOGGED (new YHK BasePotBlock)
                    try {
                        waterPropertyField = YHKReflection.steamerPotBlockClass.getDeclaredField("WATER");
                    } catch (NoSuchFieldException e) {
                        waterPropertyField = YHKReflection.steamerPotBlockClass.getDeclaredField("WATERLOGGED");
                    }
                    waterPropertyField.setAccessible(true);
                } catch (Exception e) {
                    RSIntegrationMod.LOGGER.warn("[RSI-Steamer] Water property probe failed", e);
                }
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-Steamer] Reflection probe failed", e);
        }
    }

    private static boolean isSteamerBE(BlockEntity be) {
        probeReflection();
        if (YHKReflection.steamerBEClass != null && YHKReflection.steamerBEClass.isAssignableFrom(be.getClass())) return true;
        Class<?> clazz = be.getClass();
        while (clazz != null) {
            if (YHKReflection.steamerBEClass != null && YHKReflection.steamerBEClass.getName().equals(clazz.getName())) return true;
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

    // -- rack operations --

    private static List<?> getRacks(BlockEntity be) {
        if (racksField != null) {
            try {
                return (List<?>) racksField.get(be);
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.warn("[RSI-Steamer] Failed to read racks field", e);
            }
        }
        return null;
    }

    private boolean tryAddItemToRack(Object rack, BlockEntity be, ItemStack stack) {
        if (tryAddItemMethod != null) {
            try {
                return (boolean) tryAddItemMethod.invoke(rack, be, myLevel, stack);
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.error("[RSI-Steamer] Failed to add item to rack", e);
            }
        }
        return false;
    }

    private static Object[] getRackItems(Object rack) {
        if (rack == null) return null;
        try {
            Field listField = rack.getClass().getField("list");
            return (Object[]) listField.get(rack);
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-Steamer] Failed to read rack items list", e);
        }
        return null;
    }

    private static boolean mayExtract(Object itemData) {
        if (mayExtractMethod != null) {
            try {
                return (boolean) mayExtractMethod.invoke(itemData);
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.warn("[RSI-Steamer] Failed to check mayExtract", e);
            }
        }
        return false;
    }

    private static ItemStack getStack(Object itemData) {
        if (stackField != null) {
            try {
                return (ItemStack) stackField.get(itemData);
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.error("[RSI-Steamer] Failed to read rack stack -- item may be lost", e);
            }
        }
        return ItemStack.EMPTY;
    }

    private static void setStack(Object itemData, BlockEntity be, ItemStack stack) {
        if (rackSetStackMethod != null) {
            try {
                rackSetStackMethod.invoke(itemData, be, stack);
                return;
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.error("[RSI-Steamer] Failed to set rack stack (method)", e);
            }
        }
        // Fallback: direct field set
        if (stackField != null) {
            try {
                stackField.set(itemData, stack);
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.error("[RSI-Steamer] Failed to set rack stack (field)", e);
            }
        }
    }

    private static void markItemDirty(Object itemData) {
        if (setChangedMethod != null) {
            try {
                setChangedMethod.invoke(itemData);
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.warn("[RSI-Steamer] Failed to mark item dirty", e);
            }
        }
    }

    // -- heat / water --

    private BlockPos potPos() {
        return potBasePos != null ? potBasePos : myPos;
    }

    private boolean isHeated(BlockEntity be) {
        if (isHeatedMethod == null) return true; // can't check, assume heated
        try {
            return (boolean) isHeatedMethod.invoke(be, myLevel, potPos());
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-Steamer] isHeated invoke failed", e);
        }
        return true; // can't check, assume heated so we don't block
    }

    private boolean hasWater() {
        var state = myLevel.getBlockState(potPos());
        if (waterPropertyField != null) {
            try {
                Object prop = waterPropertyField.get(null);
                if (prop instanceof BooleanProperty bp && state.hasProperty(bp))
                    return state.getValue(bp);
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.warn("[RSI-Steamer] Failed to read water property", e);
            }
        }
        for (var prop : state.getProperties()) {
            String name = prop.getName();
            if ((name.equalsIgnoreCase("water") || name.equalsIgnoreCase("waterlogged"))
                    && prop instanceof BooleanProperty bp)
                return state.getValue(bp);
        }
        return true;
    }

    private void setWaterProperty(net.minecraft.world.level.block.state.BlockState state, boolean value) {
        BlockPos p = potPos();
        if (waterPropertyField != null) {
            try {
                Object prop = waterPropertyField.get(null);
                if (prop instanceof BooleanProperty bp && state.hasProperty(bp)) {
                    myLevel.setBlock(p, state.setValue(bp, value), 3);
                    return;
                }
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.warn("[RSI-Steamer] Failed to set water property", e);
            }
        }
        for (var prop : state.getProperties()) {
            String name = prop.getName();
            if ((name.equalsIgnoreCase("water") || name.equalsIgnoreCase("waterlogged"))
                    && prop instanceof BooleanProperty bp) {
                myLevel.setBlock(p, state.setValue(bp, value), 3);
                return;
            }
        }
    }

    // -- cleanup --

    private void clearAndRefund() {
        BlockPos pos = potBasePos != null ? potBasePos : myPos;
        myLevel.getChunk(pos);
        BlockEntity be = myLevel.getBlockEntity(pos);
        if (be == null || !isSteamerBE(be)) return;

        List<?> racks = getRacks(be);
        if (racks != null) {
            for (Object rack : racks) {
                Object[] items = getRackItems(rack);
                if (items == null) continue;
                for (Object itemData : items) {
                    if (itemData == null) continue;
                    ItemStack s = getStack(itemData);
                    if (!s.isEmpty()) {
                        setStack(itemData, be, ItemStack.EMPTY);
                        markItemDirty(itemData);
                        if (!usingSharedLedger) refund(s);
                    }
                }
            }
        } else {
            // IItemHandler capability fallback -- recovers items when reflection
            // path fails (e.g. after a mod update changes internal field names)
            be.getCapability(ForgeCapabilities.ITEM_HANDLER).ifPresent(handler -> {
                for (int slot = 0; slot < handler.getSlots(); slot++) {
                    ItemStack s = handler.extractItem(slot, handler.getSlotLimit(slot), true);
                    if (!s.isEmpty()) {
                        handler.extractItem(slot, handler.getSlotLimit(slot), false);
                        if (!usingSharedLedger) refund(s);
                    }
                }
            });
        }
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
            RSIntegrationMod.LOGGER.debug("[RSI-Steamer] Chunk load failed", e);
        }
    }
}
