package com.huanghuang.rsintegration.mods.ironsspellbooks;

import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.batch.AbstractBatchDelegate;
import com.huanghuang.rsintegration.crafting.batch.BatchConcurrencyCapabilities;
import com.huanghuang.rsintegration.RSIntegrationMod;
import io.redspace.ironsspellbooks.api.registry.SpellRegistry;
import io.redspace.ironsspellbooks.api.registry.SchoolRegistry;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import io.redspace.ironsspellbooks.item.InkItem;
import io.redspace.ironsspellbooks.gui.arcane_anvil.ArcaneAnvilMenu;
import io.redspace.ironsspellbooks.gui.scroll_forge.ScrollForgeMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/** Executes one dynamic Iron's Spell Books recipe through the actual server menu. */
public final class IronSpellBooksBatchDelegate extends AbstractBatchDelegate {
    private ServerLevel level;
    private BlockPos pos;
    private IronSpellBooksRecipe recipe;
    private AbstractContainerMenu menu;
    private ItemStack result = ItemStack.EMPTY;
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
        this.result = ItemStack.EMPTY;
        this.done = false;
        this.menu = null;
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
        List<net.minecraft.world.item.crafting.Ingredient> ingredients = recipe.inputIngredients();
        List<ItemStack> displays = recipe.inputs();
        List<IngredientSpec> result = new ArrayList<>();
        for (int i = 0; i < ingredients.size(); i++) {
            result.add(new IngredientSpec(ingredients.get(i), displays.get(i).getCount()));
        }
        return result;
    }

    @Override public BatchConcurrencyCapabilities concurrencyCapabilities() { return BatchConcurrencyCapabilities.delegateResult(); }

    @Override public boolean tryStartSingleCraft(@Nonnull ServerPlayer player) {
        List<IngredientSpec> specs = getRequiredMaterials();
        if (specs == null) return false;
        ExtractionLedger privateLedger = new ExtractionLedger();
        this.ledger = privateLedger;
        this.usingSharedLedger = false;
        this.network = CraftPacketUtils.resolveNetworkForCraft(player, level.dimension(), pos);
        List<ItemStack> materials = new ArrayList<>();
        for (IngredientSpec spec : specs) {
            ItemStack stack = CraftPacketUtils.ensureMaterialAvailable(player, level.dimension(), pos,
                    spec.ingredient(), spec.count(), privateLedger);
            if (stack.isEmpty()) { privateLedger.close(); return false; }
            materials.add(stack.copy());
        }
        if (!privateLedger.commit(network, player)) return false;
        if (start(player, materials)) return true;
        privateLedger.refundCommitted(network, player);
        return false;
    }

    @Override public boolean tryStartWithMaterials(@Nonnull ServerPlayer player, @Nonnull List<ItemStack> materials,
                                                    @Nonnull ExtractionLedger sharedLedger) {
        this.ledger = sharedLedger;
        this.sharedLedger = sharedLedger;
        this.usingSharedLedger = true;
        this.network = CraftPacketUtils.resolveNetworkForCraft(player, level.dimension(), pos);
        return start(player, materials);
    }

    private boolean start(ServerPlayer player, List<ItemStack> materials) {
        List<IngredientSpec> specs = getRequiredMaterials();
        if (specs == null) return fail("recipe has no material specification", ItemStack.EMPTY, materials);
        if (materials.size() != specs.size()) {
            return fail("material count does not match recipe specification", ItemStack.EMPTY, materials);
        }
        for (int i = 0; i < specs.size(); i++) {
            ItemStack stack = materials.get(i);
            if (stack.isEmpty() || stack.getCount() < specs.get(i).count()
                    || !specs.get(i).ingredient().test(stack)) {
                return fail("material " + i + " does not satisfy the runtime recipe",
                        ItemStack.EMPTY, materials);
            }
        }

        if (usesDeterministicScrollOutput(recipe.machine())) {
            return startDeterministicScrollForge(materials);
        }

        MenuProvider provider = level.getBlockState(pos).getMenuProvider(level, pos);
        if (provider == null) return fail("machine has no menu provider", ItemStack.EMPTY, materials);
        AbstractContainerMenu created = provider.createMenu(-1, player.getInventory(), player);
        if (!(created instanceof ArcaneAnvilMenu anvil)) {
            return fail("machine created an unexpected menu type", ItemStack.EMPTY, materials);
        }
        this.menu = created;
        if (!machineSlotsEmpty(created)) {
            return fail("machine inventory is occupied", ItemStack.EMPTY, materials);
        }
        anvil.getSlot(0).set(materials.get(0).copyWithCount(1));
        anvil.getSlot(1).set(materials.get(1).copyWithCount(1));
        anvil.slotsChanged(anvil.getSlot(0).container);
        ItemStack displayed = anvil.getSlot(2).getItem().copy();
        if (!sameExpectedOutput(displayed)) {
            return fail("Arcane Anvil output mismatch", displayed, materials);
        }
        Slot resultSlot = anvil.getSlot(2);
        result = resultSlot.remove(displayed.getCount());
        resultSlot.onTake(player, result);
        clearMenu();
        done = !result.isEmpty();
        return done;
    }

    private boolean startDeterministicScrollForge(List<ItemStack> materials) {
        var spell = SpellRegistry.getSpell(new ResourceLocation(recipe.spellId()));
        if (spell == null || spell == SpellRegistry.none()) {
            return fail("spell is not registered", ItemStack.EMPTY, materials);
        }
        String rejection = deterministicScrollForgeRejection(spell, materials);
        if (rejection != null) {
            return fail("validated Scroll Forge output rejected: " + rejection,
                    ItemStack.EMPTY, materials);
        }
        result = expected.copy();
        done = !result.isEmpty();
        RSIntegrationMod.LOGGER.debug(
                "[RSI-IronSpells] Created validated deterministic Scroll Forge output {} for {}",
                result, recipe.getId());
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
        clearMenu();
        return false;
    }

    @Override protected boolean isMachineCraftFinished(@Nonnull ServerLevel level, @Nonnull BlockEntity be) { return done; }
    @Override protected CraftObservation observeMissingMachineCraft(@Nonnull ServerLevel level, @Nonnull BlockPos pos) {
        return done ? doneObservation() : workingObservation();
    }
    @Override public ItemStack collectResult(@Nonnull ServerPlayer player) { ItemStack out = result.copy(); result = ItemStack.EMPTY; done = false; return out; }
    @Override public ExpectedProduction getExpectedProduction() { return expected.isEmpty() ? null : new ExpectedProduction(expected, expected.getCount()); }
    @Override protected void clearMachineState(BlockEntity be, ServerPlayer player) { clearMenu(); result = ItemStack.EMPTY; done = false; resetState(); }
    @Override public void onBatchFinished(@Nullable ServerPlayer player) { clearMenu(); result = ItemStack.EMPTY; done = false; resetState(); }
    @Override public BlockPos getMachinePos() { return pos; }

    private void clearMenu() {
        if (menu == null) return;
        if (menu instanceof ScrollForgeMenu forge) {
            clearSlot(forge.getInkSlot());
            clearSlot(forge.getBlankScrollSlot());
            clearSlot(forge.getFocusSlot());
            clearSlot(forge.getResultSlot());
        } else if (menu instanceof ArcaneAnvilMenu anvil) {
            clearSlot(anvil.getSlot(0));
            clearSlot(anvil.getSlot(1));
            clearSlot(anvil.getSlot(2));
        }
        menu = null;
    }

    private static boolean machineSlotsEmpty(AbstractContainerMenu menu) {
        if (menu instanceof ScrollForgeMenu forge) {
            return !forge.getInkSlot().hasItem()
                    && !forge.getBlankScrollSlot().hasItem()
                    && !forge.getFocusSlot().hasItem()
                    && !forge.getResultSlot().hasItem();
        }
        if (menu instanceof ArcaneAnvilMenu anvil) {
            return !anvil.getSlot(0).hasItem()
                    && !anvil.getSlot(1).hasItem()
                    && !anvil.getSlot(2).hasItem();
        }
        return false;
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
