package com.huanghuang.rsintegration.mixin.malum;
import java.lang.reflect.Method;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.resonance.disk.ResonanceDiskAbilities;
import com.huanghuang.rsintegration.resonance.disk.ResonanceDiskAbilityService;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import com.huanghuang.rsintegration.resonance.bd.BDResonanceDiskAccess;
import com.huanghuang.rsintegration.resonance.bd.BDResonanceDiskData;
import net.minecraft.world.item.Item;

@Mixin(value = com.sammy.malum.common.block.curiosities.weeping_well.VoidConduitBlockEntity.class,
        remap = false)
public class VoidConduitBlockEntityMixin {

    @Inject(method = "spitOutItem", at = @At("HEAD"))
    private void rsi$unlockResonanceDisk(ItemStack stack,
                                         CallbackInfoReturnable<Item> cir) {
        var level = ((BlockEntity) (Object) this).getLevel();
        if (!(level instanceof ServerLevel serverLevel)) return;
        ResonanceDiskAbilityService.UnlockResult result =
                ResonanceDiskAbilityService.unlockDiskStack(
                        serverLevel, stack, ResonanceDiskAbilities.MALUM_VOID_FAVOR);
        if (result == ResonanceDiskAbilityService.UnlockResult.UNLOCKED) {
            RSIntegrationMod.LOGGER.info(
                    "[RSI-Resonance] Resonance disk unlocked Malum void-favor crafting at {}",
                    ((BlockEntity) (Object) this).getBlockPos());
            if (BDResonanceDiskAccess
                    .isDisk(stack)) {
                notifyBdOwner(serverLevel, stack);
            }
        }
    }

    private void notifyBdOwner(ServerLevel level, ItemStack stack) {
        ServerPlayer recipient = null;
        var diskId = BDResonanceDiskAccess
                .getDiskId(stack);
        if (diskId != null) {
            var record = BDResonanceDiskData
                    .get(level.getServer()).find(diskId);
            if (record != null && record.owner() != null) {
                recipient = level.getServer().getPlayerList().getPlayer(record.owner());
            }
        }
        if (recipient == null) {
            BlockEntity conduit = (BlockEntity) (Object) this;
            var nearby = level.getNearestPlayer(conduit.getBlockPos().getX() + 0.5D,
                    conduit.getBlockPos().getY() + 0.5D,
                    conduit.getBlockPos().getZ() + 0.5D, 16.0D, false);
            if (nearby instanceof ServerPlayer serverPlayer) recipient = serverPlayer;
        }
        if (recipient != null) {
            recipient.sendSystemMessage(Component.translatable(
                    "rsi.resonance.void_favor_unlocked"));
        }
    }
}
