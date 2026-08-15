package com.huanghuang.rsintegration.crafting.batch;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphBatchDelegateContractTest {
    private static final Path SOURCE_ROOT = Path.of("src/main/java");
    private static final Set<String> FLAT_ONLY_DELEGATES = Set.of(
            "PmmoSalvageBatchDelegate.java"
    );

    @Test
    void flatBatchDelegatesAlsoDeclareTheGraphBatchContract() throws IOException {
        List<String> missing = new ArrayList<>();
        try (Stream<Path> sources = Files.walk(SOURCE_ROOT)) {
            for (Path source : sources.filter(path -> path.toString().endsWith(".java")).toList()) {
                String code = Files.readString(source);
                if (!code.contains("public int prepareFlatBatch(")) continue;
                if (FLAT_ONLY_DELEGATES.contains(source.getFileName().toString())) continue;
                if (!code.contains("public void prepareGraphBatch(")
                        || !code.contains("public int preferredParallelBatchSize(")) {
                    missing.add(SOURCE_ROOT.relativize(source).toString());
                }
            }
        }

        assertTrue(missing.isEmpty(),
                "flat batching would fall back to one operation in graph execution: " + missing);
    }
}
