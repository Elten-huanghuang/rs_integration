package com.huanghuang.rsintegration.api.consumer;

import com.huanghuang.rsintegration.api.ApiVersion;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Optional;

/**
 * RSI Consumer API 的公共入口。
 *
 * <p>Consumer API 用于查询配方目录、创建库存检查会话、提交订单、查询状态和取消订单。
 * 所有带 {@link ServerPlayer} 的方法都必须在服务端主线程调用，并且只能使用调用方自己的
 * 玩家上下文。</p>
 *
 * <p>返回的 {@link ConsumerResult} 同时包含业务值和稳定诊断。调用方不得根据异常文本
 * 或本地化显示名判断结果。</p>
 */
public interface RSICraftConsumerApi {
    int API_MAJOR = 1;
    int API_MINOR = 0;

    /**
     * 返回当前公共 API 版本。
     */
    ApiVersion version();

    /**
     * 返回消费者服务状态。只有 {@link ConsumerServiceState#READY} 可以接受新请求。
     */
    ConsumerServiceState state();

    /**
     * 返回当前服务端限制。
     */
    ConsumerCapabilities capabilities();

    /**
     * 查询当前玩家可选择的存储后端。
     */
    ConsumerResult<List<ConsumerStorageOption>> storageOptions(
            ServerPlayer player
    );

    /**
     * 分页查询配方目录。
     */
    ConsumerResult<ConsumerRecipeCatalogPage> findRecipes(
            ServerPlayer player,
            ConsumerRecipeCatalogRequest request
    );

    /**
     * 创建一次固定库存与规划快照的 inspection。
     */
    ConsumerResult<ConsumerInspectionTicket> openInspection(
            ServerPlayer player,
            ConsumerInspectionRequest request
    );

    /**
     * 使用同一 ticket 拉取下一页 inspection 结果。
     */
    ConsumerResult<ConsumerInspectionPage> nextInspectionPage(
            ServerPlayer player,
            ConsumerInspectionTicketId ticketId
    );

    /**
     * 主动关闭一个 inspection ticket。
     */
    ConsumerResult<ConsumerAck> closeInspection(
            ServerPlayer player,
            ConsumerInspectionTicketId ticketId
    );

    /**
     * 丢弃一个尚未提交且仍有效的 plan。
     */
    ConsumerResult<ConsumerAck> discardPlan(
            ServerPlayer player,
            ResourceLocation consumerId,
            ConsumerPlanId planId
    );

    /**
     * 提交单个订单。
     *
     * <p>幂等键由 player、consumerId 和 externalRequestId 共同组成。网络重试必须复用
     * 同一 externalRequestId；新订单必须使用新 ID。</p>
     */
    ConsumerResult<ConsumerOrderReceipt> submit(
            ServerPlayer player,
            ConsumerOrderRequest request
    );

    /**
     * 原子提交一组订单。批次中每个订单仍保留自己的幂等结果。
     */
    ConsumerResult<ConsumerBatchOrderReceipt> submitBatch(
            ServerPlayer player,
            ConsumerBatchOrderRequest request
    );

    /**
     * 查询当前玩家拥有的订单状态。
     */
    ConsumerResult<ConsumerOrderStatus> status(
            ServerPlayer player,
            ResourceLocation consumerId,
            ConsumerOrderId orderId
    );

    /**
     * 请求取消订单。
     *
     * <p>返回成功表示取消请求已被接受，最终状态仍应以 {@link #status} 为准。</p>
     */
    ConsumerResult<ConsumerCancelResult> cancel(
            ServerPlayer player,
            ResourceLocation consumerId,
            ConsumerOrderId orderId,
            ConsumerExternalRequestId cancellationRequestId
    );

    /**
     * 获取 Consumer API 入口。RSI 未加载时返回空。
     */
    static Optional<RSICraftConsumerApi> get() {
        return RSIConsumerServices.current();
    }
}
