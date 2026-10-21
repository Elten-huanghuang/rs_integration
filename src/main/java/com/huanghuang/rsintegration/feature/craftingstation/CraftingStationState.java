package com.huanghuang.rsintegration.feature.craftingstation;

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

    /** 清空 RS 九格矩阵但不退回。用于代理槽已经退回、客户端仍显示旧矩阵的情况。 */
    void clearCraftingMatrix();
    void fillFromJei(Player player, List<List<ItemStack>> options);
}
