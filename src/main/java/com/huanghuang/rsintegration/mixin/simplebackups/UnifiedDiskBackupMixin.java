package com.huanghuang.rsintegration.mixin.simplebackups;

import com.huanghuang.rsintegration.disk.rs.UnifiedDiskBackup;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

import java.io.IOException;
import java.nio.file.FileVisitor;
import java.nio.file.Path;

@Pseudo
@Mixin(targets = "de.melanx.simplebackups.BackupThread", remap = false)
public abstract class UnifiedDiskBackupMixin {
    @Shadow @Final private MinecraftServer server;

    @WrapOperation(method = "makeWorldBackup", at = @At(value = "INVOKE", target =
            "Ljava/nio/file/Files;walkFileTree(Ljava/nio/file/Path;Ljava/nio/file/FileVisitor;)Ljava/nio/file/Path;"),
            remap = false, require = 1)
    private Path rsi$consistentDiskFiles(Path path, FileVisitor<Path> visitor, Operation<Path> original) throws IOException {
        return UnifiedDiskBackup.run(server, () -> original.call(path, visitor));
    }
}
