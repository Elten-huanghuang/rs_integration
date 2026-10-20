package com.huanghuang.rsintegration.network.binding;

import net.minecraft.network.FriendlyByteBuf;

import javax.annotation.Nullable;
import java.util.Objects;
import java.util.UUID;

/**
 * 服务端机器绑定的稳定标识。
 *
 * <p>端点 ID 只在首次绑定时生成，后续更新方块身份、显示名或终端副本时保持不变。
 * 它独立于方块坐标，供崩溃恢复和后续异步任务引用具体机器端点。</p>
 */
public record MachineEndpointId(UUID value) {
    public MachineEndpointId {
        Objects.requireNonNull(value, "value");
    }

    public static MachineEndpointId create() {
        return new MachineEndpointId(UUID.randomUUID());
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeUUID(value);
    }

    public static MachineEndpointId decode(FriendlyByteBuf buffer) {
        return new MachineEndpointId(buffer.readUUID());
    }

    @Nullable
    public static MachineEndpointId fromNullable(@Nullable UUID value) {
        return value == null ? null : new MachineEndpointId(value);
    }
}
