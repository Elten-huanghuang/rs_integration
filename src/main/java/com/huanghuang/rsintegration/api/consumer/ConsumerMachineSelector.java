package com.huanghuang.rsintegration.api.consumer;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

import java.util.Objects;

public sealed interface ConsumerMachineSelector {
    record AutomaticBound() implements ConsumerMachineSelector {}

    record Exact(
            ResourceLocation dimension,
            BlockPos position
    ) implements ConsumerMachineSelector {
        public Exact {
            Objects.requireNonNull(dimension, "dimension");
            position = Objects.requireNonNull(position, "position").immutable();
        }
    }

    record Preferred(
            ResourceLocation dimension,
            BlockPos position,
            boolean allowAutomaticFallback
    ) implements ConsumerMachineSelector {
        public Preferred {
            Objects.requireNonNull(dimension, "dimension");
            position = Objects.requireNonNull(position, "position").immutable();
        }
    }
}
