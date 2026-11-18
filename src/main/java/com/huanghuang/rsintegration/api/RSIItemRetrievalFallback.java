package com.huanghuang.rsintegration.api;

import com.huanghuang.rsintegration.storage.ItemRetrievalFallbacks;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.Objects;

/** 原生 JEI 取物或世界选块明确缺货后的服务端后备处理器。 */
public final class RSIItemRetrievalFallback {
    private RSIItemRetrievalFallback() {}

    /** 在模组初始化时注册。同一 ID 只接受一次；按 ID 排序调用，而非初始化顺序。 */
    public static boolean register(ResourceLocation id, Handler handler) {
        return ItemRetrievalFallbacks.register(id, handler);
    }

    @FunctionalInterface
    public interface Handler {
        /** 仅在服务端主线程调用。必须自行校验来源和实际交付条件；PASS 不得产生副作用。 */
        Result tryDeliver(Request request);
    }

    public enum Scene { JEI, WORLD_PICK }
    public enum Destination { PLAYER_INVENTORY, SELECTED_HOTBAR_SLOT }
    public enum Reason { OUT_OF_STOCK }

    /** 原生已通过权限及交付目标检查，且实际交付为零；目标仍须在实际交付时重验。 */
    public record Request(ServerPlayer player, ItemStack target, int requestedCount, Scene scene,
                          Destination destination, String backendId, String networkId) {
        public Request {
            Objects.requireNonNull(player);
            Objects.requireNonNull(scene);
            Objects.requireNonNull(destination);
            Objects.requireNonNull(backendId);
            Objects.requireNonNull(networkId);
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
