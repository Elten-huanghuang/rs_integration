package com.huanghuang.rsintegration.storage.disk.rs;

import com.huanghuang.rsintegration.mixin.ftbbackups2.UnifiedDiskBackupMixin;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.spongepowered.asm.mixin.injection.selectors.ElementNode;
import org.spongepowered.asm.mixin.injection.selectors.TargetSelector;
import org.spongepowered.asm.util.Annotations;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

/** 对整合包实际使用的 FTB Backups 2 字节码校验两种备份的注入入口。 */
class FtbBackupsInjectionTest {
    @Test void productionMixinSelectorsMatchBothInstalledBackupOperations() throws Exception {
        ClassNode mixin = new ClassNode();
        String resource = "/" + UnifiedDiskBackupMixin.class.getName().replace('.', '/') + ".class";
        try (var input = UnifiedDiskBackupMixin.class.getResourceAsStream(resource)) {
            assertNotNull(input);
            new ClassReader(input).accept(mixin, 0);
        }
        var injections = mixin.methods.stream()
                .map(method -> Annotations.getVisible(method, WrapOperation.class))
                .filter(annotation -> annotation != null).toList();
        assertEquals(1, injections.size());
        AnnotationNode injection = injections.get(0);
        var selectors = TargetSelector.parseAndValidate(Annotations.getValue(injection, "method", true), null);
        assertFalse(selectors.isEmpty());
        var operations = Annotations.<AnnotationNode>getValue(injection, "at", true).stream()
                .map(at -> TargetSelector.parseAndValidate(Annotations.<String>getValue(at, "target"), null))
                .toList();
        assertEquals(2, operations.size());

        try (ZipFile archive = new ZipFile(Path.of("libs/ftbbackups2-forge-1.20-1.0.23.jar").toFile())) {
            ClassNode handler = new ClassNode();
            try (var input = archive.getInputStream(archive.getEntry("net/creeperhost/ftbbackups/BackupHandler.class"))) {
                new ClassReader(input).accept(handler, 0);
            }
            // 使用 Mixin 自身的解析与匹配规则，不能以 startsWith 模拟方法选择器。
            var targets = handler.methods.stream().filter(method -> selectors.stream()
                    .anyMatch(selector -> selector.match(ElementNode.of(handler, method)).isExactMatch())).toList();
            assertEquals(1, targets.size(), "生产选择器必须匹配到实际备份方法");
            for (var operation : operations) {
                long calls = targets.stream().flatMap(method -> Arrays.stream(method.instructions.toArray()))
                        .filter(instruction -> operation.match(ElementNode.of(instruction)).isExactMatch()).count();
                assertEquals(1, calls, "ZIP 和目录备份调用都必须被保护");
            }
            assertEquals(operations.size(), Annotations.<Integer>getValue(injection, "require").intValue());
        }
    }

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
