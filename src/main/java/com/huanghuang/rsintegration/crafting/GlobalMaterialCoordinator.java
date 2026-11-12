package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.crafting.endpoint.CraftStorageEndpoint;
import net.minecraft.server.MinecraftServer;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** 服务端运行期账户注册表。隔离来自实际取料，不依赖公共库存快照或玩家分组锁。 */
final class GlobalMaterialCoordinator {
    private final Map<UUID, RuntimeMaterialAccount> accounts = new LinkedHashMap<>();

    RuntimeMaterialAccount open(UUID craftId, UUID playerId, MinecraftServer server,
                                CraftStorageEndpoint endpoint) {
        if (accounts.containsKey(craftId)) throw new IllegalStateException("材料账户重复注册: " + craftId);
        RuntimeMaterialAccount account = new RuntimeMaterialAccount(craftId, playerId, server, endpoint);
        accounts.put(craftId, account);
        return account;
    }

    void release(UUID craftId) {
        RuntimeMaterialAccount account = accounts.get(craftId);
        if (account == null) return;
        account.close();
        // 结果不明的账户保留到本代服务结束，禁止重新取得同一任务的材料。
        if (!account.uncertain()) accounts.remove(craftId);
    }

    void reconciled(UUID craftId) {
        RuntimeMaterialAccount account = accounts.get(craftId);
        if (account != null && account.closed() && account.uncertain()) accounts.remove(craftId);
    }

    int size() { return accounts.size(); }
}
