package com.huanghuang.rsintegration.mods.youkaishomecoming.ferment;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.batch.AbstractBatchDelegate;
import com.huanghuang.rsintegration.reflection.probes.YHKReflection;
import com.huanghuang.rsintegration.util.Reflect;
import com.refinedmods.refinedstorage.api.network.INetwork;
import com.refinedmods.refinedstorage.api.util.Action;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
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

/** Batch delegate for Youkais Homecoming Fermentation Tank. */
public final class FermentationTankBatchDelegate extends AbstractBatchDelegate {

    private ServerPlayer player;
    private ServerLevel myLevel;
    private ResourceKey<Level> myDim;
    private BlockPos myPos;
    private Recipe<?> recipe;
    private boolean craftDone;

    private int insertedCount;
    private ItemStack insertedSample = ItemStack.EMPTY;

    private static volatile Field itemsField;
    private static volatile Method notifyTileMethod;
    private static volatile Field openPropertyField;
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
        if (found == null || YHKReflection.simpleFermentationRecipeClass == null || !YHKReflection.simpleFermentationRecipeClass.isInstance(found)) {
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
        List<Ingredient> ingredients = getRecipeIngredients();
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
        if (be == null || !isFermentationBE(be)) {
            RSIntegrationMod.LOGGER.warn("[RSI-Ferment] BE not found or not FermentBE at {} class={}",
                    myPos, be != null ? be.getClass().getName() : "null");
            player.sendSystemMessage(Component.translatable("rsi.generic.error.machine_not_found"));
            forceChunkLoad(false);
            return false;
        }

        probeReflection();

        if (isLidOpen()) {
            setLidOpen(false);
        }

        SimpleContainer itemHandler = getItemHandler(be);
        if (itemHandler == null) {
            player.sendSystemMessage(Component.translatable("rsi.generic.error.machine_not_found"));
            forceChunkLoad(false);
            return false;
        }

        // Save snapshot for rollback
        ItemStack[] snapshot = new ItemStack[itemHandler.getContainerSize()];
        for (int i = 0; i < snapshot.length; i++) {
            snapshot[i] = itemHandler.getItem(i).copy();
        }

        insertedCount = 0;
        for (ItemStack mat : materials) {
            if (mat.isEmpty()) continue;
            ItemStack single = mat.copyWithCount(1);
            // Try inserting into each slot
            boolean inserted = false;
            for (int i = 0; i < itemHandler.getContainerSize(); i++) {
                if (itemHandler.getItem(i).isEmpty()) {
                    itemHandler.setItem(i, single);
                    inserted = true;
                    break;
                }
            }
            if (!inserted) {
                // Restore snapshot
                for (int i = 0; i < snapshot.length; i++) {
                    itemHandler.setItem(i, snapshot[i]);
                }
                notifyTile(be);
                return false;
            }
            insertedCount++;
            if (insertedSample.isEmpty()) insertedSample = single.copy();
        }
        notifyTile(be);
        return true;
    }

    @Override
    protected boolean isMachineCraftFinished(ServerLevel level, BlockEntity be) {
        if (!isFermentationBE(be)) return false;

        int progress = Reflect.<Integer>getField(be, "recipeProgress").orElse(-1);
        int total = Reflect.<Integer>getField(be, "totalTime").orElse(-1);
        if (total > 0 && progress >= total) return true;

        // Fallback: check if result item is present
        SimpleContainer handler = getItemHandler(be);
        if (handler == null) return false;

        ItemStack expected = getExpectedResult();
        if (!expected.isEmpty()) {
            for (int i = 0; i < handler.getContainerSize(); i++) {
                ItemStack s = handler.getItem(i);
                if (!s.isEmpty() && ItemStack.isSameItem(s, expected)) return true;
            }
        }
        return false;
    }

    @Override
    public ItemStack collectResult(ServerPlayer player) {
        BlockEntity be = myLevel.getBlockEntity(myPos);
        if (be == null) return ItemStack.EMPTY;

        SimpleContainer handler = getItemHandler(be);
        if (handler == null) return ItemStack.EMPTY;

        ItemStack expected = getExpectedResult();
        ItemStack collected = ItemStack.EMPTY;

        for (int i = 0; i < handler.getContainerSize(); i++) {
            ItemStack s = handler.getItem(i);
            if (!s.isEmpty()) {
                ItemStack extracted = handler.removeItem(i, s.getCount());
                if (!extracted.isEmpty()) {
                    if (collected.isEmpty()) {
                        collected = extracted;
                    } else if (ItemStack.isSameItemSameTags(collected, extracted)) {
                        collected.grow(extracted.getCount());
                    }
                }
            }
        }

        notifyTile(be);
        craftDone = true;
        if (collected.isEmpty() && !expected.isEmpty()) {
            return expected.copy();
        }
        return collected;
    }

    @Override
    protected void clearMachineState(BlockEntity be, ServerPlayer player) {
        clearAndRefund();
        forceChunkLoad(false);
        craftDone = false;
        network = null;
        insertedCount = 0;
        insertedSample = ItemStack.EMPTY;
    }

    @Override
    public void onBatchFinished(ServerPlayer player) {
        clearAndRefund();
        forceChunkLoad(false);
        craftDone = false;
        network = null;
        insertedCount = 0;
        insertedSample = ItemStack.EMPTY;
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
        warnings.add(Component.translatable("rsi.youkaishomecoming.ferment_lid_warning").getString());
        return warnings;
    }

    // -- recipe accessors via public fields --

    @SuppressWarnings("unchecked")
    private List<Ingredient> getRecipeIngredients() {
        try {
            Field f = recipe.getClass().getField("ingredients");
            Object val = f.get(recipe);
            if (val instanceof List) return (List<Ingredient>) val;
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-Fermentation] Recipe ingredients reflection failed", e);
        }
        return List.of();
    }

    @SuppressWarnings("unchecked")
    private ItemStack getExpectedResult() {
        try {
            Field f = recipe.getClass().getField("results");
            Object val = f.get(recipe);
            if (val instanceof List<?> list && !list.isEmpty()) {
                if (list.get(0) instanceof ItemStack s && !s.isEmpty())
                    return s.copy();
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-Fermentation] Expected result reflection failed", e);
        }
        return ItemStack.EMPTY;
    }

    // -- reflection --

    private static void probeReflection() {
        if (reflectionProbed) return;
        reflectionProbed = true;
        if (YHKReflection.fermentationTankBEClass == null) {
            RSIntegrationMod.LOGGER.warn("[RSI-Ferment] YHKReflection ferment probe not ready");
            return;
        }
        try {
            // FermentationTankBlockEntity.items is public final
            itemsField = YHKReflection.fermentationTankBEClass.getField("items");

            // FluidItemTile.notifyTile()
            if (YHKReflection.fluidItemTileClass != null) {
                notifyTileMethod = YHKReflection.fluidItemTileClass.getMethod("notifyTile");
                notifyTileMethod.setAccessible(true);
            }

            // FermentationTankBlock.OPEN -- public static BooleanProperty
            if (YHKReflection.fermentationTankBlockClass != null) {
                openPropertyField = YHKReflection.fermentationTankBlockClass.getDeclaredField("OPEN");
                openPropertyField.setAccessible(true);
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-Ferment] Reflection probe failed", e);
        }
    }

    private static boolean isFermentationBE(BlockEntity be) {
        probeReflection();
        if (YHKReflection.fermentationTankBEClass != null && YHKReflection.fermentationTankBEClass.isAssignableFrom(be.getClass())) return true;
        Class<?> clazz = be.getClass();
        while (clazz != null) {
            if (YHKReflection.fermentationTankBEClass != null && YHKReflection.fermentationTankBEClass.getName().equals(clazz.getName())) return true;
            clazz = clazz.getSuperclass();
        }
        return false;
    }

    private static SimpleContainer getItemHandler(BlockEntity be) {
        probeReflection();
        if (itemsField != null) {
            try {
                Object val = itemsField.get(be);
                if (val instanceof SimpleContainer sc) return sc;
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.warn("[RSI-Fermentation] getItemHandler reflection failed", e);
            }
        }
        // Fallback: getCapability
        IItemHandler cap = be.getCapability(
                net.minecraftforge.common.capabilities.ForgeCapabilities.ITEM_HANDLER)
                .resolve().orElse(null);
        if (cap instanceof SimpleContainer sc) return sc;
        return null;
    }

    private static void notifyTile(BlockEntity be) {
        if (notifyTileMethod != null) {
            try {
                notifyTileMethod.invoke(be);
                return;
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.warn("[RSI-Fermentation] notifyTile reflection failed", e);
            }
        }
        be.setChanged();
    }

    private boolean isLidOpen() {
        var state = myLevel.getBlockState(myPos);
        if (openPropertyField != null) {
            try {
                Object prop = openPropertyField.get(null);
                if (prop instanceof BooleanProperty bp && state.hasProperty(bp))
                    return state.getValue(bp);
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.warn("[RSI-Fermentation] isLidOpen reflection failed", e);
            }
        }
        for (var prop : state.getProperties()) {
            if (prop.getName().equalsIgnoreCase("open") && prop instanceof BooleanProperty bp)
                return state.getValue(bp);
        }
        return false;
    }

    private void setLidOpen(boolean open) {
        var state = myLevel.getBlockState(myPos);
        if (openPropertyField != null) {
            try {
                Object prop = openPropertyField.get(null);
                if (prop instanceof BooleanProperty bp && state.hasProperty(bp)) {
                    myLevel.setBlock(myPos, state.setValue(bp, open), 3);
                    return;
                }
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.warn("[RSI-Fermentation] setLidOpen reflection failed", e);
            }
        }
        for (var prop : state.getProperties()) {
            if (prop.getName().equalsIgnoreCase("open") && prop instanceof BooleanProperty bp) {
                myLevel.setBlock(myPos, state.setValue(bp, open), 3);
                return;
            }
        }
    }

    // -- cleanup --

    private void clearAndRefund() {
        myLevel.getChunk(myPos);
        BlockEntity be = myLevel.getBlockEntity(myPos);
        if (be == null || !isFermentationBE(be)) return;

        SimpleContainer handler = getItemHandler(be);
        if (handler == null) return;

        for (int i = 0; i < handler.getContainerSize(); i++) {
            ItemStack s = handler.removeItem(i, 64);
            if (!s.isEmpty() && !usingSharedLedger) refund(s);
        }
        notifyTile(be);
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
            RSIntegrationMod.LOGGER.debug("[RSI-Ferment] Chunk load failed", e);
        }
    }
}
