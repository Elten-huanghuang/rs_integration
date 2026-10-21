package com.huanghuang.rsintegration.crafting.endpoint;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 合成核心只能携带存储端点，RS 原生网络必须停在适配层。 */
class CraftStorageBoundaryTest {
    private static final Path CRAFTING = Path.of(
            "src", "main", "java", "com", "huanghuang", "rsintegration", "crafting");
    private static final Set<String> RS_BRIDGES = Set.of(
            "CraftStorageEndpoints.java",
            "LegacyRsCraftStorageEndpoint.java",
            "RSGridMatrixNormalizer.java");

    @Test
    void asyncChainAndLedgerDoNotStoreNativeNetworks() throws IOException {
        String chain = read("AsyncCraftChain.java");
        String ledger = read("ExtractionLedger.java");
        String context = read("ResolutionContext.java");

        assertFalse(chain.contains("private final INetwork network"),
                "AsyncCraftChain must keep only the selected CraftStorageEndpoint");
        assertFalse(ledger.contains("Map<INetwork"),
                "ExtractionLedger caches must be keyed by endpoints");
        assertFalse(ledger.contains("final INetwork sourceNetwork"),
                "ledger entries must retain endpoint sources");
        assertFalse(context.contains("final INetwork"),
                "planning context must not retain a native storage network");
    }

    @Test
    void craftingExecutionEntryPointUsesAnEndpoint() throws IOException {
        String utils = read("CraftPacketUtils.java");
        int method = utils.indexOf("public static boolean executeCraftingSteps(");
        assertTrue(method >= 0, "executeCraftingSteps must remain available");
        String signature = utils.substring(method, Math.min(utils.length(), method + 320));
        assertTrue(signature.contains("CraftStorageEndpoint endpoint"),
                "the execution entry point must accept the backend-neutral endpoint");
        assertFalse(signature.contains("INetwork network"),
                "the execution entry point must not expose the RS network");
    }

    @Test
    void nativeNetworkStaysInsideTheRsBridges() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(CRAFTING)) {
            files.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !RS_BRIDGES.contains(path.getFileName().toString()))
                    .filter(path -> {
                        try {
                            return Files.readString(path, StandardCharsets.UTF_8)
                                    .contains("INetwork");
                        } catch (IOException exception) {
                            throw new IllegalStateException(exception);
                        }
                    })
                    .map(path -> CRAFTING.relativize(path).toString())
                    .sorted()
                    .forEach(offenders::add);
        }
        assertTrue(offenders.isEmpty(),
                "通用合成代码只能通过存储端点访问后端，发现 RS 原生网络残留: " + offenders);
    }

    private static String read(String file) throws IOException {
        return Files.readString(CRAFTING.resolve(file), StandardCharsets.UTF_8);
    }
}
