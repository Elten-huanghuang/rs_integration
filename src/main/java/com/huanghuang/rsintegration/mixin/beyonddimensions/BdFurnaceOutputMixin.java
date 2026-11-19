package com.huanghuang.rsintegration.mixin.beyonddimensions;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.huanghuang.rsintegration.mods.bd.BdFurnaceOutputHook;
import com.wintercogs.beyonddimensions.api.storage.handler.impl.StackHandler;
import com.wintercogs.beyonddimensions.api.storage.key.KeyAmount;
import com.wintercogs.beyonddimensions.common.block.entity.BaseNetFurnaceBlockEntity;
import net.minecraft.world.Container;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import javax.annotation.Nullable;
import java.util.List;
import java.util.function.BiConsumer;

@Mixin(value = BaseNetFurnaceBlockEntity.class, remap = false)
public abstract class BdFurnaceOutputMixin<R extends AbstractCookingRecipe> implements BdFurnaceOutputHook<R> {
    @Unique @Nullable private Runnable rsi$outputReceiver;
    @Unique @Nullable private BiConsumer<Integer, KeyAmount> rsi$inputConsumed;

    @Override
    @Accessor("recipeType")
    public abstract RecipeType<R> rsi$recipeType();

    @Override
    @Accessor("quickChecks")
    public abstract List<RecipeManager.CachedCheck<Container, R>> rsi$recipeChecks();

    @Override
    public void rsi$setOutputReceiver(@Nullable Runnable receiver) {
        rsi$outputReceiver = receiver;
    }

    @Override
    public void rsi$setInputConsumed(@Nullable BiConsumer<Integer, KeyAmount> listener) {
        rsi$inputConsumed = listener;
    }

    // 只记已经产出成品的一次消耗，原生自动均分不经过这里。
    @WrapOperation(method = "workContent", at = @At(value = "INVOKE", target =
            "Lcom/wintercogs/beyonddimensions/api/storage/handler/impl/StackHandler;extract(IJZ)"
                    + "Lcom/wintercogs/beyonddimensions/api/storage/key/KeyAmount;"))
    private KeyAmount rsi$recordNativeConsumption(StackHandler slots, int slot, long count, boolean simulate,
                                                    Operation<KeyAmount> original) {
        KeyAmount taken = original.call(slots, slot, count, simulate);
        if (rsi$inputConsumed != null && !simulate) rsi$inputConsumed.accept(slot, taken);
        return taken;
    }

    @Inject(method = "workEnd", at = @At("HEAD"))
    private void rsi$beforeNativeOutput(CallbackInfo ci) {
        if (rsi$outputReceiver != null) rsi$outputReceiver.run();
    }
}
