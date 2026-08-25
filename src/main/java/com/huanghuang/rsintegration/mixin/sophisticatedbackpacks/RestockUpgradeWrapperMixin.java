package com.huanghuang.rsintegration.mixin.sophisticatedbackpacks;

import com.huanghuang.rsintegration.mods.sophisticatedbackpacks.StorageBackpackUtils;
import com.huanghuang.rsintegration.storage.StorageReference;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraftforge.items.IItemHandler;
import net.p3pp3rf1y.sophisticatedbackpacks.upgrades.restock.RestockUpgradeItem;
import net.p3pp3rf1y.sophisticatedbackpacks.upgrades.restock.RestockUpgradeWrapper;
import net.p3pp3rf1y.sophisticatedcore.api.IStorageWrapper;
import net.p3pp3rf1y.sophisticatedcore.upgrades.ContentsFilterLogic;
import net.p3pp3rf1y.sophisticatedcore.upgrades.UpgradeWrapperBase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.function.Consumer;

@Mixin(value = RestockUpgradeWrapper.class)
public abstract class RestockUpgradeWrapperMixin
        extends UpgradeWrapperBase<RestockUpgradeWrapper, RestockUpgradeItem> {

    @Unique
    private StorageReference rsi$storageReference;

    @Unique
    private static volatile boolean rsi$bcChecked;
    @Unique
    private static volatile boolean rsi$bcLoaded;
    @Unique
    private static volatile java.lang.reflect.Method rsi$isTwoHandedMethod;

    protected RestockUpgradeWrapperMixin(IStorageWrapper storageWrapper, ItemStack upgrade,
                                         Consumer<ItemStack> upgradeSaveHandler) {
        super(storageWrapper, upgrade, upgradeSaveHandler);
    }

    @Shadow(remap = false)
    public abstract ContentsFilterLogic getFilterLogic();

    @Inject(method = "<init>", at = @At(value = "RETURN"), remap = false)
    private void onInit(IStorageWrapper storageWrapper, ItemStack upgrade,
                        Consumer<ItemStack> upgradeSaveHandler, CallbackInfo ci) {
        CompoundTag tag = upgrade.getTag();
        this.rsi$storageReference = StorageBackpackUtils.readReference(tag);
    }

    @Inject(method = "onHandlerInteract", at = @At(value = "HEAD"), remap = false, cancellable = true)
    private void onHandlerInteract(IItemHandler handler, Player player, CallbackInfo ci) {
        if (this.rsi$storageReference == null) {
            if (rsi$checkTwoHanded(player)) {
                ci.cancel();
            }
            return;
        }

        List<ItemStack> restocked = player instanceof net.minecraft.server.level.ServerPlayer serverPlayer
                ? StorageBackpackUtils.handleRestock(getFilterLogic(), this.storageWrapper,
                serverPlayer, this.rsi$storageReference)
                : List.of();

        String key = restocked.isEmpty()
                ? "gui.sophisticatedbackpacks.status.nothing_to_restock"
                : "gui.sophisticatedbackpacks.status.stacks_restocked";
        player.displayClientMessage(
                Component.translatable(key, restocked.size()), true);
        ci.cancel();
    }

    @Unique
    private static boolean rsi$checkTwoHanded(Player player) {
        if (!rsi$bcChecked) {
            rsi$bcChecked = true;
            try {
                Class<?> cls = Class.forName("net.bettercombat.logic.PlayerAttackHelper");
                rsi$isTwoHandedMethod = cls.getMethod("isTwoHandedWielding", Player.class);
                rsi$bcLoaded = true;
            } catch (Exception e) {
                rsi$bcLoaded = false;
            }
        }
        if (!rsi$bcLoaded) return false;
        try {
            return (boolean) rsi$isTwoHandedMethod.invoke(null, player);
        } catch (Exception e) {
            return false;
        }
    }
}
