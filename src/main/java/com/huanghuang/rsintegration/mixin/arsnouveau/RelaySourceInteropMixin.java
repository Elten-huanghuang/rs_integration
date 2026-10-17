package com.huanghuang.rsintegration.mixin.arsnouveau;

import com.hollingsworth.arsnouveau.api.source.AbstractSourceMachine;
import com.hollingsworth.arsnouveau.api.source.ISourceTile;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 让 Ars Relay 的定点连接和转发遵守公开的 ISourceTile 接口。
 *
 * <p>Ars 4.12.6 的 RelayTile 连接校验和 tick 路径把目标硬编码成
 * AbstractSourceMachine。共振魔源罐不能继承 Ars 的方块实体基类，因此这里只在
 * 检测到非 AbstractSourceMachine 的 ISourceTile 时接管该次操作，其他原生路径
 * 继续由 Ars 自己执行。</p>
 */
@Mixin(targets = "com.hollingsworth.arsnouveau.common.block.tile.RelayTile", remap = false)
public abstract class RelaySourceInteropMixin {
    @Shadow private BlockPos toPos;
    @Shadow private BlockPos fromPos;
    @Shadow public boolean disabled;

    @Shadow public abstract boolean closeEnough(BlockPos pos);
    @Shadow public abstract boolean setTakeFrom(BlockPos pos);
    @Shadow public abstract boolean setSendTo(BlockPos pos);

    @Inject(method = "setSendTo", at = @At("HEAD"), cancellable = true, require = 1)
    private void rsi$allowSourceTileTarget(BlockPos pos, CallbackInfoReturnable<Boolean> callback) {
        Level level = rsi$level();
        if (level == null || !rsi$isExternalSourceTile(level, pos)) return;
        if (!closeEnough(pos)) {
            callback.setReturnValue(false);
            return;
        }
        toPos = pos.immutable();
        rsi$updateBlock();
        callback.setReturnValue(true);
    }

    @Inject(method = "onFinishedConnectionFirst", at = @At("HEAD"), cancellable = true,
            require = 1)
    private void rsi$finishSourceTileOutput(BlockPos pos, net.minecraft.world.entity.LivingEntity entity,
                                            net.minecraft.world.entity.player.Player player,
                                            CallbackInfo callback) {
        Level level = rsi$level();
        if (level == null || level.isClientSide || pos == null
                || !rsi$isExternalSourceTile(level, pos)) return;
        if (setSendTo(pos)) callback.cancel();
    }

    @Inject(method = "onFinishedConnectionLast", at = @At("HEAD"), cancellable = true,
            require = 1)
    private void rsi$finishSourceTileInput(BlockPos pos, net.minecraft.world.entity.LivingEntity entity,
                                           net.minecraft.world.entity.player.Player player,
                                           CallbackInfo callback) {
        Level level = rsi$level();
        if (level == null || level.isClientSide || pos == null
                || !rsi$isExternalSourceTile(level, pos)) return;
        if (setTakeFrom(pos)) callback.cancel();
    }

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true, require = 1)
    private void rsi$transferSourceTiles(CallbackInfo callback) {
        Level level = rsi$level();
        if (level == null || level.isClientSide || disabled || level.getGameTime() % 20 != 0) return;

        boolean externalFrom = rsi$isExternalSourceTile(level, fromPos);
        boolean externalTo = rsi$isExternalSourceTile(level, toPos);
        if (!externalFrom && !externalTo) return;

        if (fromPos != null && level.hasChunkAt(fromPos)) {
            BlockEntity raw = level.getBlockEntity(fromPos);
            if (raw instanceof ISourceTile source) {
                if (transferSource(source, (ISourceTile) (Object) this) > 0) rsi$updateBlock();
            } else {
                fromPos = null;
                rsi$updateBlock();
                callback.cancel();
                return;
            }
        }

        if (toPos != null && level.hasChunkAt(toPos)) {
            BlockEntity raw = level.getBlockEntity(toPos);
            if (raw instanceof ISourceTile target) {
                if (transferSource((ISourceTile) (Object) this, target) > 0) rsi$updateBlock();
            } else {
                toPos = null;
                rsi$updateBlock();
            }
        }
        callback.cancel();
    }

    private boolean rsi$isExternalSourceTile(Level level, BlockPos pos) {
        if (pos == null || !level.hasChunkAt(pos)) return false;
        BlockEntity entity = level.getBlockEntity(pos);
        return entity instanceof ISourceTile && !(entity instanceof AbstractSourceMachine);
    }

    private Level rsi$level() {
        return ((BlockEntity) (Object) this).getLevel();
    }

    private void rsi$updateBlock() {
        ((BlockEntity) (Object) this).setChanged();
    }

    private int transferSource(ISourceTile source, ISourceTile target) {
        // Relay 本身继承了 Ars 的转移算法；该算法只依赖 ISourceTile，
        // 因此可以安全地把共振罐作为源或目标传入。
        return ((AbstractSourceMachine) (Object) this).transferSource(source, target);
    }
}
