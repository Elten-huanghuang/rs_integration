package com.huanghuang.rsintegration.feature.craftingstation;

import com.huanghuang.rsintegration.mixin.minecraft.AnvilMenuAccessor;
import com.refinedmods.refinedstorage.container.GridContainerMenu;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/** 原版 AnvilMenu 驱动的 RS 虚拟铁砧状态。 */
public final class AnvilTerminalState implements CraftingStationState {
    private final GridContainerMenu menu;
    private final AnvilMenu anvilMenu;
    private final Container inputs;
    private String itemName = "";
    private boolean itemNameEdited;
    private boolean returned;

    public AnvilTerminalState(GridContainerMenu menu) {
        this.menu = menu;
        this.anvilMenu = new AnvilMenu(menu.containerId, menu.getPlayer().getInventory());
        // AnvilMenu 的 itemName 在部分 1.20.1/Forge 组合及其它模组注入后
        // 可能保持为 null。输入槽第一次变更会触发 AnvilUpdateEvent，事件
        // 会把该值直接传给第三方监听器；先走原版重命名入口初始化为空串，
        // 避免神秘遗物扩展等监听器调用 getName().isEmpty() 时崩溃。
        this.anvilMenu.setItemName("");
        this.inputs = anvilMenu.getSlot(0).container;
    }

    @Override
    public Container inputs() {
        return inputs;
    }

    @Override
    public int inputCount() {
        return 2;
    }

    @Override
    public ItemStack result() {
        return anvilMenu.getSlot(2).getItem();
    }

    @Override
    public void setInput(int index, ItemStack stack) {
        if (index < 0 || index >= inputCount()) return;
        ItemStack current = inputs.getItem(index);
        if (index == 0 && (current.isEmpty() != stack.isEmpty()
                || !current.isEmpty() && !stack.isEmpty()
                && !ItemStack.isSameItemSameTags(current, stack))) {
            itemName = "";
            itemNameEdited = false;
            anvilMenu.setItemName("");
        }
        anvilMenu.getSlot(index).set(stack);
        if (index == 0 && !stack.isEmpty() && stack.hasCustomHoverName()) {
            itemName = stack.getHoverName().getString();
            anvilMenu.setItemName(itemName);
        }
        returned = false;
    }

    @Override
    public void setSyncedResult(ItemStack stack) {
        if (menu.getPlayer().level().isClientSide) anvilMenu.getSlot(2).set(stack);
    }

    @Override
    public boolean acceptsInput(int index, ItemStack stack) {
        return index >= 0 && index < inputCount() && stack != null && !stack.isEmpty()
                && anvilMenu.getSlot(index).mayPlace(stack);
    }

    @Override
    public void recompute() {
        anvilMenu.createResult();
    }

    @Override
    public boolean canTakeResult(Player player) {
        return anvilMenu.getSlot(2).mayPickup(player);
    }

    @Override
    public void takeResult(Player player) {
        if (!canTakeResult(player)) return;
        Slot output = anvilMenu.getSlot(2);
        ItemStack taken = output.remove(output.getItem().getCount());
        ((AnvilMenuAccessor) (Object) anvilMenu).rsi$onTake(player, taken);
        menu.broadcastChanges();
    }

    public boolean setItemName(String name) {
        boolean changed = anvilMenu.setItemName(name);
        itemNameEdited = true;
        itemName = name;
        return changed;
    }

    public String itemName() {
        return visibleItemName(inputs.getItem(0), itemName, itemNameEdited);
    }

    static String visibleItemName(ItemStack input, String itemName, boolean itemNameEdited) {
        if (input.isEmpty()) return "";
        if (itemNameEdited) return itemName;
        return input.getHoverName().getString();
    }

    public int cost() {
        return anvilMenu.getCost();
    }

    @Override
    public void returnInputs(Player player) {
        if (returned) return;
        returned = true;
        for (int i = 0; i < inputCount(); i++) {
            ItemStack stack = inputs.removeItemNoUpdate(i);
            if (!stack.isEmpty()) {
                CraftingStationInventorySupport.returnToNetworkOrPlayer(
                        menu, player, stack);
            }
        }
        anvilMenu.getSlot(2).set(ItemStack.EMPTY);
    }

    @Override
    public void clearInputs() {
        inputs.clearContent();
        anvilMenu.getSlot(2).set(ItemStack.EMPTY);
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
        if (player.level().isClientSide || options == null) return;
        for (int i = 0; i < inputCount(); i++) {
            ItemStack previous = inputs.removeItemNoUpdate(i);
            if (!previous.isEmpty()) {
                CraftingStationInventorySupport.returnToNetworkOrPlayer(
                        menu, player, previous);
            }
            if (i >= options.size()) continue;
            for (ItemStack option : options.get(i)) {
                if (!acceptsInput(i, option)) continue;
                ItemStack extracted = CraftingStationInventorySupport.extract(
                        menu, player, option, 1);
                if (!extracted.isEmpty()) {
                    setInput(i, extracted);
                    break;
                }
            }
        }
        recompute();
        menu.broadcastChanges();
    }

}
