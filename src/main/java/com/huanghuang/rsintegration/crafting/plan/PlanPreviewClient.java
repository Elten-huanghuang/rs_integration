package com.huanghuang.rsintegration.crafting.plan;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.batch.BatchCraftNetworkHandler;
import com.huanghuang.rsintegration.crafting.batch.GenericCraftPacket;
import net.minecraft.Util;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.Map;

/**
 * Client-side preview request debouncer.
 * When the player types in the repeat-count EditBox, this waits 150ms
 * after the last keystroke before sending a preview request to the server.
 * Uses {@link Util#getMillis()} (real wall-clock time) rather than game ticks
 * so the UI stays responsive even under severe server lag.
 *
 * <h3>Wiring status</h3>
 * <b>Not wired</b> — {@code CraftingPlanScreen} currently does not debounce
 * repeat-count changes; it sends a preview request on every keystroke via
 * {@code requestPlanRefresh()} which calls {@code GenericCraftPacket(preview=true)}.
 * The server-side {@code PreviewRateLimiter} (100ms min interval) catches
 * rapid-fire requests, so client-side debouncing is a nice-to-have optimization,
 * not a correctness requirement.
 *
 * <h3>Wiring checklist</h3>
 * <ol>
 *   <li>Call {@link #onRepeatCountChanged(String, int, Map)} from
 *       {@code CraftingPlanScreen} repeat-count input handler</li>
 *   <li>Call {@link #onClientTick()} from {@code CraftingPlanScreen.tick()}</li>
 *   <li>Call {@link #cancel()} from {@code CraftingPlanScreen.onClose()}</li>
 *   <li>Remove the direct {@code GenericCraftPacket(preview=true)} send from
 *       the screen's inline handler (let the debouncer send it)</li>
 * </ol>
 */
@OnlyIn(Dist.CLIENT)
public final class PlanPreviewClient {
    private static final long DEBOUNCE_MS = 150;

    private static long lastChangeTime;
    private static String pendingRecipeId;
    private static int pendingRepeatCount = 1;
    private static Map<String, String> pendingForcedSlots;
    private static boolean pending;

    private PlanPreviewClient() {}

    /** Call whenever the repeat count input or forced-slot selection changes. */
    public static void onRepeatCountChanged(String recipeId, int repeatCount,
                                             Map<String, String> forcedSlots) {
        pendingRecipeId = recipeId;
        pendingRepeatCount = Math.max(1, Math.min(repeatCount, 999));
        pendingForcedSlots = forcedSlots;
        pending = true;
        lastChangeTime = Util.getMillis();
        RSIntegrationMod.LOGGER.debug("[RSI-Preview] Debounce scheduled: recipe={} repeat={}",
                recipeId, repeatCount);
    }

    /** Call every client tick. Sends preview request when debounce expires. */
    public static void onClientTick() {
        if (!pending) return;
        if (Util.getMillis() - lastChangeTime < DEBOUNCE_MS) return;

        pending = false;
        RSIntegrationMod.LOGGER.debug(
                "[RSI-Preview] Debounce expired — sending preview: recipe={} repeat={}",
                pendingRecipeId, pendingRepeatCount);

        ResourceLocation recipeId = ResourceLocation.tryParse(pendingRecipeId);
        if (recipeId == null) {
            RSIntegrationMod.LOGGER.warn("[RSI-Preview] Invalid recipe ID: {}", pendingRecipeId);
            return;
        }

        // Send preview request to server via the batch-craft channel.
        // preview=true → server computes and returns a PlanResponse without executing.
        BatchCraftNetworkHandler.CHANNEL.sendToServer(
                new GenericCraftPacket(recipeId, true,
                        pendingForcedSlots != null ? pendingForcedSlots : Map.of(),
                        null, null, pendingRepeatCount));
    }

    /** Cancel any pending debounce (e.g. when the screen is closed). */
    public static void cancel() {
        pending = false;
        RSIntegrationMod.LOGGER.debug("[RSI-Preview] Debounce cancelled");
    }

    public static boolean isDebouncing() {
        return pending;
    }
}
