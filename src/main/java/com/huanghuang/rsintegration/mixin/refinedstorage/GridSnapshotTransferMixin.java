package com.huanghuang.rsintegration.mixin.refinedstorage;

import com.huanghuang.rsintegration.config.RSStorageConfig;
import com.huanghuang.rsintegration.storage.rs.GridTransferPolicy;
import com.refinedmods.refinedstorage.network.PacketSplitter;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

@Mixin(value = PacketSplitter.class, remap = false)
public abstract class GridSnapshotTransferMixin {
    @ModifyVariable(method = "registerMessage(IILjava/lang/Class;Ljava/util/function/BiConsumer;Ljava/util/function/Function;Ljava/util/function/BiConsumer;)V",
            at = @At("HEAD"), argsOnly = true, ordinal = 1)
    private int rsi$configureSnapshotLimit(int original, int id, int maximum, Class<?> messageClass,
                                         BiConsumer<?, FriendlyByteBuf> encoder,
                                         Function<FriendlyByteBuf, ?> decoder,
                                         BiConsumer<?, Supplier<NetworkEvent.Context>> handler) {
        return GridTransferPolicy.limit(messageClass.getName(), original,
                RSStorageConfig.enabled(RSStorageConfig.EXPAND_GRID_TRANSFER), RSStorageConfig.transferParts());
    }
}
