package com.huanghuang.rsintegration.disk.rs;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.nio.file.Path;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** 对真实备份模组校验注入位置，避免升级后静默丢失备份保护。 */
class SimpleBackupsInjectionTest {
    @Test void installedBackupJarHasOneExpectedTraversalCallAndServerField() throws Exception {
        String configured = System.getenv("RSI_SIMPLEBACKUPS_JAR");
        assumeTrue(configured != null, "设置 RSI_SIMPLEBACKUPS_JAR 后校验实际备份模组");
        try (ZipFile archive = new ZipFile(Path.of(configured).toFile())) {
            ClassNode node = new ClassNode();
            try (var input = archive.getInputStream(archive.getEntry("de/melanx/simplebackups/BackupThread.class"))) {
                new ClassReader(input).accept(node, 0);
            }
            assertTrue(node.fields.stream().anyMatch(field -> field.name.equals("server")
                    && field.desc.equals("Lnet/minecraft/server/MinecraftServer;")));
            var backup = node.methods.stream().filter(method -> method.name.equals("makeWorldBackup"))
                    .findFirst().orElseThrow();
            int calls = 0;
            for (var instruction : backup.instructions) {
                if (instruction instanceof MethodInsnNode call && call.owner.equals("java/nio/file/Files")
                        && call.name.equals("walkFileTree")
                        && call.desc.equals("(Ljava/nio/file/Path;Ljava/nio/file/FileVisitor;)Ljava/nio/file/Path;")) calls++;
            }
            assertEquals(1, calls);
        }
    }
}
