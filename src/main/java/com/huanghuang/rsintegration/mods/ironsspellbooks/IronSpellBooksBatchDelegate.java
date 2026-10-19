package com.huanghuang.rsintegration.mods.ironsspellbooks;

import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.batch.BatchConcurrencyCapabilities;
import com.huanghuang.rsintegration.crafting.batch.InstantBatchDelegate;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.mods.common.IdleInventoryEvacuator;
import com.refinedmods.refinedstorage.api.util.Action;
import io.redspace.ironsspellbooks.api.registry.SpellRegistry;
import io.redspace.ironsspellbooks.api.registry.SchoolRegistry;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import io.redspace.ironsspellbooks.item.InkItem;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemHandlerHelper;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraft.world.item.crafting.Ingredient;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Executes one dynamic Iron's Spell Books recipe through the actual server menu. */
public final class IronSpellBooksBatchDelegate extends InstantBatchDelegate {
    private static final String ARCANE_ANVIL_MENU_CLASS =
            "io.redspace.ironsspellbooks.gui.arcane_anvil.ArcaneAnvilMenu";

    private ServerLevel level;
    private BlockPos pos;
    private IronSpellBooksRecipe recipe;
    private AbstractContainerMenu menu;
    private final List<ItemStack> results = new ArrayList<>();
    private ItemStack expected = ItemStack.EMPTY;
    private boolean done;

    @Override
    public boolean validateAndInit(@Nonnull ServerPlayer player, @Nonnull ResourceLocation recipeId,
                                   @Nullable ResourceLocation dim, @Nonnull BlockPos pos) {
        ServerLevel resolved = CraftPacketUtils.resolveLevel(player.server, dim, player);
        IronSpellBooksRecipe found = IronSpellBooksRecipeCatalog.byId(recipeId);
        if (resolved == null || found == null || !isMachine(resolved, pos, found.machine())) return false;
        this.level = resolved;
        this.pos = pos.immutable();
        this.recipe = found;
        this.expected = found.getResultItem(resolved.registryAccess());
        this.results.clear();
        this.done = false;
        this.menu = null;
        resetInstantBatch();
        this.machineDim = resolved.dimension().location();
        this.machineServer = player.server;
        markCraftStarted();
        return !expected.isEmpty();
    }

    @Override public boolean acceptsMachineWithoutBlockEntity(@Nonnull ServerLevel level, @Nonnull BlockPos pos) {
        if (recipe != null) return isMachine(level, pos, recipe.machine());
        return isMachine(level, pos, IronSpellBooksRecipe.Machine.SCROLL_FORGE)
                || isMachine(level, pos, IronSpellBooksRecipe.Machine.ARCANE_ANVIL);
    }

    @Override public List<IngredientSpec> getRequiredMaterials() {
        if (recipe == null) return null;
        List<Ingredient> ingredients = recipe.inputIngredients();
        List<ItemStack> displays = recipe.inputs();
        List<IngredientSpec> result = new ArrayList<>();
        for (int i = 0; i < ingredients.size(); i++) {
            result.add(new IngredientSpec(ingredients.get(i), displays.get(i).getCount()));
        }
        return result;
    }

    @Override public BatchConcurrencyCapabilities concurrencyCapabilities() { return BatchConcurrencyCapabilities.delegateResult(); }

    @Override public boolean startSingleOperation(@Nonnull ServerPlayer player) {
        List<IngredientSpec> specs = getRequiredMaterials();
        if (specs == null) return false;
        ExtractionLedger privateLedger = new ExtractionLedger();
        this.ledger = privateLedger;
        this.usingSharedLedger = false;
        if (storageEndpoint() == null) {
            setStorageEndpoint(CraftPacketUtils.resolveEndpointForCraft(player, level.dimension(), pos).orElse(null));
        }
        privateLedger.setStorageEndpoint(storageEndpoint());
        List<ItemStack> materials = new ArrayList<>();
        for (IngredientSpec spec : specs) {
            ItemStack stack = CraftPacketUtils.ensureMaterialAvailable(player, level.dimension(), pos,
                    spec.ingredient(), spec.count(), privateLedger);
            if (stack.isEmpty()) { privateLedger.close(); return false; }
            materials.add(stack.copy());
        }
        if (!privateLedger.commitToEndpoint(storageEndpoint(), player)) return false;
        if (start(player, materials)) return true;
        privateLedger.refundCommittedToEndpoint(storageEndpoint(), player);
        return false;
    }

    @Override public boolean startPreparedOperation(@Nonnull ServerPlayer player, @Nonnull List<ItemStack> materials,
                                                    @Nonnull ExtractionLedger sharedLedger) {
        this.ledger = sharedLedger;
        this.sharedLedger = sharedLedger;
        this.usingSharedLedger = true;
        return start(player, materials);
    }

    private boolean start(ServerPlayer player, List<ItemStack> materials) {
        List<IngredientSpec> specs = getRequiredMaterials();
        if (specs == null) return fail("recipe has no material specification", ItemStack.EMPTY, materials);
        if (materials.size() != specs.size()) {
            return fail("material count does not match recipe specification", ItemStack.EMPTY, materials);
        }
        int executions = preparedExecutions();
        for (int i = 0; i < specs.size(); i++) {
            ItemStack stack = materials.get(i);
            if (stack.isEmpty() || stack.getCount() < scaledCount(
                    specs.get(i).count(), executions)
                    || !specs.get(i).ingredient().test(stack)) {
                return fail("material " + i + " does not satisfy the runtime recipe",
                        ItemStack.EMPTY, materials);
            }
        }

        if (usesDeterministicScrollOutput(recipe.machine())) {
            if (!evacuateScrollForge(player)) {
                return fail("Scroll Forge inventory could not be cleared", ItemStack.EMPTY, materials);
            }
            return startDeterministicScrollForge(materials, executions);
        }

        MenuProvider provider = level.getBlockState(pos).getMenuProvider(level, pos);
        if (provider == null) return fail("machine has no menu provider", ItemStack.EMPTY, materials);
        AbstractContainerMenu created = provider.createMenu(-1, player.getInventory(), player);
        if (created == null || !ARCANE_ANVIL_MENU_CLASS.equals(created.getClass().getName())) {
            return fail("machine created an unexpected menu type", ItemStack.EMPTY, materials);
        }
        this.menu = created;
        if (!machineSlotsEmpty(created)) {
            return fail("machine inventory is occupied", ItemStack.EMPTY, materials);
        }
        results.clear();
        int[] remaining = {materials.get(0).getCount(), materials.get(1).getCount()};
        for (int execution = 0; execution < executions; execution++) {
            if (!machineSlotsEmpty(created)) {
                return fail("Arcane Anvil inventory did not reset between operations",
                        ItemStack.EMPTY, materials);
            }
            for (int i = 0; i < 2; i++) {
                int required = specs.get(i).count();
                if (remaining[i] < required) {
                    return fail("Arcane Anvil batch exhausted materials",
                            ItemStack.EMPTY, materials);
                }
                created.getSlot(i).set(materials.get(i).copyWithCount(required));
                remaining[i] -= required;
            }
            created.slotsChanged(created.getSlot(0).container);
            ItemStack displayed = created.getSlot(2).getItem().copy();
            if (!sameExpectedOutput(displayed)) {
                return fail("Arcane Anvil output mismatch", displayed, materials);
            }
            Slot resultSlot = created.getSlot(2);
            ItemStack taken = resultSlot.remove(displayed.getCount());
            resultSlot.onTake(player, taken);
            results.add(taken.copy());
            clearCraftingSlots(created);
        }
        clearMenu();
        done = !results.isEmpty();
        return done;
    }

    /** Returns pre-existing real inputs to RS before the deterministic forge path runs. */
    private boolean evacuateScrollForge(ServerPlayer player) {
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity == null) return false;
        IItemHandler handler = blockEntity.getCapability(ForgeCapabilities.ITEM_HANDLER)
                .resolve().orElse(null);
        if (handler == null) return false;
        int returned = evacuateScrollForgeInventory(handler,
                stack -> returnToNetworkOrPlayer(stack, player));
        if (returned < 0) return false;

        if (returned > 0) {
            RSIntegrationMod.LOGGER.debug(
                    "[RSI-IronSpells] Returned {} pre-existing Scroll Forge items to RS at {}",
                    returned, pos);
        }
        return true;
    }

    static int evacuateScrollForgeInventory(IItemHandler handler,
                                             Consumer<ItemStack> returnItem) {
        if (handler == null || handler.getSlots() < 4) return -1;
        IdleInventoryEvacuator.Result result = IdleInventoryEvacuator.evacuate(
                handler, true,
                slot -> slot < 3
                        ? IdleInventoryEvacuator.SlotPolicy.RETURN
                        : slot == 3
                        ? IdleInventoryEvacuator.SlotPolicy.DISCARD
                        : IdleInventoryEvacuator.SlotPolicy.PRESERVE,
                returnItem);
        return result.cleared() ? result.returnedCount() : -1;
    }

    private void returnToNetworkOrPlayer(ItemStack stack, ServerPlayer player) {
        ItemStack remainder = insertIntoStorage(player, stack, false);
        if (!remainder.isEmpty()) ItemHandlerHelper.giveItemToPlayer(player, remainder);
    }

    private boolean startDeterministicScrollForge(List<ItemStack> materials, int executions) {
        var spell = SpellRegistry.getSpell(new ResourceLocation(recipe.spellId()));
        if (spell == null || spell == SpellRegistry.none()) {
            return fail("spell is not registered", ItemStack.EMPTY, materials);
        }
        String rejection = deterministicScrollForgeRejection(spell, materials);
        if (rejection != null) {
            return fail("validated Scroll Forge output rejected: " + rejection,
                    ItemStack.EMPTY, materials);
        }
        ItemStack scaled = scaledCopy(expected, executions);
        results.clear();
        results.add(scaled);
        done = !scaled.isEmpty();
        RSIntegrationMod.LOGGER.debug(
                "[RSI-IronSpells] Created validated deterministic Scroll Forge output {} for {}",
                scaled, recipe.getId());
        return done;
    }

    static boolean usesDeterministicScrollOutput(IronSpellBooksRecipe.Machine machine) {
        return machine == IronSpellBooksRecipe.Machine.SCROLL_FORGE;
    }

    private boolean sameExpectedOutput(ItemStack displayed) {
        return ItemStack.isSameItemSameTags(displayed, expected)
                || IronSpellBooksRecipeCatalog.sameSpellScroll(displayed, expected);
    }

    @Nullable
    private String deterministicScrollForgeRejection(AbstractSpell spell,
                                                      List<ItemStack> materials) {
        if (materials.size() != 3 || !(materials.get(0).getItem() instanceof InkItem ink)) {
            return "expected ink, paper, and focus";
        }
        int targetLevel = recipe.spellLevel();
        ItemStack deterministic = IronSpellBooksRecipeCatalog.scrollFor(spell, targetLevel);
        int inkLevel = spell.getMinLevelForRarity(ink.getRarity());
        if (targetLevel <= 0) return "recipe has no target spell level";
        if (!recipe.inputIngredients().get(0).test(materials.get(0))) {
            return "ink is not one of the runtime recipe's accepted inks";
        }
        if (inkLevel != targetLevel) {
            return "ink maps to level " + inkLevel + " but target level is " + targetLevel;
        }
        if (!spell.allowCrafting()) return "spell disallows crafting";
        if (!isRuntimeFocus(spell, materials.get(2))) {
            return "focus is not registered for school " + spell.getSchoolType().getId();
        }
        if (!sameExpectedOutput(deterministic)) {
            return "deterministic scroll does not match the selected target";
        }
        return null;
    }

    private static boolean isRuntimeFocus(AbstractSpell spell, ItemStack focus) {
        try {
            Object schools = SchoolRegistry.class.getMethod(
                    "getSchoolsFromFocus", ItemStack.class).invoke(null, focus);
            if (schools instanceof List<?> list) return list.contains(spell.getSchoolType());
        } catch (ReflectiveOperationException | LinkageError ignored) {
            // Older Iron's Spell Books versions expose only SchoolType.isFocus.
        }
        return spell.getSchoolType().isFocus(focus);
    }

    private boolean fail(String reason, ItemStack displayed, List<ItemStack> materials) {
        RSIntegrationMod.LOGGER.warn(
                "[RSI-IronSpells] {} for {} at {}: expected={}, displayed={}, materials={}",
                reason, recipe == null ? "unknown" : recipe.getId(), pos, expected, displayed,
                materials);
        results.clear();
        clearMenu();
        return false;
    }

    @Override protected boolean isMachineCraftFinished(@Nonnull ServerLevel level, @Nonnull BlockEntity be) { return done; }
    @Override protected CraftObservation observeMissingMachineCraft(@Nonnull ServerLevel level, @Nonnull BlockPos pos) {
        return done ? doneObservation() : workingObservation();
    }
    @Override public ItemStack collectResult(@Nonnull ServerPlayer player) {
        if (results.isEmpty()) return ItemStack.EMPTY;
        ItemStack out = results.remove(0).copy();
        done = results.isEmpty();
        return out;
    }
    @Override public List<ItemStack> collectAllResults(@Nonnull ServerPlayer player) {
        List<ItemStack> output = results.stream().map(ItemStack::copy).toList();
        results.clear();
        done = false;
        resetInstantBatch();
        return output;
    }
    @Override public ExpectedProduction getExpectedProduction() {
        return expected.isEmpty() ? null : new ExpectedProduction(
                expected, scaledCount(expected.getCount(), preparedExecutions()));
    }
    @Override protected void clearMachineState(BlockEntity be, ServerPlayer player) {
        clearMenu(); results.clear(); done = false; resetInstantBatch(); resetState();
    }
    @Override public void onBatchFinished(@Nullable ServerPlayer player) {
        clearMenu(); results.clear(); done = false; resetInstantBatch(); resetState();
    }
    @Override public BlockPos getMachinePos() { return pos; }

    private void clearMenu() {
        if (menu == null) return;
        clearCraftingSlots(menu);
        menu = null;
    }

    private static void clearCraftingSlots(AbstractContainerMenu container) {
        clearSlot(container.getSlot(0));
        clearSlot(container.getSlot(1));
        clearSlot(container.getSlot(2));
    }

    private static boolean machineSlotsEmpty(AbstractContainerMenu menu) {
        return !menu.getSlot(0).hasItem()
                && !menu.getSlot(1).hasItem()
                && !menu.getSlot(2).hasItem();
    }

    private static void clearSlot(Slot slot) {
        if (slot.hasItem()) slot.remove(slot.getItem().getCount());
    }

    private static boolean isMachine(ServerLevel level, BlockPos pos, IronSpellBooksRecipe.Machine machine) {
        if (!level.isLoaded(pos)) return false;
        ResourceLocation id = ForgeRegistries.BLOCKS.getKey(level.getBlockState(pos).getBlock());
        return id != null && ((machine == IronSpellBooksRecipe.Machine.SCROLL_FORGE
                && "irons_spellbooks:scroll_forge".equals(id.toString()))
                || (machine == IronSpellBooksRecipe.Machine.ARCANE_ANVIL
                && "irons_spellbooks:arcane_anvil".equals(id.toString())));
    }
}
