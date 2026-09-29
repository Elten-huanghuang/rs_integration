package com.huanghuang.rsintegration.craftingstation;

import com.refinedmods.refinedstorage.api.network.INetwork;
import com.refinedmods.refinedstorage.api.network.grid.INetworkAwareGrid;
import com.refinedmods.refinedstorage.api.util.Action;
import com.refinedmods.refinedstorage.api.util.IComparer;
import com.refinedmods.refinedstorage.container.GridContainerMenu;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.ResultContainer;
import net.minecraft.world.inventory.TransientCraftingContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.StonecutterRecipe;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.List;

/** RS 终端内嵌切石机的单输入、配方列表和选中结果状态。 */
public final class StonecutterTerminalState implements CraftingStationState {
    private final GridContainerMenu menu;
    private final Level level;
    private final TransientCraftingContainer input;
    private final ResultContainer result = new ResultContainer();
    private List<StonecutterRecipe> recipes = List.of();
    private int selectedRecipeIndex = -1;
    private ItemStack lastInput = ItemStack.EMPTY;
    private boolean returned;

    public StonecutterTerminalState(GridContainerMenu menu) {
        this.menu = menu;
        this.level = menu.getPlayer().level();
        this.input = new TransientCraftingContainer((AbstractContainerMenu) menu, 1, 1);
    }

    @Override
    public Container inputs() {
        return input;
    }

    @Override
    public int inputCount() {
        return 1;
    }

    @Override
    public ItemStack result() {
        return result.getItem(0);
    }

    @Override
    public void setInput(int index, ItemStack stack) {
        if (index != 0) return;
        if (!lastInput.isEmpty() && !stack.isEmpty()
                && !stack.is(lastInput.getItem())) selectedRecipeIndex = -1;
        if (lastInput.isEmpty() != stack.isEmpty()) selectedRecipeIndex = -1;
        input.setItem(0, stack);
        lastInput = stack.copy();
        if (!stack.isEmpty()) returned = false;
        recompute();
    }

    @Override
    public void setSyncedResult(ItemStack stack) {
        if (level.isClientSide) result.setItem(0, stack);
    }

    @Override
    public boolean acceptsInput(int index, ItemStack stack) {
        return index == 0 && stack != null && !stack.isEmpty()
                && level.getRecipeManager().getRecipeFor(RecipeType.STONECUTTING, inputFor(stack), level)
                .isPresent();
    }

    @Override
    public void recompute() {
        ItemStack current = input.getItem(0);
        recipes = current.isEmpty() ? List.of()
                : level.getRecipeManager().getRecipesFor(RecipeType.STONECUTTING, input, level);
        if (selectedRecipeIndex >= recipes.size()) selectedRecipeIndex = -1;
        if (selectedRecipeIndex < 0 && !recipes.isEmpty()) selectedRecipeIndex = 0;
        updateResult();
    }

    public List<StonecutterRecipe> recipes() {
        return recipes;
    }

    public int selectedRecipeIndex() {
        return selectedRecipeIndex;
    }

    public boolean selectRecipe(int index) {
        if (index < 0 || index >= recipes.size()) return false;
        selectedRecipeIndex = index;
        updateResult();
        return true;
    }

    @Override
    public boolean canTakeResult(Player player) {
        return selectedRecipeIndex >= 0 && selectedRecipeIndex < recipes.size()
                && !result().isEmpty() && recipes.get(selectedRecipeIndex).matches(input, level);
    }

    @Override
    public void takeResult(Player player) {
        if (!canTakeResult(player)) return;
        ItemStack crafted = result();
        crafted.onCraftedBy(player.level(), player, crafted.getCount());
        result.awardUsedRecipes(player, List.of(input.getItem(0)));
        ItemStack remaining = input.getItem(0);
        remaining.shrink(1);
        input.setItem(0, remaining);
        recompute();
        menu.broadcastChanges();
    }

    @Override
    public void returnInputs(Player player) {
        if (returned) return;
        returned = true;
        ItemStack stack = input.removeItemNoUpdate(0);
        if (!stack.isEmpty()) returnToNetworkOrPlayer(player, stack);
        recipes = List.of();
        selectedRecipeIndex = -1;
        result.setItem(0, ItemStack.EMPTY);
        lastInput = ItemStack.EMPTY;
    }

    @Override
    public void clearInputs() {
        input.removeItemNoUpdate(0);
        recipes = List.of();
        selectedRecipeIndex = -1;
        result.setItem(0, ItemStack.EMPTY);
        lastInput = ItemStack.EMPTY;
        returned = true;
    }

    @Override
    public void returnCraftingMatrix(Player player) {
        if (player.level().isClientSide || menu.getGrid() == null
                || menu.getGrid().getCraftingMatrix() == null) return;
        CraftingContainer matrix = menu.getGrid().getCraftingMatrix();
        for (int i = 0; i < matrix.getContainerSize(); i++) {
            ItemStack stack = matrix.removeItemNoUpdate(i);
            if (!stack.isEmpty()) returnToNetworkOrPlayer(player, stack);
        }
    }

    @Override
    public void fillFromJei(Player player, List<List<ItemStack>> options) {
        if (player.level().isClientSide) return;
        ItemStack old = input.removeItemNoUpdate(0);
        if (!old.isEmpty()) returnToNetworkOrPlayer(player, old);
        if (options != null && !options.isEmpty()) {
            for (ItemStack option : options.get(0)) {
                if (!acceptsInput(0, option)) continue;
                ItemStack extracted = extract(player, option, Math.max(1, option.getCount()));
                if (!extracted.isEmpty()) {
                    setInput(0, extracted);
                    break;
                }
            }
        }
        recompute();
        menu.broadcastChanges();
    }

    private void updateResult() {
        result.setItem(0, ItemStack.EMPTY);
        if (selectedRecipeIndex < 0 || selectedRecipeIndex >= recipes.size()) return;
        ItemStack assembled = recipes.get(selectedRecipeIndex).assemble(input, level.registryAccess());
        if (assembled.isItemEnabled(level.enabledFeatures())) {
            result.setRecipeUsed(recipes.get(selectedRecipeIndex));
            result.setItem(0, assembled);
        }
    }

    private TransientCraftingContainer inputFor(ItemStack stack) {
        TransientCraftingContainer container = new TransientCraftingContainer((AbstractContainerMenu) menu, 1, 1);
        container.setItem(0, stack);
        return container;
    }

    private ItemStack extract(Player player, ItemStack prototype, int count) {
        INetwork network = network();
        ItemStack fromNetwork = network == null ? ItemStack.EMPTY
                : network.extractItem(prototype, count, IComparer.COMPARE_NBT, Action.PERFORM);
        int remaining = count - fromNetwork.getCount();
        if (remaining <= 0) return fromNetwork;
        for (ItemStack stack : player.getInventory().items) {
            if (!ItemStack.isSameItemSameTags(stack, prototype)) continue;
            ItemStack part = stack.split(Math.min(remaining, stack.getCount()));
            if (fromNetwork.isEmpty()) fromNetwork = part;
            else fromNetwork.grow(part.getCount());
            remaining -= part.getCount();
            if (remaining <= 0) break;
        }
        return fromNetwork;
    }

    private void returnToNetworkOrPlayer(Player player, ItemStack stack) {
        INetwork network = network();
        if (network == null) {
            player.getInventory().placeItemBackInInventory(stack);
            return;
        }
        ItemStack remaining = network.insertItem(stack, stack.getCount(), Action.PERFORM);
        if (!remaining.isEmpty()) player.getInventory().placeItemBackInInventory(remaining);
    }

    @Nullable
    private INetwork network() {
        return menu.getGrid() instanceof INetworkAwareGrid aware ? aware.getNetwork() : null;
    }
}
