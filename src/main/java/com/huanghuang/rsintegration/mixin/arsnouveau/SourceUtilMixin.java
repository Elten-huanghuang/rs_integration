package com.huanghuang.rsintegration.mixin.arsnouveau;

import com.hollingsworth.arsnouveau.api.source.ISourceTile;
import com.hollingsworth.arsnouveau.api.source.ISpecialSourceProvider;
import com.hollingsworth.arsnouveau.api.source.SourceProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * 让 Ars 的近邻 Source 扫描遵守公开的 ISourceTile 接口。
 *
 * <p>Ars 4.12.6 的 SourceUtil 内部把方块实体硬编码成 SourceJarTile，
 * 导致其他合法 ISourceTile 永远不会进入原生搬运路径。这里替换三个 lambda
 * 扫描分支，保留 SourceUtil 的半径、加载区块和 SourceProvider 语义。</p>
 */
@Mixin(targets = "com.hollingsworth.arsnouveau.api.util.SourceUtil", remap = false)
public abstract class SourceUtilMixin {
    @Inject(method = "lambda$canTakeSource$1", at = @At("HEAD"), cancellable = true, require = 1)
    private static void rsi$scanSourceTiles(Level level, List<ISpecialSourceProvider> result,
                                             BlockPos pos, CallbackInfo callback) {
        if (!level.isLoaded(pos)) {
            callback.cancel();
            return;
        }
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity instanceof ISourceTile tile && tile.getSource() > 0) {
            result.add(new SourceProvider(tile, pos.immutable()));
        }
        callback.cancel();
    }

    @Inject(method = "lambda$canGiveSource$0", at = @At("HEAD"), cancellable = true, require = 1)
    private static void rsi$scanAcceptingSourceTiles(Level level, List<ISpecialSourceProvider> result,
                                                       BlockPos pos, CallbackInfo callback) {
        if (!level.isLoaded(pos)) {
            callback.cancel();
            return;
        }
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity instanceof ISourceTile tile && tile.canAcceptSource()) {
            result.add(new SourceProvider(tile, pos.immutable()));
        }
        callback.cancel();
    }

    @Inject(method = "lambda$hasSourceNearby$2", at = @At("HEAD"), cancellable = true, require = 1)
    private static void rsi$hasSourceTile(Level level, int required, BlockPos pos,
                                           CallbackInfoReturnable<Boolean> callback) {
        BlockEntity blockEntity = level.getBlockEntity(pos);
        callback.setReturnValue(blockEntity instanceof ISourceTile tile
                && tile.getSource() >= required);
    }
}
