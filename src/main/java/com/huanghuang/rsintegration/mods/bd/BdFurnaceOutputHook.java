package com.huanghuang.rsintegration.mods.bd;

import com.wintercogs.beyonddimensions.api.storage.key.KeyAmount;
import net.minecraft.world.Container;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;

import javax.annotation.Nullable;
import java.util.List;
import java.util.function.BiConsumer;

public interface BdFurnaceOutputHook<R extends AbstractCookingRecipe> {
    RecipeType<R> rsi$recipeType();

    List<RecipeManager.CachedCheck<Container, R>> rsi$recipeChecks();

    void rsi$setOutputReceiver(@Nullable Runnable receiver);

    void rsi$setInputConsumed(@Nullable BiConsumer<Integer, KeyAmount> listener);
}
