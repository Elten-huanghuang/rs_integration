package com.huanghuang.rsintegration.machine;

import com.huanghuang.rsintegration.network.binding.BindingInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

import java.util.Objects;

/** 机器实时状态在客户端缓存与服务端差异比较中的统一身份。 */
public record MachineStatusKey(ResourceLocation dimension, BlockPos pos) {
    public MachineStatusKey {
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(pos, "pos");
        pos = pos.immutable();
    }

    public static MachineStatusKey of(BindingInfo info) {
        Objects.requireNonNull(info, "info");
        return new MachineStatusKey(info.dim(), info.pos());
    }
}
