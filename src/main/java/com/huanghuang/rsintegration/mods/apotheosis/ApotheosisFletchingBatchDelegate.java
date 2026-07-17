package com.huanghuang.rsintegration.mods.apotheosis;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.batch.AbstractBatchDelegate;
import com.huanghuang.rsintegration.crafting.batch.BatchConcurrencyCapabilities;
import com.huanghuang.rsintegration.recipe.ModRecipeHandlers;
import com.refinedmods.refinedstorage.api.network.INetwork;
import dev.shadowsoffire.apotheosis.village.fletching.FletchingContainer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/** Instant, ordered 1x3 crafting delegate for Apotheosis' fletching table. */
public final class ApotheosisFletchingBatchDelegate extends AbstractBatchDelegate {
    static final String RECIPE_CLASS =
            "dev.shadowsoffire.apotheosis.village.fletching.FletchingRecipe";
    static final String BLOCK_CLASS =
            "dev.shadowsoffire.apotheosis.village.fletching.ApothFletchingBlock";
    static final String BLOCK_ID = "minecraft:fletching_table";

    // FletchingContainer adds the result first, followed by the three matrix slots.
    static final int RESULT_SLOT = ApotheosisFletchingLogic.RESULT_SLOT;
    static final int FIRST_INPUT_SLOT = ApotheosisFletchingLogic.FIRST_INPUT_SLOT;
    static final int INPUT_COUNT = ApotheosisFletchingLogic.INPUT_COUNT;

    private ServerLevel level;
    private ResourceKey<Level> dimension;
    private BlockPos pos;
    private Recipe<?> recipe;
    private FletchingContainer menu;
    private ItemStack expectedOutput = ItemStack.EMPTY;
    private final List<ItemStack> cachedResults = new ArrayList<>();
    private boolean craftDone;

    @Override
    public boolean validateAndInit(@Nonnull ServerPlayer player, @Nonnull ResourceLocation recipeId,
                                   @Nullable ResourceLocation dim, @Nonnull BlockPos pos) {
        ServerLevel resolved = CraftPacketUtils.resolveLevel(player.server, dim, player);
        if (resolved == null) {
            player.sendSystemMessage(Component.translatable("rsi.generic.error.dim_not_found"));
            return false;
        }
        if (!isFletchingTable(resolved, pos)) {
            player.sendSystemMessage(Component.translatable("rsi.apotheosis.error.not_fletching_table"));
            return false;
        }

        Recipe<?> found = resolved.getRecipeManager().byKey(recipeId).orElse(null);
        if (found == null) {
            player.sendSystemMessage(Component.translatable(
                    "rsi.generic.error.recipe_not_found", recipeId.toString()));
            return false;
        }
        if (!found.getClass().getName().equals(RECIPE_CLASS)) {
            return false;
        }

        this.level = resolved;
        this.dimension = resolved.dimension();
        this.pos = pos.immutable();
        this.recipe = found;
        this.machineDim = resolved.dimension().location();
        this.machineServer = player.server;
        this.expectedOutput = found.getResultItem(resolved.registryAccess()).copy();
        this.cachedResults.clear();
        this.craftDone = false;
        this.menu = null;
        markCraftStarted();
        return !expectedOutput.isEmpty();
    }

    @Override
    public boolean acceptsMachineWithoutBlockEntity(@Nonnull ServerLevel level, @Nonnull BlockPos pos) {
        return isFletchingTable(level, pos);
    }

    @Nullable
    @Override
    public List<IngredientSpec> getRequiredMaterials() {
        if (recipe == null) return null;
        var handler = ModRecipeHandlers.handlerFor(recipe);
        return handler != null ? handler.getIngredients(recipe) : null;
    }

    @Override
    public BatchConcurrencyCapabilities concurrencyCapabilities() {
        return BatchConcurrencyCapabilities.delegateResult();
    }

    @Override
    public boolean tryStartSingleCraft(@Nonnull ServerPlayer player) {
        List<IngredientSpec> specs = getRequiredMaterials();
        if (specs == null || specs.size() != INPUT_COUNT) return false;

        ExtractionLedger privateLedger = new ExtractionLedger();
        this.ledger = privateLedger;
        this.usingSharedLedger = false;
        this.network = CraftPacketUtils.resolveNetworkForCraft(player, dimension, pos);
        List<ItemStack> materials = new ArrayList<>(INPUT_COUNT);
        for (IngredientSpec spec : specs) {
            ItemStack reserved = CraftPacketUtils.ensureMaterialAvailable(
                    player, dimension, pos, spec.ingredient(), spec.count(), privateLedger);
            if (reserved.isEmpty()) {
                privateLedger.close();
                return false;
            }
            materials.add(reserved.copy());
        }
        if (!privateLedger.commit(network, player)) return false;
        if (startCraft(player, materials)) return true;

        privateLedger.refundCommitted(network, player);
        return false;
    }

    @Override
    public boolean tryStartWithMaterials(@Nonnull ServerPlayer player,
                                         @Nonnull List<ItemStack> materials,
                                         @Nonnull ExtractionLedger sharedLedger) {
        this.ledger = sharedLedger;
        this.sharedLedger = sharedLedger;
        this.usingSharedLedger = true;
        this.network = CraftPacketUtils.resolveNetworkForCraft(player, dimension, pos);
        return startCraft(player, materials);
    }

    private boolean startCraft(ServerPlayer player, List<ItemStack> materials) {
        craftDone = false;
        cachedResults.clear();
        if (materials.size() != INPUT_COUNT || !isFletchingTable(level, pos)) return false;
        level.getChunk(pos);

        AbstractContainerMenu created = createMenu(player);
        if (!(created instanceof FletchingContainer fletching)) return false;
        this.menu = fletching;
        if (!matrixIsEmpty(fletching) || fletching.getSlot(RESULT_SLOT).hasItem()) {
            clearMenu();
            return false;
        }

        List<IngredientSpec> specs = getRequiredMaterials();
        if (specs == null || specs.size() != INPUT_COUNT) {
            clearMenu();
            return false;
        }
        for (int i = 0; i < INPUT_COUNT; i++) {
            ItemStack material = materials.get(i);
            IngredientSpec spec = specs.get(i);
            if (material == null || material.isEmpty() || material.getCount() < spec.count()
                    || !spec.ingredient().test(material)) {
                clearMenu();
                return false;
            }
            fletching.getSlot(ApotheosisFletchingLogic.menuSlotForIngredient(i))
                    .set(material.copyWithCount(spec.count()));
        }
        fletching.slotsChanged(fletching.getSlot(FIRST_INPUT_SLOT).container);

        Slot outputSlot = fletching.getSlot(RESULT_SLOT);
        ItemStack displayed = outputSlot.getItem().copy();
        if (!sameExactStack(displayed, expectedOutput)) {
            RSIntegrationMod.LOGGER.warn(
                    "[RSI-Apotheosis] Fletching output mismatch at {}: expected={} x{}, actual={} x{}",
                    pos, expectedOutput.getDisplayName().getString(), expectedOutput.getCount(),
                    displayed.isEmpty() ? "empty" : displayed.getDisplayName().getString(),
                    displayed.getCount());
            clearMenu();
            return false;
        }

        ItemStack taken = outputSlot.remove(displayed.getCount());
        if (!sameExactStack(taken, expectedOutput)) {
            clearMenu();
            return false;
        }
        outputSlot.onTake(player, taken);
        cachedResults.add(taken.copy());

        // Preserve any native crafting remainders produced by FletchingResultSlot.
        for (int i = 0; i < INPUT_COUNT; i++) {
            Slot input = fletching.getSlot(FIRST_INPUT_SLOT + i);
            if (input.hasItem()) cachedResults.add(input.remove(input.getItem().getCount()));
        }
        clearMenu();
        craftDone = true;
        return true;
    }

    @Nullable
    private AbstractContainerMenu createMenu(ServerPlayer player) {
        MenuProvider provider = level.getBlockState(pos).getMenuProvider(level, pos);
        if (provider == null) return null;
        try {
            return provider.createMenu(-1, player.getInventory(), player);
        } catch (RuntimeException e) {
            RSIntegrationMod.LOGGER.warn(
                    "[RSI-Apotheosis] Failed to create fletching menu at {}", pos, e);
            return null;
        }
    }

    @Override
    protected boolean isMachineCraftFinished(@Nonnull ServerLevel level, @Nonnull BlockEntity be) {
        return craftDone;
    }

    @Nonnull
    @Override
    protected CraftObservation observeMissingMachineCraft(@Nonnull ServerLevel level,
                                                           @Nonnull BlockPos pos) {
        if (!isFletchingTable(level, pos)) return failObservation("fletching table replaced");
        return craftDone ? doneObservation() : workingObservation();
    }

    @Nonnull
    @Override
    public ItemStack collectResult(@Nonnull ServerPlayer player) {
        if (cachedResults.isEmpty()) return ItemStack.EMPTY;
        ItemStack result = cachedResults.remove(0).copy();
        craftDone = false;
        return result;
    }

    @Nonnull
    @Override
    public List<ItemStack> collectAllResults(@Nonnull ServerPlayer player) {
        List<ItemStack> results = cachedResults.stream().map(ItemStack::copy).toList();
        cachedResults.clear();
        craftDone = false;
        if (ledger != null && !usingSharedLedger && ledger.isCommitted()) {
            ledger.settleAllCommitted();
        }
        return results;
    }

    @Override
    public boolean collectsPhysicalSecondaryOutputs() {
        return true;
    }

    @Nullable
    @Override
    public ExpectedProduction getExpectedProduction() {
        return expectedOutput.isEmpty()
                ? null
                : new ExpectedProduction(expectedOutput, expectedOutput.getCount());
    }

    @Override
    protected void clearMachineState(BlockEntity be, ServerPlayer player) {
        clearMenu();
        cachedResults.clear();
        craftDone = false;
        resetState();
    }

    @Override
    public void onBatchFinished(@NotNull ServerPlayer player) {
        clearMenu();
        cachedResults.clear();
        craftDone = false;
        expectedOutput = ItemStack.EMPTY;
        resetState();
    }

    @Override
    public BlockPos getMachinePos() {
        return pos;
    }

    private void clearMenu() {
        if (menu == null) return;
        for (int i = 0; i < INPUT_COUNT; i++) {
            Slot slot = menu.getSlot(FIRST_INPUT_SLOT + i);
            if (slot.hasItem()) slot.remove(slot.getItem().getCount());
        }
        Slot result = menu.getSlot(RESULT_SLOT);
        if (result.hasItem()) result.remove(result.getItem().getCount());
        menu = null;
    }

    private static boolean matrixIsEmpty(FletchingContainer menu) {
        for (int i = 0; i < INPUT_COUNT; i++) {
            if (menu.getSlot(FIRST_INPUT_SLOT + i).hasItem()) return false;
        }
        return true;
    }

    static boolean sameExactStack(ItemStack actual, ItemStack expected) {
        return ApotheosisFletchingLogic.sameExactStack(actual, expected);
    }

    static boolean isFletchingTable(ServerLevel level, BlockPos pos) {
        if (!level.isLoaded(pos)) return false;
        var block = level.getBlockState(pos).getBlock();
        ResourceLocation id = ForgeRegistries.BLOCKS.getKey(block);
        return id != null && BLOCK_ID.equals(id.toString())
                && BLOCK_CLASS.equals(block.getClass().getName());
    }
}
