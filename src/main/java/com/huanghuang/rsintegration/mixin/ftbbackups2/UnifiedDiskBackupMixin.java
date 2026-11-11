package com.huanghuang.rsintegration.mixin.ftbbackups2;

import com.huanghuang.rsintegration.storage.disk.rs.UnifiedDiskBackup;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

import java.io.IOException;
import java.nio.file.Path;

/** FTB 的 ZIP 与目录备份都要在枚举世界文件之前固定统一盘检查点。 */
@Pseudo
@Mixin(targets = "net.creeperhost.ftbbackups.BackupHandler", remap = false)
public abstract class UnifiedDiskBackupMixin {
    // Mixin 的 * 是匹配数量量词，不能用于匹配 lambda 方法名后缀。
    @WrapOperation(method = "lambda$createBackup$1(Ljava/util/concurrent/CompletableFuture;"
            + "Lnet/minecraft/server/MinecraftServer;Ljava/lang/String;"
            + "Lnet/creeperhost/ftbbackups/config/Format;)V", at = {
            @At(value = "INVOKE", target = "Lnet/creeperhost/ftbbackups/utils/FileUtils;zip("
                    + "Ljava/nio/file/Path;Ljava/nio/file/Path;Ljava/lang/Iterable;)V"),
            @At(value = "INVOKE", target = "Lnet/creeperhost/ftbbackups/utils/FileUtils;copy("
                    + "Ljava/nio/file/Path;Ljava/nio/file/Path;Ljava/lang/Iterable;)V")
    }, remap = false, require = 2)
    private static void rsi$consistentDiskFiles(Path output, Path root, Iterable<Path> paths, Operation<Void> original,
                                               @Local(argsOnly = true) MinecraftServer server) throws IOException {
        UnifiedDiskBackup.run(server, () -> {
            original.call(output, root, paths);
            return null;
        });
    }
}
