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
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.TransientCraftingContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SmithingRecipe;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.List;

/** RS 终端内嵌锻造台的服务端/客户端共享状态。 */
public final class SmithingTerminalState implements CraftingStationState {
    private final GridContainerMenu menu;
    private final Level level;
    private final TransientCraftingContainer inputs;
    private final ResultContainer result = new ResultContainer();
    private final List<SmithingRecipe> recipes;
    @Nullable
    private SmithingRecipe recipe;
    private boolean returned;

    public SmithingTerminalState(GridContainerMenu menu) {
        this.menu = menu;
        this.level = menu.getPlayer().level();
        this.inputs = new TransientCraftingContainer((AbstractContainerMenu) menu, 3, 1);
        this.recipes = level.getRecipeManager().getAllRecipesFor(RecipeType.SMITHING);
    }

    @Override
    public Container inputs() {
        return inputs;
    }

    @Override
    public int inputCount() {
        return 3;
    }

    @Override
    public ItemStack result() {
        return result.getItem(0);
    }

    @Override
    public void setInput(int index, ItemStack stack) {
        if (index < 0 || index >= 3) return;
        inputs.setItem(index, stack);
        if (!stack.isEmpty()) returned = false;
        recompute();
    }

    @Override
    public void setSyncedResult(ItemStack stack) {
        if (level.isClientSide) result.setItem(0, stack);
    }

    /** 原版 SmithingMenu 的模板/基底/附加材料槽语义。 */
    @Override
    public boolean acceptsInput(int index, ItemStack stack) {
        if (index < 0 || index >= 3 || stack == null || stack.isEmpty()) return false;
        return recipes.stream().anyMatch(recipe -> switch (index) {
            case 0 -> recipe.isTemplateIngredient(stack);
            case 1 -> recipe.isBaseIngredient(stack);
            case 2 -> recipe.isAdditionIngredient(stack);
            default -> false;
        });
    }

    @Override
    public void recompute() {
        recipe = null;
        result.setItem(0, ItemStack.EMPTY);
        List<SmithingRecipe> matches = level.getRecipeManager()
                .getRecipesFor(RecipeType.SMITHING, inputs, level);
        if (!matches.isEmpty()) {
            SmithingRecipe candidate = matches.get(0);
            ItemStack assembled = candidate.assemble(inputs, level.registryAccess());
            if (!assembled.isEmpty() && assembled.isItemEnabled(level.enabledFeatures())) {
                recipe = candidate;
                result.setRecipeUsed(candidate);
                result.setItem(0, assembled);
            }
        }
    }

    @Override
    public boolean canTakeResult(Player player) {
        return recipe != null && recipe.matches(inputs, level);
    }

    @Override
    public void takeResult(Player player) {
        if (!canTakeResult(player)) return;
        ItemStack crafted = result.getItem(0);
        crafted.onCraftedBy(player.level(), player, crafted.getCount());
        result.awardUsedRecipes(player, List.of(inputs.getItem(0), inputs.getItem(1), inputs.getItem(2)));
        for (int i = 0; i < 3; i++) {
            ItemStack stack = inputs.getItem(i);
            if (!stack.isEmpty()) {
                stack.shrink(1);
                inputs.setItem(i, stack);
            }
        }
        recompute();
        menu.broadcastChanges();
    }

    /** 锻造材料优先回到终端所属的 RS 网络，网络不可用时交还玩家。 */
    @Override
    public void returnInputs(Player player) {
        if (returned) return;
        returned = true;
        for (int i = 0; i < 3; i++) {
            ItemStack stack = inputs.removeItemNoUpdate(i);
            if (!stack.isEmpty()) returnToNetworkOrPlayer(player, stack);
        }
        result.setItem(0, ItemStack.EMPTY);
        recipe = null;
    }

    @Override
    public void clearInputs() {
        for (int i = 0; i < 3; i++) inputs.removeItemNoUpdate(i);
        result.setItem(0, ItemStack.EMPTY);
        recipe = null;
        returned = true;
    }

    /**
     * 切换到锻造模式前清理 RS 原生 3x3 矩阵。
     * GridContainerMenu.initSlots() 只重建 Slot 列表，不会替我们归还矩阵中的物品；
     * 若直接切换，旧矩阵内容会失去可见槽位并在关闭终端时消失。
     */
    @Override
    public void returnCraftingMatrix(Player player) {
        if (player.level().isClientSide || menu.getGrid() == null
                || menu.getGrid().getCraftingMatrix() == null) {
            return;
        }
        CraftingContainer matrix = menu.getGrid().getCraftingMatrix();
        for (int i = 0; i < matrix.getContainerSize(); i++) {
            ItemStack stack = matrix.removeItemNoUpdate(i);
            if (!stack.isEmpty()) {
                returnToNetworkOrPlayer(player, stack);
            }
        }
    }

    /** JEI 一键填充：服务端按候选顺序从网络优先、玩家库存其次提取。 */
    @Override
    public void fillFromJei(Player player, List<List<ItemStack>> options) {
        if (player.level().isClientSide || options == null) return;
        returned = false;
        for (int i = 0; i < 3; i++) {
            SmithingInputSlot slot = findInputSlot(i);
            if (slot == null) continue;
            ItemStack previous = inputs.removeItemNoUpdate(i);
            if (!previous.isEmpty()) returnToNetworkOrPlayer(player, previous);
            if (i >= options.size()) continue;
            ItemStack extracted = extractOptions(player, i, options.get(i));
            if (!extracted.isEmpty()) setInput(i, extracted);
        }
        recompute();
        menu.broadcastChanges();
    }

    private ItemStack extractOptions(Player player, int inputIndex, List<ItemStack> options) {
        if (options == null) return ItemStack.EMPTY;
        for (ItemStack option : options) {
            if (option == null || option.isEmpty() || !acceptsInput(inputIndex, option)) continue;
            int requested = Math.max(1, Math.min(option.getCount(), option.getMaxStackSize()));
            ItemStack extracted = extract(player, option, requested);
            if (!extracted.isEmpty()) return extracted;
        }
        return ItemStack.EMPTY;
    }

    private ItemStack extract(Player player, ItemStack prototype, int count) {
        INetwork network = network();
        ItemStack fromNetwork = network == null ? ItemStack.EMPTY
                : network.extractItem(prototype, count, IComparer.COMPARE_NBT, Action.PERFORM);
        int remaining = count - fromNetwork.getCount();
        ItemStack result = fromNetwork;
        if (remaining > 0) {
            ItemStack fromPlayer = extractFromPlayerInventory(player, prototype, remaining);
            if (result.isEmpty()) result = fromPlayer;
            else if (!fromPlayer.isEmpty()) result.grow(fromPlayer.getCount());
        }
        return result;
    }

    private static ItemStack extractFromPlayerInventory(Player player, ItemStack prototype, int count) {
        ItemStack result = ItemStack.EMPTY;
        for (int i = 0; i < player.getInventory().items.size() && count > 0; i++) {
            ItemStack current = player.getInventory().items.get(i);
            if (!ItemStack.isSameItemSameTags(current, prototype)) continue;
            int taken = Math.min(count, current.getCount());
            ItemStack part = current.split(taken);
            if (result.isEmpty()) result = part;
            else result.grow(part.getCount());
            count -= taken;
        }
        for (ItemStack current : player.getInventory().offhand) {
            if (count <= 0 || !ItemStack.isSameItemSameTags(current, prototype)) continue;
            int taken = Math.min(count, current.getCount());
            ItemStack part = current.split(taken);
            if (result.isEmpty()) result = part;
            else result.grow(part.getCount());
            count -= taken;
        }
        return result;
    }

    private void returnToNetworkOrPlayer(Player player, ItemStack stack) {
        INetwork network = network();
        if (network != null) {
            ItemStack remaining = network.insertItem(stack, stack.getCount(), Action.PERFORM);
            if (!remaining.isEmpty()) player.getInventory().placeItemBackInInventory(remaining);
        } else {
            player.getInventory().placeItemBackInInventory(stack);
        }
    }

    @Nullable
    private SmithingInputSlot findInputSlot(int index) {
        for (Slot slot : menu.slots) {
            if (slot instanceof SmithingInputSlot input && input.isActive()
                    && input.getSlotIndex() == index) return input;
        }
        return null;
    }

    @Nullable
    private INetwork network() {
        return menu.getGrid() instanceof INetworkAwareGrid aware ? aware.getNetwork() : null;
    }

}
