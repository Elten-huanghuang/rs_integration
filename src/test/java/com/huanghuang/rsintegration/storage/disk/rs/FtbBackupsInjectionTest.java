package com.huanghuang.rsintegration.storage.disk.rs;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

/** 对整合包实际使用的 FTB Backups 2 字节码校验两种备份的注入入口。 */
class FtbBackupsInjectionTest {
    @Test void installedBackupJarEnumeratesFilesInsideBothProtectedOperations() throws Exception {
        try (ZipFile archive = new ZipFile(Path.of("libs/ftbbackups2-forge-1.20-1.0.23.jar").toFile())) {
            ClassNode handler = new ClassNode();
            try (var input = archive.getInputStream(archive.getEntry("net/creeperhost/ftbbackups/BackupHandler.class"))) {
                new ClassReader(input).accept(handler, 0);
            }
            Set<String> operations = new HashSet<>();
            int calls = 0;
            for (var method : handler.methods) {
                for (var instruction : method.instructions) {
                    if (!(instruction instanceof MethodInsnNode call)
                            || !call.owner.equals("net/creeperhost/ftbbackups/utils/FileUtils")
                            || !Set.of("zip", "copy").contains(call.name)) continue;
                    calls++;
                    operations.add(call.name);
                    assertTrue(method.name.startsWith("lambda$createBackup$"));
                    assertEquals("(Ljava/nio/file/Path;Ljava/nio/file/Path;Ljava/lang/Iterable;)V", call.desc);
                    assertEquals(1, Arrays.stream(Type.getArgumentTypes(method.desc))
                            .filter(type -> type.getDescriptor().equals("Lnet/minecraft/server/MinecraftServer;")).count());
                }
            }
            assertEquals(Set.of("zip", "copy"), operations);
            assertEquals(2, calls);
            ClassNode files = new ClassNode();
            try (var input = archive.getInputStream(archive.getEntry("net/creeperhost/ftbbackups/utils/FileUtils.class"))) {
                new ClassReader(input).accept(files, 0);
            }
            for (String name : operations) {
                var method = files.methods.stream().filter(candidate -> candidate.name.equals(name)).findFirst().orElseThrow();
                assertTrue(Arrays.stream(method.instructions.toArray()).anyMatch(instruction ->
                        instruction instanceof MethodInsnNode call && call.owner.equals("java/nio/file/Files")
                                && call.name.equals("walk")), "固定检查点必须早于世界目录枚举");
            }
        }
        assertTrue(Files.readString(Path.of("src/main/resources/rs_integration.mixins.json"))
                .contains("\"ftbbackups2.UnifiedDiskBackupMixin\""));
    }
}
