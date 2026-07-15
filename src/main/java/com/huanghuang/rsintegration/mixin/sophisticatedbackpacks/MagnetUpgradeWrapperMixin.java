package com.huanghuang.rsintegration.mixin.sophisticatedbackpacks;

import com.huanghuang.rsintegration.compat.ftbquests.ExternalItemProgressBridge;
import com.huanghuang.rsintegration.util.BackpackRSUtils;
import com.huanghuang.rsintegration.util.ExternalItemProgressSuppression;
import com.huanghuang.rsintegration.util.InsertedStackDelta;
import com.huanghuang.rsintegration.util.RsOperationPlayerContext;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.p3pp3rf1y.sophisticatedcore.api.IStorageWrapper;
import net.p3pp3rf1y.sophisticatedcore.upgrades.ContentsFilterLogic;
import net.p3pp3rf1y.sophisticatedcore.upgrades.IUpgradeWrapper;
import net.p3pp3rf1y.sophisticatedcore.upgrades.UpgradeWrapperBase;
import net.p3pp3rf1y.sophisticatedcore.upgrades.magnet.MagnetUpgradeItem;
import net.p3pp3rf1y.sophisticatedcore.upgrades.magnet.MagnetUpgradeWrapper;
import net.p3pp3rf1y.sophisticatedcore.upgrades.voiding.VoidUpgradeWrapper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Mixin(value = MagnetUpgradeWrapper.class)
public abstract class MagnetUpgradeWrapperMixin
        extends UpgradeWrapperBase<MagnetUpgradeWrapper, MagnetUpgradeItem> {

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
    @Unique
    private boolean rsi$voidUpgrade = false;

    @Unique
    private static volatile Class<?> rsi$debrisClass;
    @Unique
    private static volatile boolean rsi$debrisProbed;
    @Unique
    private ItemStack rsi$magnetInput = ItemStack.EMPTY;

    @Unique
    private static boolean rsi$isDebrisEntity(Entity e) {
        if (!rsi$debrisProbed) {
            rsi$debrisProbed = true;
            try {
                rsi$debrisClass = Class.forName(
                        "fuzs.mutantmonsters.world.entity.MutantSkeletonBodyPart");
            } catch (ClassNotFoundException ex) {
                RSIntegrationMod.LOGGER.debug("[RSI] reflection probe failed", ex);
            }
        }
        return rsi$debrisClass != null && rsi$debrisClass.isInstance(e);
    }

    protected MagnetUpgradeWrapperMixin(IStorageWrapper storageWrapper, ItemStack upgrade,
                                         Consumer<ItemStack> upgradeSaveHandler) {
        super(storageWrapper, upgrade, upgradeSaveHandler);
    }

    @Shadow(remap = false)
    public abstract ContentsFilterLogic getFilterLogic();

    @Shadow(remap = false)
    public abstract boolean shouldPickupItems();

    @Inject(method = "<init>", at = @At(value = "RETURN"), remap = false)
    private void onInit(IStorageWrapper storageWrapper, ItemStack upgrade,
                        Consumer<ItemStack> upgradeSaveHandler, CallbackInfo ci) {
        CompoundTag tag = upgrade.getTag();
        if (tag != null && tag.contains(RS_BLOCK_POS_TAG) && tag.contains(RS_BLOCK_DIMENSION_TAG)) {
            this.rsi$isRs = true;
            this.rsi$rsBlockPos = BlockPos.of(tag.getLong(RS_BLOCK_POS_TAG));
            this.rsi$rsDimensionKey = ResourceKey.create(Registries.DIMENSION,
                    ResourceLocation.parse(tag.getString(RS_BLOCK_DIMENSION_TAG)));
            if (!tag.contains("disabled")) {
                this.rsi$voidUpgrade = true;
            }
        }
    }

    @Inject(method = "tryToInsertItem", at = @At(value = "HEAD"), remap = false, cancellable = true)
    private void tryToInsertItem(ItemEntity itemEntity, CallbackInfoReturnable<Boolean> cir) {
        rsi$magnetInput = itemEntity.getItem().copy();
        ExternalItemProgressSuppression.beginOperation();
        if (!this.rsi$isRs) return;
        UUID backpackUuid = this.storageWrapper.getContentsUuid().orElse(null);
        boolean inserted = BackpackRSUtils.handleRsInsertion(this.getFilterLogic(), itemEntity,
                backpackUuid,
                this.rsi$rsBlockPos, this.rsi$rsDimensionKey,
                rsi$getUpgradesOfType(VoidUpgradeWrapper.class), this.rsi$voidUpgrade);
        ItemStack input = rsi$magnetInput;
        rsi$magnetInput = ItemStack.EMPTY;
        rsi$reportInsertion(input, itemEntity.getItem());
        cir.setReturnValue(inserted);
        cir.cancel();
    }

    @Inject(method = "tryToInsertItem", at = @At("RETURN"), remap = false)
    private void rsi$reportMagnetInsertion(ItemEntity itemEntity, CallbackInfoReturnable<Boolean> cir) {
        ItemStack input = rsi$magnetInput;
        rsi$magnetInput = ItemStack.EMPTY;
        rsi$reportInsertion(input, itemEntity.getItem());
    }

    @Unique
    private static void rsi$reportInsertion(ItemStack input, ItemStack remainder) {
        boolean suppressed = ExternalItemProgressSuppression.consume();
        ServerPlayer player = RsOperationPlayerContext.current();
        if (suppressed || player == null || input.isEmpty()) return;
        ExternalItemProgressBridge.enqueue(player,
                InsertedStackDelta.between(input, remainder));
    }

    @WrapOperation(
            method = "tick",
            at = @At(value = "INVOKE",
                    target = "Lnet/p3pp3rf1y/sophisticatedcore/upgrades/magnet/MagnetUpgradeWrapper;pickupItems(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;)I"),
            remap = false)
    private int rsi$withPlayerContext(MagnetUpgradeWrapper instance, Entity entity,
                                      Level level, BlockPos pos, Operation<Integer> original) {
        if (!(entity instanceof ServerPlayer player)) return original.call(instance, entity, level, pos);
        try (RsOperationPlayerContext.Scope ignored = RsOperationPlayerContext.push(player)) {
            return original.call(instance, entity, level, pos);
        }
    }

    @Inject(method = "pickup", at = @At(value = "HEAD"), remap = false, cancellable = true)
    private void pickup(Level world, ItemStack stack, boolean simulate,
                        CallbackInfoReturnable<ItemStack> cir) {
        if (!this.rsi$isRs) return;
        UUID backpackUuid = this.storageWrapper.getContentsUuid().orElse(null);
        cir.setReturnValue(BackpackRSUtils.handleRSPickup(this.getFilterLogic(), world, stack, simulate,
                backpackUuid,
                this.rsi$rsBlockPos, this.rsi$rsDimensionKey,
                rsi$getUpgradesOfType(VoidUpgradeWrapper.class), this.rsi$voidUpgrade));
        cir.cancel();
    }

    @Inject(method = "tick", at = @At(value = "RETURN"), remap = false)
    private void rsi$onTick(Entity entity, Level level, BlockPos pos, CallbackInfo ci) {
        if (!(entity instanceof Player player)) return;
        if (!this.shouldPickupItems()) return;
        int radius = this.upgradeItem.getRadius();
        AABB area = new AABB(pos).inflate(radius);
        for (Entity e : level.getEntitiesOfClass(Entity.class, area, e -> e.isAlive()
                && rsi$isDebrisEntity(e))) {
            e.interact(player, InteractionHand.MAIN_HAND);
        }
    }

    @Unique
    protected <U extends IUpgradeWrapper> List<U> rsi$getUpgradesOfType(Class<U> upgradeClass) {
        return this.storageWrapper.getUpgradeHandler().getWrappersThatImplement(upgradeClass)
                .stream().filter(IUpgradeWrapper::isEnabled).collect(Collectors.toList());
    }
}
