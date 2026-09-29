package com.huanghuang.rsintegration.craftingstation;

import com.refinedmods.refinedstorage.api.network.INetwork;
import com.refinedmods.refinedstorage.api.network.grid.INetworkAwareGrid;
import com.refinedmods.refinedstorage.api.util.Action;
import com.refinedmods.refinedstorage.api.util.IComparer;
import com.huanghuang.rsintegration.mixin.minecraft.AnvilMenuAccessor;
import com.refinedmods.refinedstorage.container.GridContainerMenu;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.List;

/** 原版 AnvilMenu 驱动的 RS 虚拟铁砧状态。 */
public final class AnvilTerminalState implements CraftingStationState {
    private final GridContainerMenu menu;
    private final AnvilMenu anvilMenu;
    private final Container inputs;
    private String itemName = "";
    private boolean returned;

    public AnvilTerminalState(GridContainerMenu menu) {
        this.menu = menu;
        this.anvilMenu = new AnvilMenu(menu.containerId, menu.getPlayer().getInventory());
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
        anvilMenu.getSlot(index).set(stack);
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
        if (changed) itemName = name;
        return changed;
    }

    public String itemName() {
        return itemName;
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
            if (!stack.isEmpty()) returnToNetworkOrPlayer(player, stack);
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
        if (player.level().isClientSide || options == null) return;
        for (int i = 0; i < inputCount(); i++) {
            ItemStack previous = inputs.removeItemNoUpdate(i);
            if (!previous.isEmpty()) returnToNetworkOrPlayer(player, previous);
            if (i >= options.size()) continue;
            for (ItemStack option : options.get(i)) {
                if (!acceptsInput(i, option)) continue;
                ItemStack extracted = extract(player, option, 1);
                if (!extracted.isEmpty()) {
                    setInput(i, extracted);
                    break;
                }
            }
        }
        recompute();
        menu.broadcastChanges();
    }

    private ItemStack extract(Player player, ItemStack prototype, int count) {
        INetwork network = network();
        ItemStack fromNetwork = network == null ? ItemStack.EMPTY
                : network.extractItem(prototype, count, IComparer.COMPARE_NBT, Action.PERFORM);
        if (!fromNetwork.isEmpty()) return fromNetwork;
        for (ItemStack stack : player.getInventory().items) {
            if (!ItemStack.isSameItemSameTags(stack, prototype)) continue;
            return stack.split(Math.min(count, stack.getCount()));
        }
        return ItemStack.EMPTY;
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
