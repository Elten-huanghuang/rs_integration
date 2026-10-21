package com.huanghuang.rsintegration.feature.craftingstation;

import com.refinedmods.refinedstorage.container.GridContainerMenu;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.StonecutterMenu;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.StonecutterRecipe;

import java.util.List;

/** 以原版 StonecutterMenu 作为后端，RSI 只代理输入槽和结果槽。 */
public final class StonecutterTerminalState implements CraftingStationState {
    private final GridContainerMenu menu;
    private final StonecutterMenu stonecutterMenu;
    private final Container input;
    private boolean returned;

    public StonecutterTerminalState(GridContainerMenu menu) {
        this.menu = menu;
        this.stonecutterMenu = new StonecutterMenu(menu.containerId, menu.getPlayer().getInventory());
        this.input = stonecutterMenu.getSlot(0).container;
    }

    @Override
    public Container inputs() { return input; }

    @Override
    public int inputCount() { return 1; }

    @Override
    public ItemStack result() { return stonecutterMenu.getSlot(1).getItem(); }

    @Override
    public void setInput(int index, ItemStack stack) {
        if (index != 0) return;
        stonecutterMenu.getSlot(0).set(stack);
        returned = false;
        // 原版输入容器会自动回调 slotsChanged；这里显式触发，兼容 RSI 代理槽写入路径。
        stonecutterMenu.slotsChanged(input);
    }

    @Override
    public void setSyncedResult(ItemStack stack) {
        if (menu.getPlayer().level().isClientSide) stonecutterMenu.getSlot(1).set(stack);
    }

    @Override
    public boolean acceptsInput(int index, ItemStack stack) {
        // 原版切石机输入槽不筛选物品，没有配方时只显示空配方列表。
        return index == 0 && stack != null && !stack.isEmpty()
                && stonecutterMenu.getSlot(0).mayPlace(stack);
    }

    @Override
    public void recompute() { stonecutterMenu.slotsChanged(input); }

    public List<StonecutterRecipe> recipes() { return stonecutterMenu.getRecipes(); }

    public int selectedRecipeIndex() { return stonecutterMenu.getSelectedRecipeIndex(); }

    public boolean selectRecipe(int index) {
        if (index < 0 || index >= stonecutterMenu.getNumRecipes()) return false;
        return stonecutterMenu.clickMenuButton(menu.getPlayer(), index);
    }

    /** 原版 StonecutterMenu.quickMoveStack 只把有切石配方的物品移入输入槽。 */
    public boolean canQuickMoveInput(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        SimpleContainer candidate = new SimpleContainer(stack.copy());
        return menu.getPlayer().level().getRecipeManager()
                .getRecipeFor(RecipeType.STONECUTTING, candidate, menu.getPlayer().level())
                .isPresent();
    }

    @Override
    public boolean canTakeResult(Player player) { return stonecutterMenu.getSlot(1).hasItem(); }

    @Override
    public void takeResult(Player player) {
        if (!canTakeResult(player)) return;
        Slot output = stonecutterMenu.getSlot(1);
        ItemStack taken = output.remove(output.getItem().getCount());
        output.onTake(player, taken);
        menu.broadcastChanges();
    }

    @Override
    public void returnInputs(Player player) {
        if (returned) return;
        returned = true;
        ItemStack stack = input.removeItemNoUpdate(0);
        if (!stack.isEmpty()) {
            CraftingStationInventorySupport.returnToNetworkOrPlayer(
                    menu, player, stack);
        }
        stonecutterMenu.getSlot(1).set(ItemStack.EMPTY);
        recompute();
    }

    @Override
    public void clearInputs() {
        input.removeItemNoUpdate(0);
        stonecutterMenu.getSlot(1).set(ItemStack.EMPTY);
        recompute();
        returned = true;
    }

    @Override
    public void returnCraftingMatrix(Player player) {
        CraftingStationInventorySupport.returnCraftingMatrix(menu, player);
    }

    /** 只清空九格，不插入网络。代理槽退回后用来去掉客户端仍显示的旧物品。 */
    @Override
    public void clearCraftingMatrix() {
        CraftingStationInventorySupport.clearCraftingMatrix(menu);
    }


    @Override
    public void fillFromJei(Player player, List<List<ItemStack>> options) {
        if (player.level().isClientSide) return;
        ItemStack previous = input.removeItemNoUpdate(0);
        if (!previous.isEmpty()) {
            CraftingStationInventorySupport.returnToNetworkOrPlayer(
                    menu, player, previous);
        }
        recompute();
        if (options != null && !options.isEmpty()) {
            for (ItemStack option : options.get(0)) {
                if (!acceptsInput(0, option)) continue;
                ItemStack extracted = CraftingStationInventorySupport.extract(
                        menu, player, option, Math.max(1, option.getCount()));
                if (!extracted.isEmpty()) {
                    setInput(0, extracted);
                    break;
                }
            }
        }
        // JEI 已明确目标配方；输入完成后选中第一条原版候选，保留一键转移体验。
        if (!recipes().isEmpty()) selectRecipe(0);
        menu.broadcastChanges();
    }

}
