package com.huanghuang.rsintegration.crafting.plan;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanBackgroundClientTest extends BootstrapTest {

    @AfterEach
    void clearState() {
        PlanBackgroundClient.clear();
    }

    @Test
    void readyMessageHasAOneClickOpenCommand() {
        Component message = PlanBackgroundClient.readyMessage(
                Component.literal("Poppy"), 42L);
        Component link = message.getSiblings().stream()
                .filter(part -> part.getStyle().getClickEvent() != null)
                .findFirst()
                .orElseThrow();

        ClickEvent click = link.getStyle().getClickEvent();
        assertNotNull(click);
        assertEquals(ClickEvent.Action.RUN_COMMAND, click.getAction());
        assertEquals("/rsi_plan open 42", click.getValue());
    }

    @Test
    void onlyDetachedRequestsAreCapturedAndOnlyOnce() {
        PlanResponse plan = new PlanResponse(false, "Poppy", ItemStack.EMPTY,
                List.of(), Map.of(), List.of(), "minecraft:poppy");

        assertFalse(PlanBackgroundClient.capture(42L, plan));
        PlanBackgroundClient.detach(42L);
        assertTrue(PlanBackgroundClient.capture(42L, plan));
        assertFalse(PlanBackgroundClient.capture(42L, plan));
    }
}
