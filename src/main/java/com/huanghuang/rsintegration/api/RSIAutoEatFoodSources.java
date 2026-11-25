package com.huanghuang.rsintegration.api;

import com.huanghuang.rsintegration.feature.autoeat.AutoEatFoodBatch;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/** 服务端一键进食的外部食物来源，自 Integration API 1.4 起可用。 */
public final class RSIAutoEatFoodSources {
    private RSIAutoEatFoodSources() {}

    /** 在通用初始化阶段注册，重复 ID 返回 {@code false}。 */
    public static boolean register(ResourceLocation id, Source source) {
        return AutoEatFoodBatch.register(id, source);
    }

    /** {@code wantedItems} 为空时查询全部食物。两个回调均在服务端主线程执行。 */
    public record Context(ServerPlayer player, Set<Item> wantedItems) {
        public Context {
            Objects.requireNonNull(player);
            wantedItems = Set.copyOf(wantedItems);
        }
    }

    public interface Source {
        /** 每批只读枚举一次，每个可供应的变体返回一个数量为 1 的精确模板。 */
        List<ItemStack> candidates(Context context);

        /** 重新检查并结算一份食物，仅通过返回结果转移食物的所有权。 */
        TakeResult takeOne(Context context, ItemStack exactTemplate);
    }

    public sealed interface TakeResult {
        /** 独立且数量为 1 的物品栈，完整序列化身份（含 Forge 能力数据）须与模板一致。 */
        record Taken(ItemStack stack) implements TakeResult {}
        /** 未收费且未交付，RSI 可以尝试下一来源。 */
        record Unavailable() implements TakeResult {}
        /** 收费或交付结果不明，RSI 停止整个请求。 */
        record Indeterminate() implements TakeResult {}
    }
}
