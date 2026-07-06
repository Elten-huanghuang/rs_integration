package com.huanghuang.rsintegration.mods.youkaishomecoming.cooking;

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
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.world.ForgeChunkManager;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemHandlerHelper;

import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class CookingPotBatchDelegate extends AbstractBatchDelegate {

    // indices into BOWL_KEYS / POT_KEYS / BE_CLASS_NAMES
    protected static final int SMALL = 0;
    protected static final int SHORT = 1;
    protected static final int LARGE = 2;

    protected final int potIndex;

    public CookingPotBatchDelegate() { this(SMALL); }
    protected CookingPotBatchDelegate(int potIndex) { this.potIndex = potIndex; }

    private ServerPlayer player;
    private ServerLevel myLevel;
    private ResourceKey<Level> myDim;
    private BlockPos myPos;
    private Recipe<?> recipe;
    private boolean craftDone;

    // -- reflection cache --
    private static volatile Field itemsField;          // CookingBlockEntity.items (public final)
    private static volatile Method tryAddItemMethod;   // CookingBlockEntity.tryAddItem(ItemStack, boolean)
    private static volatile Method isHeatedMethod;     // HeatableBlockEntity.isHeated(Level, BlockPos)
    private static volatile Method getInputMethod;     // PotCookingRecipe.getInput()
    private static volatile Method getResultMethod;    // PotCookingRecipe.getResult()
    private static volatile Method inProgressMethod;   // TimedRecipeBlockEntity.inProgress()
    private static volatile Method containerMethod;    // CookingBlockEntity.container()
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
        if (found == null || !isPotCookingRecipe(found)) {
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
        probeReflection();
        List<Ingredient> ingredients = getRecipeInput(recipe);
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

        BlockEntity be = findCookingBE();
        if (be == null) {
            RSIntegrationMod.LOGGER.warn("[RSI-CookPot] CookingBE not found near {}", myPos);
            player.sendSystemMessage(Component.translatable("rsi.generic.error.machine_not_found"));
            forceChunkLoad(false);
            return false;
        }

        probeReflection();

        if (!isHeated(be)) {
            player.sendSystemMessage(Component.translatable("rsi.youkaishomecoming.no_heat"));
            forceChunkLoad(false);
            return false;
        }

        // Ensure this pot can cook this recipe -- matchContainer() checks
        // that result.getCraftingRemainingItem() matches be.container()
        if (!matchesContainer(be, recipe)) {
            RSIntegrationMod.LOGGER.debug("[RSI-CookPot] Recipe {} does not match this pot type (index {})",
                    recipe.getId(), potIndex);
            player.sendSystemMessage(Component.translatable("rsi.youkaishomecoming.wrong_pot"));
            forceChunkLoad(false);
            return false;
        }

        // setLastPlayer is needed for recipe matching in tryAddItem
        try {
            Method setPlayer = be.getClass().getMethod("setLastPlayer",
                    net.minecraft.world.entity.player.Player.class);
            setPlayer.invoke(be, player);
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-CookingPot] setLastPlayer reflection failed", e);
        }

        for (ItemStack mat : materials) {
            if (mat.isEmpty()) continue;
            ItemStack single = mat.copyWithCount(1);
            if (!tryAddItem(be, single)) {
                RSIntegrationMod.LOGGER.warn("[RSI-CookPot] tryAddItem failed for {}",
                        single.getHoverName().getString());
                forceChunkLoad(false);
                return false;
            }
        }

        be.setChanged();
        return true;
    }

    @Override
    protected boolean isMachineCraftFinished(ServerLevel level, BlockEntity be) {
        BlockPos pos = be.getBlockPos();

        // Case 1: BE is gone -- finishRecipe() replaced the pot with a BlockItem
        // result (e.g. soup block placed at the pot's own position).
        if (!isCookingBE(be)) {
            // If the block here is not air and not a cooking pot, it's the result.
            var state = level.getBlockState(pos);
            if (!state.isAir()) {
                String key = net.minecraft.core.registries.BuiltInRegistries.BLOCK
                        .getKey(state.getBlock()).toString();
                // Make sure it's not one of the bowl/pot blocks (some pots
                // auto-convert back to bowl after cooking).
                boolean isPot = key.contains("cooking_") || key.contains("iron_pot")
                        || key.contains("stockpot");
                if (!isPot) return true; // Result block replaced the pot
            }
            return false;
        }

        // Case 2: Container is empty -- finishRecipe() cleared items and
        // dropped/spawned the result.
        Container container = getItemsContainer(be);
        if (container != null) {
            for (int i = 0; i < container.getContainerSize(); i++) {
                if (!container.getItem(i).isEmpty()) return false;
            }
            return true;
        }

        // Fallback: use TimedRecipeBlockEntity progress fields
        int progress = Reflect.<Integer>getField(be, "recipeProgress").orElse(-1);
        int total = Reflect.<Integer>getField(be, "totalTime").orElse(-1);
        return total > 0 && progress >= total;
    }

    @Override
    public ItemStack collectResult(ServerPlayer player) {
        craftDone = true;

        ItemStack expected = getRecipeResult(recipe);
        BlockPos above = myPos.above();

        // Case 1: Result block replaced the pot (BlockItem result).
        // finishRecipe() calls level.setBlock(getBlockPos(), state) for blocks.
        var stateAtPot = myLevel.getBlockState(myPos);
        if (!stateAtPot.isAir()) {
            String key = net.minecraft.core.registries.BuiltInRegistries.BLOCK
                    .getKey(stateAtPot.getBlock()).toString();
            boolean isPot = key.contains("cooking_") || key.contains("iron_pot")
                    || key.contains("stockpot");
            if (!isPot) {
                var blockItem = stateAtPot.getBlock().asItem();
                if (blockItem != net.minecraft.world.item.Items.AIR) {
                    myLevel.removeBlock(myPos, false);
                    return new ItemStack(blockItem);
                }
            }
        }

        // Case 2: Non-block result dropped as entity at pos.above().
        var entities = myLevel.getEntitiesOfClass(
                net.minecraft.world.entity.item.ItemEntity.class,
                new net.minecraft.world.phys.AABB(above).inflate(0.5));
        for (var itemEntity : entities) {
            if (!itemEntity.isAlive()) continue;
            ItemStack s = itemEntity.getItem();
            if (!s.isEmpty() && (expected.isEmpty() || ItemStack.isSameItem(s, expected))) {
                ItemStack result = s.copy();
                itemEntity.discard();
                return result;
            }
        }

        // Case 3: Block placed above the pot (edge case).
        var stateAbove = myLevel.getBlockState(above);
        if (!stateAbove.isAir()) {
            var blockItem = stateAbove.getBlock().asItem();
            if (blockItem != net.minecraft.world.item.Items.AIR) {
                myLevel.removeBlock(above, false);
                return new ItemStack(blockItem);
            }
        }

        // Last resort: return the expected recipe result directly
        if (!expected.isEmpty()) return expected.copy();
        return ItemStack.EMPTY;
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
        // Don't clearAndRefund -- the pot's finishRecipe() already consumed
        // ingredients, and collectResult() already took the result.  The
        // bowl/pot in the container slot must stay in-world for reuse.
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
        warnings.add(Component.translatable("rsi.youkaishomecoming.cookpot_heat_warning").getString());
        return warnings;
    }

    // -- BE discovery --

    // YHK bowl blocks are IronBowlBlock (no BE).  They must be converted to the
    // corresponding cooking block before a CookingBlockEntity exists.
    // Bowl -> Pot mappings (from IronBowlBlock.use):
    //   small_iron_pot     -> cooking_small_iron_pot  (SmallCookingPotBlockEntity)
    //   short_iron_pot     -> cooking_short_iron_pot  (MidCookingPotBlockEntity)
    //   stockpot           -> cooking_stockpot        (LargeCookingPotBlockEntity)
    private static final String[] BOWL_KEYS = {
        "youkaishomecoming:small_iron_pot",
        "youkaishomecoming:short_iron_pot",
        "youkaishomecoming:stockpot" };
    private static final String[] POT_KEYS = {
        "youkaishomecoming:cooking_small_iron_pot",
        "youkaishomecoming:cooking_short_iron_pot",
        "youkaishomecoming:cooking_stockpot" };

    @Nullable
    private BlockEntity findCookingBE() {
        // Try direct chunk BE lookup first -- more reliable when chunk was just loaded.
        var chunk = myLevel.getChunk(myPos);
        BlockEntity be = chunk.getBlockEntity(myPos);
        if (be != null && isCookingBE(be)) return be;

        // Fallback to level-level lookup.
        be = myLevel.getBlockEntity(myPos);
        if (be != null && isCookingBE(be)) return be;

        // If we're looking at an IronBowlBlock (empty bowl, no BE), replace it
        // with the cooking variant to create the CookingBlockEntity.
        // Only convert the bowl matching this delegate's pot type.
        var state = myLevel.getBlockState(myPos);
        var block = state.getBlock();
        if (!(block instanceof net.minecraft.world.level.block.EntityBlock)) {
            String blockKey = net.minecraft.core.registries.BuiltInRegistries.BLOCK
                    .getKey(block).toString();
            if (BOWL_KEYS[potIndex].equals(blockKey)) {
                var potBlock = net.minecraft.core.registries.BuiltInRegistries.BLOCK
                        .get(new ResourceLocation(POT_KEYS[potIndex]));
                if (potBlock != null) {
                    var cookingState = potBlock.defaultBlockState();
                    if (state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING)) {
                        cookingState = cookingState.setValue(
                                net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING,
                                state.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING));
                    }
                    myLevel.setBlock(myPos, cookingState, 3);
                    RSIntegrationMod.LOGGER.info("[RSI-CookPot] Activated bowl -> {} at {}",
                            POT_KEYS[potIndex], myPos);
                    be = myLevel.getBlockEntity(myPos);
                    if (be != null && isCookingBE(be)) return be;
                }
            }
        }

        RSIntegrationMod.LOGGER.warn("[RSI-CookPot] CookingBE not found at {}. "
                        + "Block={} class={} isEntityBlock={} chunkLoaded={}",
                myPos,
                block.getDescriptionId(),
                block.getClass().getName(),
                block instanceof net.minecraft.world.level.block.EntityBlock,
                myLevel.isLoaded(myPos));
        return null;
    }

    // -- reflection (one-time probe) --

    private static void probeReflection() {
        if (reflectionProbed) return;
        reflectionProbed = true;
        if (YHKReflection.cookingBEClass == null || YHKReflection.potCookingRecipeClass == null) {
            RSIntegrationMod.LOGGER.warn("[RSI-CookPot] YHKReflection probe not ready");
            return;
        }
        try {
            // items field -- public final on CookingBlockEntity
            itemsField = YHKReflection.cookingBEClass.getField("items");

            // tryAddItem(ItemStack, boolean) -- public on CookingBlockEntity
            tryAddItemMethod = YHKReflection.cookingBEClass.getMethod("tryAddItem", ItemStack.class, boolean.class);

            // isHeated(Level, BlockPos) -- from FD's HeatableBlockEntity interface
            isHeatedMethod = YHKReflection.cookingBEClass.getMethod("isHeated", Level.class, BlockPos.class);

            // container() -- public abstract on CookingBlockEntity
            containerMethod = YHKReflection.cookingBEClass.getMethod("container");

            // inProgress() -- public on TimedRecipeBlockEntity
            inProgressMethod = findMethodInHierarchy(YHKReflection.cookingBEClass.getSuperclass(), "inProgress");

            // PotCookingRecipe.getInput() / getResult() -- public
            getInputMethod = YHKReflection.potCookingRecipeClass.getMethod("getInput");
            getResultMethod = YHKReflection.potCookingRecipeClass.getMethod("getResult");
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-CookPot] Reflection probe failed", e);
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

    private static boolean isCookingBE(BlockEntity be) {
        probeReflection();
        if (YHKReflection.cookingBEClass != null && YHKReflection.cookingBEClass.isAssignableFrom(be.getClass())) return true;
        Class<?> clazz = be.getClass();
        while (clazz != null) {
            if (YHKReflection.cookingBEClass != null && YHKReflection.cookingBEClass.getName().equals(clazz.getName())) return true;
            clazz = clazz.getSuperclass();
        }
        return false;
    }

    private static boolean isPotCookingRecipe(Recipe<?> recipe) {
        probeReflection();
        if (YHKReflection.potCookingRecipeClass != null && YHKReflection.potCookingRecipeClass.isAssignableFrom(recipe.getClass())) return true;
        Class<?> clazz = recipe.getClass();
        while (clazz != null) {
            if (YHKReflection.potCookingRecipeClass != null && YHKReflection.potCookingRecipeClass.getName().equals(clazz.getName())) return true;
            clazz = clazz.getSuperclass();
        }
        return false;
    }

    // -- operation helpers --

    @Nullable
    private static Container getItemsContainer(BlockEntity be) {
        probeReflection();
        if (itemsField != null) {
            try {
                Object val = itemsField.get(be);
                if (val instanceof Container c) return c;
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.warn("[RSI-CookingPot] Items container reflection failed", e);
            }
        }
        return null;
    }

    private static boolean tryAddItem(BlockEntity be, ItemStack stack) {
        if (tryAddItemMethod != null) {
            try {
                return (boolean) tryAddItemMethod.invoke(be, stack, false);
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.warn("[RSI-CookingPot] tryAddItem reflection failed", e);
            }
        }
        return false;
    }

    private static boolean isHeated(BlockEntity be) {
        if (isHeatedMethod == null) return true;
        try {
            return (boolean) isHeatedMethod.invoke(be, be.getLevel(), be.getBlockPos());
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-CookPot] isHeated invoke failed", e);
        }
        return true;
    }

    /** Check that the recipe result's {@code getCraftingRemainingItem()} matches
     *  the BE's {@code container()} item.  This ensures small-pot recipes don't
     *  get routed to stockpots and vice versa. */
    private static boolean matchesContainer(BlockEntity be, Recipe<?> recipe) {
        try {
            if (containerMethod == null) return true;
            Item container = (Item) containerMethod.invoke(be);
            ItemStack result = getRecipeResult(recipe);
            if (!result.isEmpty() && result.hasCraftingRemainingItem()) {
                return result.getCraftingRemainingItem().is(container);
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-CookPot] matchContainer failed", e);
        }
        return true; // can't determine -- allow
    }

    @SuppressWarnings("unchecked")
    private static List<Ingredient> getRecipeInput(Recipe<?> recipe) {
        if (getInputMethod != null) {
            try {
                return (List<Ingredient>) getInputMethod.invoke(recipe);
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.warn("[RSI-CookingPot] getRecipeInput reflection failed", e);
            }
        }
        return List.of();
    }

    private static ItemStack getRecipeResult(Recipe<?> recipe) {
        if (getResultMethod != null) {
            try {
                return (ItemStack) getResultMethod.invoke(recipe);
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.warn("[RSI-CookingPot] getRecipeResult reflection failed", e);
            }
        }
        return ItemStack.EMPTY;
    }

    // -- cleanup --

    private void clearAndRefund() {
        BlockEntity be = myLevel.getBlockEntity(myPos);
        if (be == null || !isCookingBE(be)) return;

        // Try items field first
        Container container = getItemsContainer(be);
        if (container != null) {
            for (int i = 0; i < container.getContainerSize(); i++) {
                ItemStack s = container.getItem(i);
                if (!s.isEmpty()) {
                    container.setItem(i, ItemStack.EMPTY);
                    if (!usingSharedLedger) refund(s);
                }
            }
            be.setChanged();
            return;
        }

        // Fallback: capability
        IItemHandler handler = be.getCapability(
                net.minecraftforge.common.capabilities.ForgeCapabilities.ITEM_HANDLER)
                .resolve().orElse(null);
        if (handler != null) {
            for (int i = 0; i < handler.getSlots(); i++) {
                ItemStack s = handler.extractItem(i, 64, false);
                if (!s.isEmpty() && !usingSharedLedger) refund(s);
            }
            be.setChanged();
        }
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
            RSIntegrationMod.LOGGER.debug("[RSI-CookPot] Chunk load failed", e);
        }
    }
}
