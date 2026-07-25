package com.huanghuang.rsintegration;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards against a dedicated-server startup crash.
 *
 * <p>{@code @OnlyIn(Dist.CLIENT)} does not merely disable a method on a server —
 * Forge <em>removes</em> it from the class. A {@code registerMessage(...)} call in
 * {@code common_setup} that references such a method with a method ref therefore
 * raises {@link NoSuchMethodError}, and mod loading fails outright: the server
 * never starts. Singleplayer never reveals this, because nothing is stripped
 * client-side.</p>
 *
 * <p>A PLAY_TO_CLIENT handler must stay present on both dists and defer its
 * client-only work (DistExecutor / an explicit dist check) instead.</p>
 */
class StrippedPacketHandlerTest {

    private static final Path SRC = Path.of("src", "main", "java");

    /** A method declaration preceded by @OnlyIn(Dist.CLIENT), capturing its name. */
    private static final Pattern STRIPPED_METHOD = Pattern.compile(
            // Accept both @OnlyIn and a fully-qualified @net.minecraftforge...OnlyIn,
            // and Dist.CLIENT written with or without its package.
            "@(?:[\\w.]+\\.)?OnlyIn\\s*\\(\\s*(?:[\\w.]+\\.)?Dist\\.CLIENT\\s*\\)"
                    + "((?:\\s*@\\w+(?:\\([^)]*\\))?)*)\\s*"
                    + "(?:public|private|protected)?\\s*(?:static\\s+)?"
                    + "[\\w.<>\\[\\], ?]+?\\s+(\\w+)\\s*\\(");

    private static final Pattern METHOD_REF = Pattern.compile("(\\w+)\\s*::\\s*(\\w+)");

    @Test
    void registeredPacketHandlersAreNotStrippedOnDedicatedServer() throws IOException {
        assertTrue(Files.isDirectory(SRC), () -> "missing source root " + SRC.toAbsolutePath());

        // Simple class name -> method names Forge strips on a server.
        Map<String, Set<String>> stripped = new HashMap<>();
        forEachJavaFile(path -> {
            String text = read(path);
            if (!text.contains("OnlyIn")) return;
            Matcher matcher = STRIPPED_METHOD.matcher(text);
            while (matcher.find()) {
                stripped.computeIfAbsent(stem(path), key -> new TreeSet<>())
                        .add(matcher.group(2));
            }
        });

        List<String> offenders = new ArrayList<>();
        forEachJavaFile(path -> {
            String text = read(path);
            if (!text.contains("registerMessage")) return;
            Matcher matcher = METHOD_REF.matcher(text);
            while (matcher.find()) {
                Set<String> names = stripped.get(matcher.group(1));
                if (names != null && names.contains(matcher.group(2))) {
                    offenders.add(path.getFileName() + " registers "
                            + matcher.group(1) + "::" + matcher.group(2));
                }
            }
        });

        assertEquals(List.of(), offenders,
                "a registered packet handler is @OnlyIn(Dist.CLIENT); Forge strips it on a "
                        + "dedicated server and registerMessage then throws NoSuchMethodError at "
                        + "common_setup. Drop the annotation and defer the client-only work.");
    }

    private static void forEachJavaFile(java.util.function.Consumer<Path> action)
            throws IOException {
        try (Stream<Path> files = Files.walk(SRC)) {
            files.filter(path -> path.toString().endsWith(".java")).forEach(action);
        }
    }

    private static String stem(Path path) {
        String name = path.getFileName().toString();
        return name.substring(0, name.length() - ".java".length());
    }

    private static String read(Path path) {
        try {
            return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
