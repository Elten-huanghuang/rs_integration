package com.huanghuang.rsintegration.api;

import com.huanghuang.rsintegration.storage.ItemRetrievalService;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.Objects;

/** 原生 JEI 取物和世界选块共用的服务端取物入口。 */
public final class RSIItemRetrieval {
    private RSIItemRetrieval() {}

    /** 在服务端主线程调用。提示消息和收藏反馈由调用方处理。 */
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
     * 服务端校验菜单光标时所用的预期容器 ID。
     * 创造模式物品栏的光标沿用调用方自己的客户端确认流程。
     */
    public record Cursor(int containerId) implements Destination {}
    /** 预期选中的快捷栏槽位，范围为 0 至 8。 */
    public record SelectedHotbar(int slot) implements Destination {}

    /**
     * {@code COUNT} 和 {@code TOP_UP} 不超过 {@code count}；{@code SINGLE} 取一个，{@code STACK} 取一整组。
     * 世界选块只支持当前选中的快捷栏槽位；补齐时槽位中必须已有匹配的物品堆。
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

    /** {@code deliveredCount} 是已确认的交付量；结果不明时可能只是实际交付量的下限。 */
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
