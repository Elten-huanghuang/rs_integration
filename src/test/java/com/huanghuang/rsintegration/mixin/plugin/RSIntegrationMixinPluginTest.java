package com.huanghuang.rsintegration.mixin.plugin;

import org.junit.jupiter.api.Test;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarFile;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** 校验 JEI 输入 API 探测能区分旧版和 15.74+。 */
class RSIntegrationMixinPluginTest {

    @Test
    void legacyJeiInputApiMatchesEachDistributionJar() throws Exception {
        List<Path> jars = new ArrayList<>();
        try (Stream<Path> files = Files.list(Path.of("libs"))) {
            files.filter(path -> path.getFileName().toString()
                            .matches("jei-1\\.20\\.1-forge-.*\\.jar"))
                    .sorted().forEach(jars::add);
        }
        String configured = System.getProperty("rsintegration.jei.compatibilityJar");
        if (configured != null && !configured.isBlank()) {
            Path path = Path.of(configured);
            if (Files.isRegularFile(path)) jars.add(path);
        }
        assumeTrue(!jars.isEmpty(), "没有找到 JEI 发行 jar，跳过版本探测校验");

        for (Path path : jars) {
            try (JarFile jar = new JarFile(path.toFile());
                 URLClassLoader loader = new URLClassLoader(
                         new URL[] {path.toUri().toURL()}, null)) {
                boolean expected = jar.getEntry(
                        "mezz/jei/gui/input/IUserInputHandler.class") != null
                        && jar.getEntry("mezz/jei/gui/input/UserInput.class") != null
                        && jar.getEntry("mezz/jei/gui/input/handlers/CombinedInputHandler.class") != null;
                assertEquals(expected, RSIntegrationMixinPlugin.hasLegacyJeiInputApi(loader),
                        path + " 的 JEI 输入 API 探测结果错误");
            }
        }
    }
}
