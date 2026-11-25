package com.huanghuang.rsintegration.mixin.constructionwand;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.CraftItemRecovery;
import com.huanghuang.rsintegration.crafting.endpoint.CraftStorageEndpoint;
import com.huanghuang.rsintegration.storage.StorageOperationResult;
import com.huanghuang.rsintegration.storage.StorageRestockSupport;
import com.huanghuang.rsintegration.util.PlayerUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.Optional;

/** Uses the authenticated RS/BD network for the wand's final item deduction. */
@Pseudo
@Mixin(targets = "thetadev.constructionwand.wand.supplier.SupplierInventory", remap = false)
public abstract class SupplierInventoryNetworkMixin {
    @Shadow @Final protected Player player;

    @Inject(method = "takeItemStack", at = @At("RETURN"), cancellable = true, require = 0)
    private void rsi$takeFromNetwork(ItemStack requested,
                                      CallbackInfoReturnable<Integer> callback) {
        int remaining = callback.getReturnValue();
        if (remaining <= 0 || requested == null || requested.isEmpty()
                || !(player instanceof ServerPlayer serverPlayer)) return;

        Optional<CraftStorageEndpoint> endpoint = resolve(serverPlayer);
        if (endpoint.isEmpty()) return;
        try {
            CraftStorageEndpoint storage = endpoint.get();
            StorageOperationResult result = storage.extractExact(
                    serverPlayer, requested.copyWithCount(remaining), remaining, false);
            refund(storage, serverPlayer, result.recoveryStacks());
            result.transferredAmount().ifPresent(transferred -> callback.setReturnValue(
                    Math.max(0, remaining - (int) Math.min(Integer.MAX_VALUE, transferred))));
        } catch (RuntimeException | LinkageError ignored) {
            // 可选存储后端不可用时，保留原始余量。
        }
    }

    private static Optional<CraftStorageEndpoint> resolve(ServerPlayer player) {
        try {
            return StorageRestockSupport.resolve(player);
        } catch (RuntimeException | LinkageError ignored) {
            return Optional.empty();
        }
    }

    private static void refund(CraftStorageEndpoint endpoint, ServerPlayer player,
                               List<ItemStack> stacks) {
        for (ItemStack stack : stacks) {
            if (stack == null || stack.isEmpty()) continue;
            try {
                var result = CraftStorageEndpoint.insertInternal(endpoint, player, stack, false);
                var remainder = result.remainder();
                if (remainder.isEmpty()) {
                    RSIntegrationMod.LOGGER.error("[RSI-Wand] 退料写回结果不确定，不重复返还：{}（{}）",
                            stack, result.diagnosticCode());
                    continue;
                }
                ItemStack rejected = remainder.orElseThrow();
                if (rejected.isEmpty()) continue;

                CraftItemRecovery recovery = CraftItemRecovery.get(player.server);
                var owner = player.getUUID();
                boolean alreadyPending = recovery.hasPending(owner);
                recovery.retain(owner, rejected);
                if (!alreadyPending) {
                    PlayerUtils.safeSendMessage(player, Component.translatable("rsi.item_recovery.pending"));
                }
            } catch (RuntimeException | LinkageError failure) {
                RSIntegrationMod.LOGGER.error("[RSI-Wand] 退料处理失败，不重复返还：" + stack, failure);
            }
        }
    }
}
