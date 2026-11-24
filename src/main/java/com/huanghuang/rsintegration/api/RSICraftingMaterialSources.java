package com.huanghuang.rsintegration.api;

import com.huanghuang.rsintegration.crafting.RegisteredMaterialSources;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** 原生递归合成的外部材料源，由两种存储后端共用。 */
public final class RSICraftingMaterialSources {
    private RSICraftingMaterialSources() {}

    /** 在通用初始化阶段注册。优先使用原生库存，再按来源 ID 顺序使用扩展材料源。 */
    public static boolean register(ResourceLocation id, RSIPlanExtensions.Source toggle, Source source) {
        return RegisteredMaterialSources.register(id, toggle, source);
    }

    public interface Source {
        /** 在服务端主线程读取玩家状态，返回不依赖实时库存的供料和额度快照。 */
        Snapshot snapshot(Context context);

        /**
         * 在服务端主线程预留指定数量。返回空值表示供料不足。
         * 此处只占用供料额度，不取出物品。{@code earlier} 是同一材料源在本批次已预留的份额。
         */
        Optional<Reservation> reserve(Context context, Allocation requested, List<Allocation> earlier);
    }

    public enum Phase { PLAN, REVALIDATE, TASK }
    public record Plan(ResourceLocation recipeId, long generation) {}

    /** {@code itemTypes} 为空时查询所有变体。选定存储网络时，{@code network} 包含该网络。 */
    public record Context(ServerPlayer player, Optional<RSIPlanExtensions.Network> network,
                          Set<RSIPlanExtensions.Source> enabledSources, Set<Item> itemTypes,
                          Phase phase, Optional<Plan> plan, Optional<UUID> taskId) {
        public Context {
            Objects.requireNonNull(player);
            Objects.requireNonNull(network);
            enabledSources = Set.copyOf(enabledSources);
            itemTypes = Set.copyOf(itemTypes);
            Objects.requireNonNull(phase);
            Objects.requireNonNull(plan);
            Objects.requireNonNull(taskId);
        }
    }

    /** 额度池 ID 仅在各自的材料源内有效，多个物品变体可共用同一额度池。 */
    public record Snapshot(Map<ResourceLocation, BigInteger> balances, List<Offer> offers) {
        public static final Snapshot EMPTY = new Snapshot(Map.of(), List.of());

        public Snapshot {
            balances = Map.copyOf(balances);
            offers = List.copyOf(offers);
            if (balances.values().stream().anyMatch(value -> value.signum() < 0)) {
                throw new IllegalArgumentException("negative material balance");
            }
            for (Offer offer : offers) {
                if (offer.unitCost().signum() > 0 && !balances.containsKey(offer.pool())) {
                    throw new IllegalArgumentException("unknown material pool " + offer.pool());
                }
            }
        }
    }

    /** 免费供料只受变体数量上限限制；付费供料还会消耗相应额度池的余额。 */
    public record Offer(ItemStack target, long limit, ResourceLocation pool, BigInteger unitCost) {
        public Offer {
            target = Objects.requireNonNull(target).copyWithCount(1);
            Objects.requireNonNull(pool);
            Objects.requireNonNull(unitCost);
            if (target.isEmpty() || limit <= 0 || unitCost.signum() < 0) {
                throw new IllegalArgumentException("invalid material offer");
            }
        }

        @Override
        public ItemStack target() { return target.copy(); }
    }

    public record Allocation(ItemStack target, long count) {
        public Allocation {
            target = Objects.requireNonNull(target).copyWithCount(1);
            if (target.isEmpty() || count <= 0) throw new IllegalArgumentException("invalid material allocation");
        }

        @Override
        public ItemStack target() { return target.copy(); }
    }

    public interface Reservation {
        /** 在服务端主线程重新校验剩余预留额度，不取出材料。 */
        boolean validate();

        /** 重新校验预留后实际取料；即使只取出一部分，也要返回已取得的物品堆。 */
        Extraction commit();

        /** 释放未用的预留额度。提交后仍会调用一次；已取出的物品仍按已付费处理。 */
        void release();
    }

    /** 取料结果不明时停止自动重试；仍须返回所有已确认取出的物品堆。 */
    public record Extraction(List<ItemStack> items, boolean uncertain) {
        public Extraction {
            items = items.stream().map(ItemStack::copy).toList();
        }

        @Override
        public List<ItemStack> items() { return items.stream().map(ItemStack::copy).toList(); }
    }
}
