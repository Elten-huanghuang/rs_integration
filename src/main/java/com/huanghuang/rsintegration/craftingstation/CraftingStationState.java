package com.huanghuang.rsintegration.craftingstation;

import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/** 各内嵌工作站共享的槽位、结果和物品归还契约。 */
public interface CraftingStationState {
    Container inputs();
    int inputCount();
    ItemStack result();
    void setInput(int index, ItemStack stack);
    void setSyncedResult(ItemStack stack);
    boolean acceptsInput(int index, ItemStack stack);
    void recompute();
    boolean canTakeResult(Player player);
    void takeResult(Player player);
    void returnInputs(Player player);
    void clearInputs();
    void returnCraftingMatrix(Player player);
    void fillFromJei(Player player, List<List<ItemStack>> options);
}
