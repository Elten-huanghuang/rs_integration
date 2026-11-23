package com.huanghuang.rsintegration.api;

import com.huanghuang.rsintegration.storage.ItemRetrievalService;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.Objects;

/** Server-authoritative retrieval used by the native JEI and world-pick actions. */
public final class RSIItemRetrieval {
    private RSIItemRetrieval() {}

    /** Call on the server thread. The caller owns any messages or bookmark feedback. */
    public static Result retrieve(Request request) {
        return ItemRetrievalService.retrieve(Objects.requireNonNull(request));
    }

    public enum Scene { JEI, WORLD_PICK }
    public enum Intent { COUNT, SINGLE, STACK, TOP_UP }
    public enum Fallback { DISABLED, REGISTERED }
    public enum Status {
        SUCCESS, PARTIAL, OUT_OF_STOCK, NO_NETWORK, NO_PERMISSION, NO_SPACE,
        INVALID_REQUEST, BACKEND_FAILURE, INDETERMINATE
    }

    public sealed interface Destination permits Inventory, Cursor, SelectedHotbar {}
    public record Inventory() implements Destination {}
    /**
     * The expected server-side container ID for a server-validated menu cursor.
     * Creative inventory cursors use the caller's own client confirmation flow.
     */
    public record Cursor(int containerId) implements Destination {}
    /** The expected selected hotbar index, from 0 to 8. */
    public record SelectedHotbar(int slot) implements Destination {}

    /**
     * COUNT and TOP_UP are limited by count. SINGLE requests one item, STACK requests a full stack.
     * World pick supports the selected hotbar only. TOP_UP requires a matching stack there.
     */
    public record Request(ServerPlayer player, ItemStack target, int count, Scene scene, Intent intent,
                          Destination destination, Fallback fallback) {
        public Request {
            Objects.requireNonNull(player);
            target = Objects.requireNonNull(target).copyWithCount(1);
            Objects.requireNonNull(scene);
            Objects.requireNonNull(intent);
            Objects.requireNonNull(destination);
            Objects.requireNonNull(fallback);
        }

        @Override
        public ItemStack target() { return target.copy(); }
    }

    /** deliveredCount is the confirmed delivery, which can be a lower bound for INDETERMINATE. */
    public record Result(Status status, int deliveredCount) {
        public Result {
            Objects.requireNonNull(status);
            if (deliveredCount < 0) throw new IllegalArgumentException("negative delivery");
        }

        public boolean confirmedZeroDelivery() {
            return status != Status.INDETERMINATE && deliveredCount == 0;
        }
    }
}
