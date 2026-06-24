package com.huanghuang.rsintegration.mixin.sophisticatedbackpacks;

import com.huanghuang.rsintegration.util.BackpackRSUtils;
import com.refinedmods.refinedstorage.api.network.INetwork;
import com.refinedmods.refinedstorage.api.storage.cache.IStorageCache;
import com.refinedmods.refinedstorage.api.util.Action;
import com.refinedmods.refinedstorage.api.util.IComparer;
import com.refinedmods.refinedstorage.api.util.StackListEntry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.p3pp3rf1y.sophisticatedcore.api.IStorageWrapper;
import net.p3pp3rf1y.sophisticatedcore.upgrades.FilterLogic;
import net.p3pp3rf1y.sophisticatedcore.upgrades.UpgradeWrapperBase;
import net.p3pp3rf1y.sophisticatedcore.upgrades.feeding.FeedingUpgradeItem;
import net.p3pp3rf1y.sophisticatedcore.upgrades.feeding.FeedingUpgradeWrapper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.Consumer;

@Mixin(value = FeedingUpgradeWrapper.class)
public abstract class FeedingUpgradeWrapperMixin
        extends UpgradeWrapperBase<FeedingUpgradeWrapper, FeedingUpgradeItem> {

    @Unique
    private static final String RS_BLOCK_POS_TAG = "RSBlockPos";
    @Unique
    private static final String RS_BLOCK_DIMENSION_TAG = "RSBlockDimension";

    @Unique
    private boolean rsi$isRs;
    @Unique
    private BlockPos rsi$rsBlockPos;
    @Unique
    private ResourceKey<Level> rsi$rsDimensionKey;

    protected FeedingUpgradeWrapperMixin(IStorageWrapper storageWrapper, ItemStack upgrade,
                                         Consumer<ItemStack> upgradeSaveHandler) {
        super(storageWrapper, upgrade, upgradeSaveHandler);
    }

    @Shadow(remap = false)
    public abstract FilterLogic getFilterLogic();

    @Inject(method = "<init>", at = @At(value = "RETURN"), remap = false)
    private void onInit(IStorageWrapper storageWrapper, ItemStack upgrade,
                        Consumer<ItemStack> upgradeSaveHandler, CallbackInfo ci) {
        CompoundTag tag = upgrade.getTag();
        if (tag != null && tag.contains(RS_BLOCK_POS_TAG) && tag.contains(RS_BLOCK_DIMENSION_TAG)) {
            this.rsi$isRs = true;
            this.rsi$rsBlockPos = BlockPos.of(tag.getLong(RS_BLOCK_POS_TAG));
            this.rsi$rsDimensionKey = ResourceKey.create(Registries.DIMENSION,
                    ResourceLocation.parse(tag.getString(RS_BLOCK_DIMENSION_TAG)));
        }
    }

    @Inject(method = "tryFeedingFoodFromStorage", at = @At(value = "HEAD"),
            remap = false, cancellable = true)
    private void onTryFeedingFoodFromStorage(Level level, int missingFood, Player player,
                                             CallbackInfoReturnable<Boolean> cir) {
        if (!this.rsi$isRs) return;

        INetwork network = BackpackRSUtils.getNetwork(level, this.rsi$rsBlockPos, this.rsi$rsDimensionKey);
        if (network == null) {
            cir.setReturnValue(false);
            cir.cancel();
            return;
        }

        IStorageCache<ItemStack> cache = network.getItemStorageCache();
        if (cache == null) {
            cir.setReturnValue(false);
            cir.cancel();
            return;
        }

        FilterLogic filter = getFilterLogic();
        if (filter == null) {
            cir.setReturnValue(false);
            cir.cancel();
            return;
        }

        for (StackListEntry<ItemStack> cacheEntry : cache.getList().getStacks()) {
            ItemStack rsStack = cacheEntry.getStack();
            if (rsStack.isEmpty()) continue;
            if (!rsStack.isEdible()) continue;
            if (!filter.matchesFilter(rsStack)) continue;

            FoodProperties foodProps = rsStack.getItem().getFoodProperties(rsStack, player);
            if (foodProps == null) continue;

            int foodValue = foodProps.getNutrition();
            if (!rsi$isHungryEnough(missingFood, foodValue)) continue;

            ItemStack extracted = network.extractItem(rsStack.copy(), 1,
                    IComparer.COMPARE_NBT, Action.PERFORM);
            if (extracted.isEmpty()) continue;

            player.getFoodData().eat(foodProps.getNutrition(), foodProps.getSaturationModifier());

            ItemStack remainder = extracted.getCraftingRemainingItem();
            if (!remainder.isEmpty()) {
                network.insertItem(remainder, remainder.getCount(), Action.PERFORM);
            }

            cir.setReturnValue(true);
            cir.cancel();
            return;
        }

        cir.setReturnValue(false);
        cir.cancel();
    }

    @Unique
    private static boolean rsi$isHungryEnough(int missingFood, int foodValue) {
        return foodValue <= missingFood;
    }
}
