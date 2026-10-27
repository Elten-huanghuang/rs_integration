package com.huanghuang.rsintegration.api.resonance;

import com.huanghuang.rsintegration.api.ApiVersion;
import net.minecraft.server.level.ServerPlayer;

import java.util.Optional;

/**
 * RSI Resonance Inventory Access API。
 *
 * <p>该 API 让第三方模组把共振盘中的内容作为额外的玩家物品来源读取或使用。
 * 共振盘仍是独立存储，不会被伪装成玩家主背包槽位或全局 Forge
 * {@code ITEM_HANDLER}。所有带玩家的调用必须在服务端主线程执行。</p>
 */
public interface RSIResonanceApi {
    int API_MAJOR = 1;
    int API_MINOR = 0;

    ApiVersion version();

    /**
     * 打开当前玩家可见共振盘的短生命周期快照。
     *
     * <p>返回的 {@link ResonanceInventory} 只能用于当前请求或当前 tick，
     * 不应跨 tick、跨玩家或跨线程缓存。</p>
     */
    ResonanceResult<ResonanceInventory> openInventory(ServerPlayer player);

    static Optional<RSIResonanceApi> get() {
        return RSIResonanceServices.current();
    }
}
