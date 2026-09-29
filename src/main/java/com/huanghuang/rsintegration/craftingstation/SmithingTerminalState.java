package com.huanghuang.rsintegration.craftingstation;

import com.refinedmods.refinedstorage.api.network.INetwork;
import com.refinedmods.refinedstorage.api.network.grid.INetworkAwareGrid;
import com.refinedmods.refinedstorage.api.util.Action;
import com.refinedmods.refinedstorage.api.util.IComparer;
import com.refinedmods.refinedstorage.container.GridContainerMenu;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.SmithingMenu;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.List;

/** 以原版 SmithingMenu 作为后端，RSI 只代理其三个输入槽和结果槽。 */
public final class SmithingTerminalState implements CraftingStationState {
    private final GridContainerMenu menu;
    private final SmithingMenu smithingMenu;
    private final Container inputs;
    private boolean returned;

    public SmithingTerminalState(GridContainerMenu menu) {
        this.menu = menu;
        this.smithingMenu = new SmithingMenu(menu.containerId, menu.getPlayer().getInventory());
        this.inputs = smithingMenu.getSlot(0).container;
    }

    @Override
    public Container inputs() { return inputs; }

    @Override
    public int inputCount() { return 3; }

    @Override
    public ItemStack result() { return smithingMenu.getSlot(3).getItem(); }

    @Override
    public void setInput(int index, ItemStack stack) {
        if (index < 0 || index >= inputCount()) return;
        smithingMenu.getSlot(index).set(stack);
        returned = false;
        // RSI 代理槽不会调用 SmithingMenu.slotsChanged，主动走原版结果计算。
        smithingMenu.createResult();
    }

    @Override
    public void setSyncedResult(ItemStack stack) {
        if (menu.getPlayer().level().isClientSide) smithingMenu.getSlot(3).set(stack);
    }

    /** 完全复用 SmithingMenu 的模板、基底和附加材料判断。 */
    @Override
    public boolean acceptsInput(int index, ItemStack stack) {
        return index >= 0 && index < inputCount()
                && stack != null && !stack.isEmpty()
                && smithingMenu.getSlot(index).mayPlace(stack);
    }

    @Override
    public void recompute() { smithingMenu.createResult(); }

    @Override
    public boolean canTakeResult(Player player) {
        return smithingMenu.getSlot(3).mayPickup(player);
    }

    @Override
    public void takeResult(Player player) {
        if (!canTakeResult(player)) return;
        Slot output = smithingMenu.getSlot(3);
        ItemStack taken = output.remove(output.getItem().getCount());
        output.onTake(player, taken);
        menu.broadcastChanges();
    }

    @Override
    public void returnInputs(Player player) {
        if (returned) return;
        returned = true;
        for (int i = 0; i < inputCount(); i++) {
            ItemStack stack = inputs.removeItemNoUpdate(i);
            if (!stack.isEmpty()) returnToNetworkOrPlayer(player, stack);
        }
        smithingMenu.getSlot(3).set(ItemStack.EMPTY);
    }

    @Override
    public void clearInputs() {
        inputs.clearContent();
        smithingMenu.getSlot(3).set(ItemStack.EMPTY);
        returned = true;
    }

    @Override
    public void returnCraftingMatrix(Player player) {
        if (player.level().isClientSide || menu.getGrid() == null
                || menu.getGrid().getCraftingMatrix() == null) return;
        for (int i = 0; i < menu.getGrid().getCraftingMatrix().getContainerSize(); i++) {
            ItemStack stack = menu.getGrid().getCraftingMatrix().removeItemNoUpdate(i);
            if (!stack.isEmpty()) returnToNetworkOrPlayer(player, stack);
        }
    }

    /** JEI 一键填充：候选物品仍由服务端提取，放入时使用原版槽位语义。 */
    @Override
    public void fillFromJei(Player player, List<List<ItemStack>> options) {
        if (player.level().isClientSide || options == null) return;
        returned = false;
        for (int i = 0; i < inputCount(); i++) {
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
        ItemStack result = network == null ? ItemStack.EMPTY
                : network.extractItem(prototype, count, IComparer.COMPARE_NBT, Action.PERFORM);
        int remaining = count - result.getCount();
        if (remaining <= 0) return result;
        ItemStack fromPlayer = extractFromPlayerInventory(player, prototype, remaining);
        if (result.isEmpty()) return fromPlayer;
        if (!fromPlayer.isEmpty()) result.grow(fromPlayer.getCount());
        return result;
    }

    private static ItemStack extractFromPlayerInventory(Player player, ItemStack prototype, int count) {
        ItemStack result = ItemStack.EMPTY;
        for (ItemStack current : player.getInventory().items) {
            if (count <= 0 || !ItemStack.isSameItemSameTags(current, prototype)) continue;
            ItemStack part = current.split(Math.min(count, current.getCount()));
            if (result.isEmpty()) result = part;
            else result.grow(part.getCount());
            count -= part.getCount();
        }
        for (ItemStack current : player.getInventory().offhand) {
            if (count <= 0 || !ItemStack.isSameItemSameTags(current, prototype)) continue;
            ItemStack part = current.split(Math.min(count, current.getCount()));
            if (result.isEmpty()) result = part;
            else result.grow(part.getCount());
            count -= part.getCount();
        }
        return result;
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
