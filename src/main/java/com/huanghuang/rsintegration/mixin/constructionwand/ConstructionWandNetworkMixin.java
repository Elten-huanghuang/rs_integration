package com.huanghuang.rsintegration.mixin.constructionwand;

import com.huanghuang.rsintegration.crafting.CraftStorageEndpoint;
import com.huanghuang.rsintegration.storage.StorageRestockSupport;
import com.huanghuang.rsintegration.storage.StoragePermission;
import com.huanghuang.rsintegration.storage.StorageSnapshotResult;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;
import java.util.Set;

/** Supplies Construction Wand from the currently authenticated RS/BD network. */
@Pseudo
@Mixin(targets = "thetadev.constructionwand.basics.WandUtil", remap = false)
public abstract class ConstructionWandNetworkMixin {
    @Inject(method = "countItem", at = @At("RETURN"), cancellable = true, require = 0)
    private static void rsi$countNetwork(Player player, Item item,
                                          CallbackInfoReturnable<Integer> callback) {
        int localCount = callback.getReturnValue();
        if (localCount == Integer.MAX_VALUE || item == null
                || !(player instanceof ServerPlayer serverPlayer)) return;

        Optional<CraftStorageEndpoint> endpoint = resolve(serverPlayer);
        if (endpoint.isEmpty()) return;

        ItemStack template = new ItemStack(item);
        try {
            CraftStorageEndpoint storage = endpoint.get();
            if (!storage.session().hasPermission(serverPlayer, StoragePermission.EXTRACT)) return;
            StorageSnapshotResult snapshot = storage.snapshot(serverPlayer, Set.of(item));
            if (!snapshot.successful() || snapshot.snapshot().isEmpty()) return;
            long networkCount = snapshot.snapshot().orElseThrow()
                    .countExact(storage.session().itemKey(template));
            callback.setReturnValue(saturatedAdd(localCount, networkCount));
        } catch (RuntimeException | LinkageError ignored) {
            // Optional storage integrations must never make wand usage fail.
        }
    }

    private static Optional<CraftStorageEndpoint> resolve(ServerPlayer player) {
        try {
            return StorageRestockSupport.resolve(player);
        } catch (RuntimeException | LinkageError ignored) {
            return Optional.empty();
        }
    }

    private static int saturatedAdd(int localCount, long networkCount) {
        if (networkCount <= 0) return localCount;
        return networkCount >= Integer.MAX_VALUE - (long) localCount
                ? Integer.MAX_VALUE : localCount + (int) networkCount;
    }
}
