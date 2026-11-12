package com.huanghuang.rsintegration.testutil;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

/** 优先使用本地模组，否则读取独立的兼容测试缓存，不改变正式编译依赖。 */
public final class CompatibilityTestJars {
    private CompatibilityTestJars() {}

    public static Path requireMatching(String fileNamePattern) throws IOException {
        return requireMatching(fileNamePattern,
                List.of(Path.of("libs"), Path.of(".gradle", "compatibility-test-jars")));
    }

    static Path requireMatching(String fileNamePattern, List<Path> directories) throws IOException {
        Pattern pattern = Pattern.compile(fileNamePattern, Pattern.CASE_INSENSITIVE);
        for (Path directory : directories) {
            if (!Files.isDirectory(directory)) continue;
            List<Path> matches;
            try (var paths = Files.list(directory)) {
                matches = paths.filter(Files::isRegularFile)
                        .filter(path -> pattern.matcher(path.getFileName().toString()).matches())
                        .sorted().toList();
            }
            if (matches.size() > 1) {
                throw new IOException("兼容测试依赖匹配到多个文件：" + matches);
            }
            if (matches.size() == 1) return matches.get(0);
        }
        throw new FileNotFoundException("缺少兼容测试依赖：" + fileNamePattern + "；查找目录："
                + directories + "。请先联网运行 ./gradlew prepareCompatibilityTestJars。");
    }
}
