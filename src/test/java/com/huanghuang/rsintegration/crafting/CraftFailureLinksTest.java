package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.crafting.batch.CraftProgressDeltaPacket;
import com.huanghuang.rsintegration.crafting.batch.CraftStartedPacket;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static com.huanghuang.rsintegration.crafting.CraftProgressSnapshot.*;
import static org.junit.jupiter.api.Assertions.*;

class CraftFailureLinksTest extends BootstrapTest {
    @AfterEach void clear() { CraftProgressTracker.clear(); }

    @Test
    void greenUnderlinedLinkExecutesLocalCommandForExactTaskWithoutPermissions() throws Exception {
        var snapshot = failure();
        CraftProgressTracker.onProgress(snapshot);
        Component link = CraftFailureLinks.message(snapshot.craftId()).getSiblings().stream()
                .filter(part -> part.getStyle().getClickEvent() != null).findFirst().orElseThrow();
        assertEquals(ChatFormatting.GREEN.getColor(), link.getStyle().getColor().getValue());
        assertTrue(link.getStyle().isUnderlined());
        assertNotNull(link.getStyle().getHoverEvent());
        ClickEvent click = link.getStyle().getClickEvent();
        assertEquals(ClickEvent.Action.RUN_COMMAND, click.getAction());
        assertEquals("/rsi_failure_report " + snapshot.craftId(), click.getValue());
        CraftFailureLinks queue = new CraftFailureLinks();
        CommandDispatcher<Object> dispatcher = new CommandDispatcher<>();
        CraftFailureLinks.register(dispatcher, text -> assertTrue(queue.request(text,
                CraftProgressTracker.sessionGeneration(), CraftProgressTracker::failure)));
        assertEquals(1, dispatcher.execute(click.getValue().substring(1), new Object()));
        List<CraftFailureHistory.Entry> opened = new ArrayList<>();
        queue.tick(CraftProgressTracker.sessionGeneration(), CraftProgressTracker::failure, opened::add, () -> fail("expired"));
        assertTrue(opened.isEmpty(), "must let ChatScreen close before opening");
        queue.tick(CraftProgressTracker.sessionGeneration(), CraftProgressTracker::failure, opened::add, () -> fail("expired"));
        queue.tick(CraftProgressTracker.sessionGeneration(), CraftProgressTracker::failure, opened::add, () -> fail("expired"));
        assertEquals(1, opened.size());
        assertEquals(snapshot.craftId(), opened.get(0).snapshot().craftId());
    }

    @Test
    void fullDeltaStartedAndResyncDuplicatesDoNotNotifyOrResurrectDismissedFailure() {
        var snapshot = failure();
        var started = new CraftStartedPacket(snapshot.craftId(), 1, true, new ItemStack(Items.DIAMOND));
        CraftProgressTracker.onStarted(started);
        var delta = new CraftProgressDeltaPacket(snapshot.craftId(), 0, snapshot, snapshot.nodes());
        assertTrue(CraftProgressTracker.onDelta(delta));
        assertFalse(CraftProgressTracker.onProgress(snapshot));
        assertFalse(CraftProgressTracker.onDelta(delta));
        CraftProgressTracker.retainOnly(List.of());
        CraftProgressTracker.dismissFailure(snapshot.craftId());
        CraftProgressTracker.onStarted(started);
        assertFalse(CraftProgressTracker.onProgress(snapshot));
        assertFalse(CraftProgressTracker.onDelta(delta));
        assertNull(CraftProgressTracker.failure(snapshot.craftId()));
        assertTrue(CraftProgressTracker.taskSnapshots().isEmpty());
    }

    @Test
    void removedEvictedDisconnectedAndMalformedLinksAreInvalid() {
        var snapshot = failure();
        CraftProgressTracker.onProgress(snapshot);
        CraftFailureLinks queue = new CraftFailureLinks();
        String id = snapshot.craftId().toString();
        assertTrue(queue.request(id, CraftProgressTracker.sessionGeneration(), CraftProgressTracker::failure));
        for (int index = 0; index < CraftFailureHistory.MAX_ENTRIES; index++) CraftProgressTracker.onProgress(failure());
        assertFalse(queue.request(id, CraftProgressTracker.sessionGeneration(), CraftProgressTracker::failure));
        assertFalse(CraftProgressTracker.onProgress(snapshot));
        assertFalse(queue.request("not-a-uuid", 0, CraftProgressTracker::failure));
        assertFalse(queue.request("1-1-1-1-1", 0, CraftProgressTracker::failure));
        var retained = failure();
        CraftProgressTracker.onProgress(retained);
        CraftProgressTracker.dismissFailure(retained.craftId());
        assertFalse(queue.request(retained.craftId().toString(), 0, CraftProgressTracker::failure));
        var disconnected = failure();
        CraftProgressTracker.onProgress(disconnected);
        CraftProgressTracker.clear();
        assertFalse(queue.request(disconnected.craftId().toString(), 0, CraftProgressTracker::failure));
    }

    @Test
    void queuedLinkRechecksExpiryAndCannotOpenAcrossDisconnect() {
        var snapshot = failure();
        CraftProgressTracker.onProgress(snapshot);
        CraftFailureLinks queue = new CraftFailureLinks();
        assertTrue(queue.request(snapshot.craftId().toString(), CraftProgressTracker.sessionGeneration(), CraftProgressTracker::failure));
        AtomicInteger expired = new AtomicInteger();
        CraftProgressTracker.dismissFailure(snapshot.craftId());
        queue.tick(CraftProgressTracker.sessionGeneration(), CraftProgressTracker::failure, entry -> fail("opened"), expired::incrementAndGet);
        queue.tick(CraftProgressTracker.sessionGeneration(), CraftProgressTracker::failure, entry -> fail("opened"), expired::incrementAndGet);
        queue.tick(CraftProgressTracker.sessionGeneration(), CraftProgressTracker::failure, entry -> fail("opened"), expired::incrementAndGet);
        assertEquals(1, expired.get());
        var other = failure();
        CraftProgressTracker.onProgress(other);
        assertTrue(queue.request(other.craftId().toString(), CraftProgressTracker.sessionGeneration(), CraftProgressTracker::failure));
        CraftProgressTracker.clear();
        queue.tick(CraftProgressTracker.sessionGeneration(), CraftProgressTracker::failure, entry -> fail("opened"), () -> fail("stale popup"));
    }

    @Test
    void nonFailuresDoNotCreateReports() {
        for (Result result : Result.values()) {
            if (result == Result.FAILED) continue;
            assertFalse(CraftProgressTracker.onProgress(new CraftProgressSnapshot(UUID.randomUUID(), 1,
                    result, Reason.NONE, 0, 1, 0, null)));
        }
        assertTrue(CraftProgressTracker.taskSnapshots().stream().noneMatch(snapshot -> snapshot.result() == Result.FAILED));
    }

    @Test
    void clientCommandIsRegisteredOnlyOnClientAndNeverUsesServerTransport() throws Exception {
        Path base = Path.of("src/main/java/com/huanghuang/rsintegration");
        String command = Files.readString(base.resolve("crafting/CraftFailureClientCommands.java"));
        String links = Files.readString(base.resolve("crafting/CraftFailureLinks.java"));
        assertTrue(command.contains("RegisterClientCommandsEvent"));
        assertTrue(command.contains("@OnlyIn(Dist.CLIENT)"));
        assertFalse(command.contains("sendToServer"));
        assertFalse(command.contains("sendCommand"));
        assertFalse(links.contains("sendToServer"));
        assertTrue(Files.readString(base.resolve("client/ClientEventBootstrap.java")).contains("CraftFailureClientCommands.class"));
        String handler = Files.readString(base.resolve("crafting/batch/CraftProgressClientPacketHandler.java"));
        assertTrue(handler.contains("if (CraftProgressTracker.onProgress(snapshot))"));
        assertTrue(handler.contains("if (CraftProgressTracker.onDelta(packet))"));
        assertTrue(command.contains("ClientCommandHandler.runCommand(command)"));
        String mixin = Files.readString(base.resolve("mixin/minecraft/CraftFailureReportClickMixin.java"));
        assertTrue(mixin.contains("method = \"handleComponentClicked\""));
        assertTrue(mixin.contains("require = 1"));
        assertTrue(mixin.contains("callback.setReturnValue(true)"));
        var config = com.google.gson.JsonParser.parseString(Files.readString(Path.of("src/main/resources/rs_integration.mixins.json"))).getAsJsonObject();
        var name = new com.google.gson.JsonPrimitive("minecraft.CraftFailureReportClickMixin");
        assertTrue(config.getAsJsonArray("client").contains(name));
        assertFalse(config.getAsJsonArray("mixins").contains(name));
    }

    @Test
    void clickBridgeOnlyInterceptsOurRunCommandAndLeavesOtherModsUntouched() {
        var style = net.minecraft.network.chat.Style.EMPTY;
        String command = "/rsi_failure_report " + UUID.randomUUID();
        assertEquals(command.substring(1), CraftFailureLinks.localCommand(style.withClickEvent(
                new ClickEvent(ClickEvent.Action.RUN_COMMAND, command))));
        assertNull(CraftFailureLinks.localCommand(null));
        assertNull(CraftFailureLinks.localCommand(style));
        assertNull(CraftFailureLinks.localCommand(style.withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, command))));
        assertNull(CraftFailureLinks.localCommand(style.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/other_mod report"))));
        assertNull(CraftFailureLinks.localCommand(style.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/rsi_failure_report_other"))));
    }

    private static CraftProgressSnapshot failure() {
        return new CraftProgressSnapshot(UUID.randomUUID(), TERMINAL_SEQUENCE, Result.FAILED,
                Reason.UNKNOWN, 0, 1, 0, "test", List.of());
    }
}
