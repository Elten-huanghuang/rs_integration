package com.huanghuang.rsintegration.mods.ironsspellbooks;

import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.batch.AbstractBatchDelegate;
import com.huanghuang.rsintegration.crafting.batch.BatchConcurrencyCapabilities;
import io.redspace.ironsspellbooks.api.registry.SpellRegistry;
import io.redspace.ironsspellbooks.gui.arcane_anvil.ArcaneAnvilMenu;
import io.redspace.ironsspellbooks.gui.scroll_forge.ScrollForgeMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
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
        return recipe != null && isMachine(level, pos, recipe.machine());
    }

    @Override public List<IngredientSpec> getRequiredMaterials() {
        if (recipe == null) return null;
        List<IngredientSpec> result = new ArrayList<>();
        for (ItemStack input : recipe.inputs()) result.add(new IngredientSpec(
                net.minecraftforge.common.crafting.StrictNBTIngredient.of(input), input.getCount()));
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
        if (specs == null || materials.size() != specs.size()) return false;
        MenuProvider provider = level.getBlockState(pos).getMenuProvider(level, pos);
        if (provider == null) return false;
        AbstractContainerMenu created = provider.createMenu(-1, player.getInventory(), player);
        if (!(created instanceof ScrollForgeMenu) && !(created instanceof ArcaneAnvilMenu)) return false;
        this.menu = created;
        if (!machineSlotsEmpty(created)) {
            this.menu = null;
            return false;
        }
        for (int i = 0; i < specs.size(); i++) {
            ItemStack stack = materials.get(i);
            if (stack.isEmpty() || stack.getCount() < specs.get(i).count()
                    || !specs.get(i).ingredient().test(stack)) { this.menu = null; return false; }
        }
        if (recipe.machine() == IronSpellBooksRecipe.Machine.SCROLL_FORGE) {
            ScrollForgeMenu forge = (ScrollForgeMenu) created;
            forge.getInkSlot().set(materials.get(0).copyWithCount(1));
            forge.getBlankScrollSlot().set(materials.get(1).copyWithCount(1));
            forge.getFocusSlot().set(materials.get(2).copyWithCount(1));
            var spell = SpellRegistry.getSpell(new ResourceLocation(recipe.spellId()));
            if (spell == null) { clearMenu(); return false; }
            forge.setRecipeSpell(spell);
            ItemStack displayed = forge.getResultSlot().getItem().copy();
            if (!ItemStack.isSameItemSameTags(displayed, expected)) { clearMenu(); return false; }
            Slot resultSlot = forge.getResultSlot();
            result = resultSlot.remove(displayed.getCount());
            resultSlot.onTake(player, result);
        } else {
            ArcaneAnvilMenu anvil = (ArcaneAnvilMenu) created;
            anvil.getSlot(0).set(materials.get(0).copyWithCount(1));
            anvil.getSlot(1).set(materials.get(1).copyWithCount(1));
            anvil.slotsChanged(anvil.getSlot(0).container);
            ItemStack displayed = anvil.getSlot(2).getItem().copy();
            if (!ItemStack.isSameItemSameTags(displayed, expected)) { clearMenu(); return false; }
            Slot resultSlot = anvil.getSlot(2);
            result = resultSlot.remove(displayed.getCount());
            resultSlot.onTake(player, result);
        }
        clearMenu();
        done = !result.isEmpty();
        return done;
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
