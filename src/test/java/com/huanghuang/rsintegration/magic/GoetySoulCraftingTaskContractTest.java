package com.huanghuang.rsintegration.magic;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GoetySoulCraftingTaskContractTest {
    private static final Path TASK_SOURCE = Path.of(
            "src", "main", "java", "com", "huanghuang", "rsintegration",
            "magic", "GoetySoulCraftingTask.java");
    private static final Path DELEGATE_SOURCE = Path.of(
            "src", "main", "java", "com", "huanghuang", "rsintegration",
            "mods", "goety", "GoetyBatchDelegate.java");
    private static final Path VIRTUAL_SOURCE = Path.of(
            "src", "main", "java", "com", "huanghuang", "rsintegration",
            "mixin", "goety", "DarkAltarSoulSourceMixin.java");
    private static final Path CAGE_SOURCE = Path.of(
            "src", "main", "java", "com", "huanghuang", "rsintegration",
            "mixin", "goety", "CursedCageSoulSourceMixin.java");
    private static final Path CANDLE_SOURCE = Path.of(
            "src", "main", "java", "com", "huanghuang", "rsintegration",
            "mixin", "goety", "SoulCandlestickSoulSourceMixin.java");
    private static final Path PORT_SOURCE = Path.of(
            "src", "main", "java", "com", "huanghuang", "rsintegration",
            "magic", "DarkAltarSoulPort.java");
    private static final Path BRAZIER_PORT_SOURCE = Path.of(
            "src", "main", "java", "com", "huanghuang", "rsintegration",
            "magic", "BrazierSoulPort.java");
    private static final Path VIRTUAL_ACCESS_SOURCE = Path.of(
            "src", "main", "java", "com", "huanghuang", "rsintegration",
            "magic", "GoetyVirtualSoulAccess.java");
    private static final Path COORDINATOR_SOURCE = Path.of(
            "src", "main", "java", "com", "huanghuang", "rsintegration",
            "magic", "GoetySoulSourceCoordinator.java");
    private static final Path NATIVE_SOURCE = Path.of(
            "src", "main", "java", "com", "huanghuang", "rsintegration",
            "magic", "GoetyDarkAltarNativeSoulSource.java");
    private static final Path BD_BRIDGE_SOURCE = Path.of(
            "src", "main", "java", "com", "huanghuang", "rsintegration",
            "magic", "BdReservationBridge.java");

    @Test
    void darkAltarReservesAndSuppliesSoulsInStages() throws Exception {
        String task = Files.readString(TASK_SOURCE);
        String delegate = Files.readString(DELEGATE_SOURCE);
        String virtualSource = Files.readString(VIRTUAL_SOURCE);
        String cageSource = Files.readString(CAGE_SOURCE);
        String candleSource = Files.readString(CANDLE_SOURCE);
        String port = Files.readString(PORT_SOURCE);
        String brazierPort = Files.readString(BRAZIER_PORT_SOURCE);
        String virtualAccess = Files.readString(VIRTUAL_ACCESS_SOURCE);
        String coordinator = Files.readString(COORDINATOR_SOURCE);
        String nativeSource = Files.readString(NATIVE_SOURCE);
        String bdBridge = Files.readString(BD_BRIDGE_SOURCE);

        assertTrue(task.contains("DARK_ALTAR_PER_SECOND"));
        assertTrue(task.contains("TOTAL_BATCHED"));
        assertTrue(task.contains("settleProgress"));
        assertTrue(task.contains("pendingDiskInjected"));
        assertTrue(task.contains("MagicResourceReservations.reserve"));
        assertTrue(task.contains("Reflect.<Integer>invoke(recipe, \"getSoulCost\")"));
        assertTrue(task.contains("withdraw(need)"));
        assertTrue(task.contains("saturatingMultiply(perCharge, duration)"));
        assertTrue(task.contains("reserveBrazier"));
        assertTrue(task.contains("prepareBeforeStart"));
        assertTrue(task.contains("Using RS/BD network soul source directly"));
        assertTrue(task.contains("GoetySoulSourceCoordinator.create"));
        assertTrue(task.contains("GoetyDarkAltarNativeSoulSource"));
        assertTrue(task.contains("DarkAltarSoulPort.attach(altar, cage"));
        assertTrue(task.contains("BrazierSoulPort.attach(altar, directCoordinator, candles)"));
        assertTrue(task.contains("directCoordinator == null"));
        assertTrue(task.contains("supplyBrazier"));
        assertTrue(task.contains("findCandlesticks"));
        assertTrue(task.contains("BdReservationBridge"));
        // BD 预留的实现已抽到 BdReservationBridge，任务类只做转发。
        assertTrue(bdBridge.contains("class BdReservation implements GoetySoulNetworkPool"));
        assertTrue(bdBridge.contains("RESERVED_BY_BD"));
        assertTrue(bdBridge.contains("takeUnaccounted"));
        assertFalse(task.contains("RESERVED_BY_DISK"));
        assertFalse(task.contains("RESERVED_BY_BD"));
        assertTrue(task.contains("BdReservationBridge.reserve"));
        assertTrue(bdBridge.contains("callBd"));
        assertTrue(task.contains("bdNetworkId"));
        assertTrue(delegate.contains("GoetySoulCraftingTask.reserveDarkAltar"));
        assertTrue(delegate.contains("GoetySoulCraftingTask.reserveBrazier"));
        assertTrue(delegate.contains("soulTask.prepareBeforeStart(brazier)"));
        assertTrue(delegate.contains("storageEndpoint()"));
        assertTrue(delegate.contains("delegate.setStorageEndpoint(endpoint);"));
        assertTrue(delegate.contains("rsi.goety.error.soul_reservation_incomplete"));
        assertTrue(delegate.contains("soulTask.startupAvailable()"));
        assertTrue(delegate.contains("soulTask.tick(level, be)"));
        assertTrue(delegate.contains("closeSoulTask()"));
        assertTrue(port.contains("IdentityHashMap"));
        assertTrue(port.contains("coordinator.consume(amount)"));
        assertTrue(port.contains("GoetyVirtualSoulAccess.attach"));
        assertTrue(brazierPort.contains("GoetyVirtualSoulAccess.attach"));
        assertTrue(coordinator.contains("network.withdraw(fromNetwork)"));
        assertTrue(coordinator.contains("nativeSource.available() < nativeNeeded"));
        assertTrue(coordinator.contains("nativeSource.consume(remaining)"));
        assertTrue(coordinator.contains("network.refund(withdrawn)"));
        assertTrue(nativeSource.contains("GoetyPlayerSoulAccess.consume"));
        assertTrue(nativeSource.contains("getOwner"));
        assertTrue(virtualSource.contains("DarkAltarSoulPort.isActive"));
        assertTrue(cageSource.contains("GoetyVirtualSoulAccess.souls"));
        assertTrue(cageSource.contains("GoetyVirtualSoulAccess.consume"));
        assertTrue(candleSource.contains("GoetyVirtualSoulAccess.souls"));
        assertTrue(candleSource.contains("GoetyVirtualSoulAccess.consume"));
        assertTrue(virtualAccess.contains("IdentityHashMap"));
        assertTrue(virtualAccess.contains("physicalSouls"));
        assertTrue(virtualAccess.contains("physicalDecrease"));
    }
}
