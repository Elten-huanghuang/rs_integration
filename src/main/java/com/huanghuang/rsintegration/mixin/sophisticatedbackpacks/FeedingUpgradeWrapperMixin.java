package com.huanghuang.rsintegration.mixin.sophisticatedbackpacks;
import com.huanghuang.rsintegration.mods.sophisticatedbackpacks.StorageBackpackUtils;
import com.huanghuang.rsintegration.storage.StorageOperationResult;
import com.huanghuang.rsintegration.storage.StorageOperationStatus;
import com.huanghuang.rsintegration.storage.StorageSession;
import com.huanghuang.rsintegration.storage.StorageReference;
import com.huanghuang.rsintegration.storage.StorageSnapshotResult;
import com.huanghuang.rsintegration.storage.StoredItem;
import com.huanghuang.rsintegration.util.RSFeedingPolicy;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.ItemHandlerHelper;
import net.minecraftforge.event.ForgeEventFactory;
import net.minecraft.world.level.Level;
import net.p3pp3rf1y.sophisticatedcore.api.IStorageWrapper;
import net.p3pp3rf1y.sophisticatedcore.upgrades.FilterLogic;
import net.p3pp3rf1y.sophisticatedcore.upgrades.UpgradeWrapperBase;
import net.p3pp3rf1y.sophisticatedcore.upgrades.feeding.FeedingUpgradeItem;
import net.p3pp3rf1y.sophisticatedcore.upgrades.feeding.FeedingUpgradeWrapper;
import net.p3pp3rf1y.sophisticatedcore.upgrades.feeding.HungerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import net.minecraft.server.level.ServerPlayer;

import java.util.function.Consumer;

@Mixin(value = FeedingUpgradeWrapper.class)
public abstract class FeedingUpgradeWrapperMixin
        extends UpgradeWrapperBase<FeedingUpgradeWrapper, FeedingUpgradeItem> {

    @Unique
    private StorageReference rsi$storageReference;

    protected FeedingUpgradeWrapperMixin(IStorageWrapper storageWrapper, ItemStack upgrade,
                                         Consumer<ItemStack> upgradeSaveHandler) {
        super(storageWrapper, upgrade, upgradeSaveHandler);
    }

    @Shadow(remap = false)
    public abstract FilterLogic getFilterLogic();

    @Shadow(remap = false)
    public abstract HungerLevel getFeedAtHungerLevel();

    @Shadow(remap = false)
    public abstract boolean shouldFeedImmediatelyWhenHurt();

    @Inject(method = "<init>", at = @At(value = "RETURN"), remap = false)
    private void onInit(IStorageWrapper storageWrapper, ItemStack upgrade,
                        Consumer<ItemStack> upgradeSaveHandler, CallbackInfo ci) {
        CompoundTag tag = upgrade.getTag();
        this.rsi$storageReference = StorageBackpackUtils.readReference(tag);
    }

    @Inject(method = "tryFeedingFoodFromStorage", at = @At(value = "HEAD"),
            remap = false, cancellable = true)
    private void onTryFeedingFoodFromStorage(Level level, int missingFood, Player player,
                                             CallbackInfoReturnable<Boolean> cir) {
        if (this.rsi$storageReference == null) return;

        if (!(player instanceof ServerPlayer serverPlayer)) {
            cir.setReturnValue(false);
            cir.cancel();
            return;
        }
        StorageSession session = StorageBackpackUtils.resolve(serverPlayer, this.rsi$storageReference);
        if (session == null) {
            cir.setReturnValue(false);
            cir.cancel();
            return;
        }
        StorageSnapshotResult snapshotResult = session.snapshotItems(serverPlayer);
        if (!snapshotResult.successful()) {
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

        for (StoredItem storedItem : snapshotResult.snapshot().orElseThrow().items()) {
            ItemStack rsStack = storedItem.stack();
            if (rsStack.isEmpty()) continue;
            if (!rsStack.isEdible()) continue;
            if (!filter.matchesFilter(rsStack)) continue;

            FoodProperties foodProps = rsStack.getItem().getFoodProperties(rsStack, player);
            if (foodProps == null || foodProps.getNutrition() <= 0) continue;

            int foodValue = foodProps.getNutrition();
            boolean hurt = player.getHealth() < player.getMaxHealth() - 0.1F;
            RSFeedingPolicy.HungerRule hungerRule = RSFeedingPolicy.HungerRule.valueOf(
                    getFeedAtHungerLevel().name());
            if (!RSFeedingPolicy.canFeed(hungerRule, missingFood, foodValue,
                    hurt, shouldFeedImmediatelyWhenHurt())) continue;

            StorageOperationResult extraction = StorageBackpackUtils.extractExact(
                    session, serverPlayer, rsStack, 1, false);
            if (extraction == null || extraction.status() == StorageOperationStatus.FAILED
                    || extraction.status() == StorageOperationStatus.INVALID_RESPONSE
                    || extraction.extractedStacks().isEmpty()) continue;
            ItemStack extracted = extraction.extractedStacks().get(0);

            ItemStack food = extracted.copyWithCount(1);
            ItemStack remainder = ItemStack.EMPTY;
            try {
                // 直接完成食用，避免调用 use() 模拟右键，从而误触玩家手上的其他物品。
                ItemStack consumedSnapshot = food.copy();
                ItemStack finished = food.getItem().finishUsingItem(food, level, player);
                remainder = ForgeEventFactory.onItemUseFinish(player, consumedSnapshot, 0, finished);
            } catch (RuntimeException ex) {
                StorageBackpackUtils.returnAfterLocalFailure(session, serverPlayer, food.copy());
                continue;
            }

            if (!remainder.isEmpty()) {
                StorageOperationResult returned = session.insert(serverPlayer, remainder, false);
                if (returned.remainder().isPresent() && !returned.remainder().orElseThrow().isEmpty())
                    ItemHandlerHelper.giveItemToPlayer(player, returned.remainder().orElseThrow());
            }

            cir.setReturnValue(true);
            cir.cancel();
            return;
        }

        cir.setReturnValue(false);
        cir.cancel();
    }
}
