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

import java.util.ArrayList;
import java.util.List;

/** 让 Ars RelaySplitter 的多目标转发同样接受任意公开 ISourceTile。 */
@Mixin(targets = "com.hollingsworth.arsnouveau.common.block.tile.RelaySplitterTile", remap = false)
public abstract class RelaySplitterSourceInteropMixin {
    @Shadow private ArrayList<BlockPos> toList;
    @Shadow private ArrayList<BlockPos> fromList;

    @Shadow public abstract void createParticles(BlockPos from, BlockPos to);

    @Inject(method = "processFromList", at = @At("HEAD"), cancellable = true, require = 1)
    private void rsi$processExternalSources(CallbackInfo callback) {
        Level level = rsi$level();
        if (level == null || !rsi$containsExternal(level, fromList)) return;
        List<BlockPos> invalid = new ArrayList<>();
        int share = fromList.isEmpty() ? 0 : rsi$self().getTransferRate() / fromList.size();
        for (BlockPos pos : fromList) {
            if (!level.hasChunkAt(pos)) continue;
            BlockEntity entity = level.getBlockEntity(pos);
            if (!(entity instanceof ISourceTile source)
                    || entity instanceof AbstractSourceMachine) {
                // 原生 Ars 源交给原版 processFromList 处理；这里只处理共振罐。
                if (entity instanceof AbstractSourceMachine) continue;
                invalid.add(pos);
                continue;
            }
            ISourceTile self = (ISourceTile) (Object) this;
            int amount = Math.min(share, rsi$self().getTransferRate(source, self));
            if (rsi$self().transferSource(source, self, amount) > 0) {
                createParticles(pos, rsi$pos());
            }
        }
        invalid.forEach(fromList::remove);
        callback.cancel();
    }

    @Inject(method = "processToList", at = @At("HEAD"), cancellable = true, require = 1)
    private void rsi$processExternalTargets(CallbackInfo callback) {
        Level level = rsi$level();
        if (level == null || !rsi$containsExternal(level, toList)) return;
        List<BlockPos> invalid = new ArrayList<>();
        int share = toList.isEmpty() ? 0 : rsi$self().getSource() / toList.size();
        for (BlockPos pos : toList) {
            if (!level.hasChunkAt(pos)) continue;
            BlockEntity entity = level.getBlockEntity(pos);
            if (!(entity instanceof ISourceTile target)
                    || entity instanceof AbstractSourceMachine) {
                if (entity instanceof AbstractSourceMachine) continue;
                invalid.add(pos);
                continue;
            }
            if (rsi$self().transferSource(rsi$self(), target, share) > 0) {
                createParticles(rsi$pos(), pos);
            }
        }
        invalid.forEach(toList::remove);
        callback.cancel();
    }

    private boolean rsi$containsExternal(Level level, List<BlockPos> positions) {
        for (BlockPos pos : positions) {
            if (!level.hasChunkAt(pos)) continue;
            BlockEntity entity = level.getBlockEntity(pos);
            if (entity instanceof ISourceTile && !(entity instanceof AbstractSourceMachine)) return true;
        }
        return false;
    }

    private Level rsi$level() {
        return ((BlockEntity) (Object) this).getLevel();
    }

    private BlockPos rsi$pos() {
        return ((BlockEntity) (Object) this).getBlockPos();
    }

    private AbstractSourceMachine rsi$self() {
        return (AbstractSourceMachine) (Object) this;
    }
}
