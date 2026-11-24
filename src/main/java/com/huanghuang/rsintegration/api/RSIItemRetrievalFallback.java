package com.huanghuang.rsintegration.api;

import com.huanghuang.rsintegration.storage.ItemRetrievalFallbacks;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.Objects;
import java.util.Set;

/** 原生 JEI 取物或世界选块明确缺货后的服务端后备处理器。 */
public final class RSIItemRetrievalFallback {
    private RSIItemRetrievalFallback() {}

    /** 在模组初始化时注册。同一 ID 只接受一次；按 ID 排序调用，而非初始化顺序。 */
    public static boolean register(ResourceLocation id, Handler handler) {
        return ItemRetrievalFallbacks.register(id, handler);
    }

    /** 显式声明支持的交付目标，以及是否能补齐已有匹配物品的快捷栏槽位。 */
    public static boolean register(ResourceLocation id, Set<Destination> destinations, boolean supportsTopUp,
                                   Handler handler) {
        return ItemRetrievalFallbacks.register(id, destinations, supportsTopUp, handler);
    }

    @FunctionalInterface
    public interface Handler {
        /** 仅在服务端主线程调用。必须自行校验来源和实际交付条件；PASS 不得产生副作用。 */
        Result tryDeliver(Request request);
    }

    public enum Scene { JEI, WORLD_PICK }
    public enum Destination { PLAYER_INVENTORY, SELECTED_HOTBAR_SLOT, CURSOR }
    public enum Reason { OUT_OF_STOCK }

    /**
     * 原生已通过权限及交付目标检查，且实际交付为零。实际交付时重验目标。
     * destinationIndex 为光标对应的菜单 ID 或选中的快捷栏索引。背包使用 -1。
     */
    public record Request(ServerPlayer player, ItemStack target, int requestedCount, Scene scene,
                          Destination destination, String backendId, String networkId,
                          RSIItemRetrieval.Intent intent, int destinationIndex) {
        public Request(ServerPlayer player, ItemStack target, int requestedCount, Scene scene,
                       Destination destination, String backendId, String networkId) {
            this(player, target, requestedCount, scene, destination, backendId, networkId,
                    RSIItemRetrieval.Intent.STACK, destination == Destination.SELECTED_HOTBAR_SLOT
                            ? player.getInventory().selected : -1);
        }

        public Request {
            Objects.requireNonNull(player);
            Objects.requireNonNull(scene);
            Objects.requireNonNull(destination);
            Objects.requireNonNull(backendId);
            Objects.requireNonNull(networkId);
            Objects.requireNonNull(intent);
            target = Objects.requireNonNull(target).copyWithCount(1);
            if (target.isEmpty() || requestedCount <= 0) throw new IllegalArgumentException("invalid target or count");
        }

        @Override
        public ItemStack target() { return target.copy(); }

        public Reason reason() { return Reason.OUT_OF_STOCK; }

        public int nativeDeliveredCount() { return 0; }
    }

    public sealed interface Result permits Pass, Delivered {
        Result PASS = Pass.INSTANCE;
    }

    public enum Pass implements Result { INSTANCE }

    /** 后备处理器已经完成交付；不得报告模拟结果或超过请求数量。 */
    public record Delivered(int actualCount) implements Result {
        public Delivered {
            if (actualCount <= 0) throw new IllegalArgumentException("delivery must be positive");
        }
    }
}
