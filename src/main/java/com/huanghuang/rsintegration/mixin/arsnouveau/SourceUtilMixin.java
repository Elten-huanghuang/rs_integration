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
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * 让 Ars 的公开 SourceUtil 查询遵守 ISourceTile 公共接口。
 *
 * <p>Ars 4.12.6 的内置扫描实现把方块实体硬编码成 SourceJarTile。这里在
 * 公开查询方法返回前补入附近的任意 ISourceTile，保留 Ars 原生扫描、半径和
 * SourceManager 行为，不依赖编译器生成的 lambda 方法名。</p>
 */
@Mixin(targets = "com.hollingsworth.arsnouveau.api.util.SourceUtil", remap = false)
public abstract class SourceUtilMixin {
    @Inject(method = "canTakeSource", at = @At("RETURN"), require = 1)
    private static void rsi$appendSourceTiles(BlockPos origin, Level level, int range,
                                               CallbackInfoReturnable<List<ISpecialSourceProvider>> callback) {
        appendSourceTiles(origin, level, range, callback.getReturnValue(), false);
    }

    @Inject(method = "canGiveSource", at = @At("RETURN"), require = 1)
    private static void rsi$appendAcceptingSourceTiles(
            BlockPos origin, Level level, int range,
            CallbackInfoReturnable<List<ISpecialSourceProvider>> callback) {
        appendSourceTiles(origin, level, range, callback.getReturnValue(), true);
    }

    @Inject(method = "hasSourceNearby", at = @At("RETURN"), cancellable = true, require = 1)
    private static void rsi$includeSourceTiles(BlockPos origin, Level level, int range,
                                                 int required,
                                                 CallbackInfoReturnable<Boolean> callback) {
        if (callback.getReturnValue()) return;
        for (BlockPos pos : positions(origin, range)) {
            if (!level.isLoaded(pos)) continue;
            BlockEntity blockEntity = level.getBlockEntity(pos);
            if (blockEntity instanceof ISourceTile tile && tile.getSource() >= required) {
                callback.setReturnValue(true);
                return;
            }
        }
    }

    private static void appendSourceTiles(BlockPos origin, Level level, int range,
                                          List<ISpecialSourceProvider> result,
                                          boolean accepting) {
        for (BlockPos pos : positions(origin, range)) {
            if (!level.isLoaded(pos)) continue;
            BlockEntity blockEntity = level.getBlockEntity(pos);
            if (!(blockEntity instanceof ISourceTile tile)) continue;
            if (accepting ? !tile.canAcceptSource() : tile.getSource() <= 0) continue;
            if (containsSource(result, tile, pos)) continue;
            result.add(new SourceProvider(tile, pos.immutable()));
        }
    }

    private static boolean containsSource(List<ISpecialSourceProvider> providers,
                                          ISourceTile tile, BlockPos pos) {
        for (ISpecialSourceProvider provider : providers) {
            if (provider.getSource() == tile || pos.equals(provider.getCurrentPos())) return true;
        }
        return false;
    }

    private static Iterable<BlockPos> positions(BlockPos origin, int range) {
        int radius = Math.max(0, range);
        return BlockPos.betweenClosed(origin.offset(-radius, -radius, -radius),
                origin.offset(radius, radius, radius));
    }
}
