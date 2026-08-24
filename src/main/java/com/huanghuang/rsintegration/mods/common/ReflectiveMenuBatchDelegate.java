package com.huanghuang.rsintegration.mods.common;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.batch.AbstractBatchDelegate;
import com.huanghuang.rsintegration.crafting.batch.BatchConcurrencyCapabilities;
import net.minecraft.core.BlockPos;
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

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/** Executes an instant external crafting recipe through the machine's real server menu. */
public abstract class ReflectiveMenuBatchDelegate extends AbstractBatchDelegate {
    private final String recipeClass;
    private final String menuClass;
    private final String blockId;
    private final int inputCount;
    private final int resultSlotIndex;
    private final boolean strictResultNbt;

    protected ServerLevel level;
    protected ResourceKey<Level> dimension;
    protected BlockPos pos;
    protected Recipe<?> recipe;
    protected AbstractContainerMenu menu;
    protected ItemStack expected = ItemStack.EMPTY;
    protected final List<ItemStack> results = new ArrayList<>();
    protected boolean done;

    protected ReflectiveMenuBatchDelegate(String recipeClass, String menuClass, String blockId,
                                          int inputCount, int resultSlotIndex, boolean strictResultNbt) {
        this.recipeClass = recipeClass;
        this.menuClass = menuClass;
        this.blockId = blockId;
        this.inputCount = inputCount;
        this.resultSlotIndex = resultSlotIndex;
        this.strictResultNbt = strictResultNbt;
    }

    @Override
    public boolean validateAndInit(@Nonnull ServerPlayer player, @Nonnull ResourceLocation recipeId,
                                   @Nullable ResourceLocation dim, @Nonnull BlockPos pos) {
        ServerLevel resolved = CraftPacketUtils.resolveLevel(player.server, dim, player);
        Recipe<?> found = resolved == null ? null : resolved.getRecipeManager().byKey(recipeId).orElse(null);
        if (resolved == null || found == null || !found.getClass().getName().equals(recipeClass)
                || !isMachine(resolved, pos)) return false;
        this.level = resolved;
        this.dimension = resolved.dimension();
        this.pos = pos.immutable();
        this.recipe = found;
        this.expected = found.getResultItem(resolved.registryAccess()).copy();
        this.menu = null;
        this.results.clear();
        this.done = false;
        this.machineDim = resolved.dimension().location();
        this.machineServer = player.server;
        markCraftStarted();
        List<IngredientSpec> specs = getRequiredMaterials();
        return !expected.isEmpty() && specs != null && !specs.isEmpty() && specs.size() <= inputCount;
    }

    @Override public boolean acceptsMachineWithoutBlockEntity(@Nonnull ServerLevel level, @Nonnull BlockPos pos) {
        return isMachine(level, pos);
    }

    @Override public List<IngredientSpec> getRequiredMaterials() {
        return recipe == null ? null : CraftPacketUtils.extractIngredientSpecs(recipe);
    }

    @Override public BatchConcurrencyCapabilities concurrencyCapabilities() {
        return BatchConcurrencyCapabilities.delegateResult();
    }

    @Override public boolean tryStartSingleCraft(@Nonnull ServerPlayer player) {
        List<IngredientSpec> specs = getRequiredMaterials();
        if (specs == null || specs.size() > inputCount) return false;
        ExtractionLedger privateLedger = new ExtractionLedger();
        this.ledger = privateLedger;
        this.usingSharedLedger = false;
        if (storageEndpoint() == null) {
            this.network = CraftPacketUtils.resolveNetworkForCraft(player, dimension, pos);
        }
        privateLedger.setStorageEndpoint(storageEndpoint());
        List<ItemStack> materials = new ArrayList<>(specs.size());
        for (IngredientSpec spec : specs) {
            ItemStack stack = CraftPacketUtils.ensureMaterialAvailable(player, dimension, pos,
                    spec.ingredient(), spec.count(), privateLedger);
            if (stack.isEmpty()) { privateLedger.close(); return false; }
            materials.add(stack.copy());
        }
        if (!privateLedger.commit(network, player)) return false;
        if (start(player, materials)) return true;
        privateLedger.refundCommitted(network, player);
        return false;
    }

    @Override public boolean tryStartWithMaterials(@Nonnull ServerPlayer player,
                                                    @Nonnull List<ItemStack> materials,
                                                    @Nonnull ExtractionLedger sharedLedger) {
        this.ledger = sharedLedger;
        this.sharedLedger = sharedLedger;
        this.usingSharedLedger = true;
        return start(player, materials);
    }

    private boolean start(ServerPlayer player, List<ItemStack> materials) {
        done = false;
        results.clear();
        List<IngredientSpec> specs = getRequiredMaterials();
        if (specs == null || materials.size() != specs.size() || specs.size() > inputCount
                || !isMachine(level, pos)) return false;
        MenuProvider provider = level.getBlockState(pos).getMenuProvider(level, pos);
        if (provider == null) return false;
        AbstractContainerMenu created;
        try {
            created = provider.createMenu(-1, player.getInventory(), player);
        } catch (RuntimeException e) {
            RSIntegrationMod.LOGGER.warn("[RSI-MenuCraft] Cannot create {} at {}", menuClass, pos, e);
            return false;
        }
        if (created == null || !created.getClass().getName().equals(menuClass)) return false;
        this.menu = created;
        if (!machineSlotsEmpty()) { clearMenu(); return false; }
        for (int i = 0; i < specs.size(); i++) {
            IngredientSpec spec = specs.get(i);
            ItemStack material = materials.get(i);
            if (material == null || material.isEmpty() || material.getCount() < spec.count()
                    || !spec.ingredient().test(material)) { clearMenu(); return false; }
            menu.getSlot(menuInputSlotIndex(i)).set(material.copyWithCount(spec.count()));
        }
        menu.slotsChanged(menu.getSlot(0).container);
        Slot resultSlot = menu.getSlot(resultSlotIndex);
        ItemStack displayed = resultSlot.getItem().copy();
        if (!matchesExpected(displayed)) {
            RSIntegrationMod.LOGGER.warn("[RSI-MenuCraft] Output mismatch for {} at {}: expected={}, actual={}",
                    recipe.getId(), pos, expected, displayed);
            clearMenu();
            return false;
        }
        ItemStack taken = resultSlot.remove(displayed.getCount());
        if (!matchesExpected(taken)) { clearMenu(); return false; }
        InventoryDeltaCapture inventory = InventoryDeltaCapture.snapshot(player);
        resultSlot.onTake(player, taken);
        results.add(taken.copy());
        results.addAll(inventory.removeAdded(player));
        collectRemainingInputs();
        clearMenu();
        done = true;
        return true;
    }

    private boolean matchesExpected(ItemStack actual) {
        if (actual.isEmpty() || actual.getCount() != expected.getCount()
                || !ItemStack.isSameItem(actual, expected)) return false;
        return !strictResultNbt || ItemStack.isSameItemSameTags(actual, expected);
    }

    private boolean machineSlotsEmpty() {
        for (int i = 0; i < inputCount; i++) {
            if (menu.getSlot(menuInputSlotIndex(i)).hasItem()) return false;
        }
        return !menu.getSlot(resultSlotIndex).hasItem();
    }

    private void collectRemainingInputs() {
        for (int i = 0; i < inputCount; i++) {
            Slot slot = menu.getSlot(menuInputSlotIndex(i));
            if (slot.hasItem()) results.add(slot.remove(slot.getItem().getCount()).copy());
        }
    }

    protected void clearMenu() {
        if (menu == null) return;
        for (int i = 0; i < inputCount; i++) clearSlot(menu.getSlot(menuInputSlotIndex(i)));
        clearSlot(menu.getSlot(resultSlotIndex));
        menu = null;
    }

    /** Maps recipe input order to the physical menu slot order. */
    protected int menuInputSlotIndex(int recipeInputIndex) {
        return recipeInputIndex;
    }

    private static void clearSlot(Slot slot) {
        if (slot.hasItem()) slot.remove(slot.getItem().getCount());
    }

    private boolean isMachine(ServerLevel level, BlockPos pos) {
        if (!level.hasChunkAt(pos)) return false;
        ResourceLocation id = ForgeRegistries.BLOCKS.getKey(level.getBlockState(pos).getBlock());
        return id != null && blockId.equals(id.toString());
    }

    @Override protected boolean isMachineCraftFinished(@Nonnull ServerLevel level, @Nonnull BlockEntity be) {
        return done;
    }
    @Override protected CraftObservation observeMissingMachineCraft(@Nonnull ServerLevel level, @Nonnull BlockPos pos) {
        if (!isMachine(level, pos)) return failObservation("menu machine replaced");
        return done ? doneObservation() : workingObservation();
    }
    @Override public ItemStack collectResult(@Nonnull ServerPlayer player) {
        List<ItemStack> all = collectAllResults(player);
        return all.isEmpty() ? ItemStack.EMPTY : all.get(0);
    }
    @Override public List<ItemStack> collectAllResults(@Nonnull ServerPlayer player) {
        List<ItemStack> output = results.stream().map(ItemStack::copy).toList();
        results.clear();
        done = false;
        if (ledger != null && !usingSharedLedger && ledger.isCommitted()) ledger.settleAllCommitted();
        return output;
    }
    @Override public boolean collectsPhysicalSecondaryOutputs() { return true; }
    @Override public ExpectedProduction getExpectedProduction() {
        return expected.isEmpty() ? null : new ExpectedProduction(expected, expected.getCount());
    }
    @Override protected void clearMachineState(BlockEntity be, ServerPlayer player) {
        clearMenu(); results.clear(); done = false; resetState();
    }
    @Override protected void clearMissingMachineState(@Nullable ServerPlayer player) {
        clearMenu(); results.clear(); done = false; resetState();
    }
    @Override public void onBatchFinished(@Nullable ServerPlayer player) {
        if (!markTerminalCleanup()) return;
        clearMenu(); results.clear(); done = false; expected = ItemStack.EMPTY; resetState();
    }
    @Override public BlockPos getMachinePos() { return pos; }
}
