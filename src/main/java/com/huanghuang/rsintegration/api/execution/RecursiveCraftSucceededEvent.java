package com.huanghuang.rsintegration.api.execution;

import com.huanghuang.rsintegration.api.IntegrationResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.eventbus.api.Event;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** 原生递归任务成功结算后，在服务端 Forge 事件总线上发送。 */
public final class RecursiveCraftSucceededEvent extends Event {
    private final ServerPlayer player;
    private final UUID taskId;
    private final Map<IntegrationResourceKey, Long> producedItems;

    public RecursiveCraftSucceededEvent(ServerPlayer player, UUID taskId,
                                        Map<IntegrationResourceKey, Long> producedItems) {
        this.player = Objects.requireNonNull(player);
        this.taskId = Objects.requireNonNull(taskId);
        this.producedItems = Map.copyOf(producedItems);
    }

    public ServerPlayer player() { return player; }
    public UUID taskId() { return taskId; }

    /** 按实际物品及 NBT 汇总；包括后续步骤消耗的产物，不含退料。 */
    public Map<IntegrationResourceKey, Long> producedItems() { return producedItems; }
}
