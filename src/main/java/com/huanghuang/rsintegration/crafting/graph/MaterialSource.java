package com.huanghuang.rsintegration.crafting.graph;

import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;

public sealed interface MaterialSource permits MaterialSource.InitialPool, MaterialSource.ProducerOutput {

    record InitialPool(MaterialKey key, @Nullable ResourceLocation extensionId) implements MaterialSource {
        public InitialPool(MaterialKey key) { this(key, null); }

        public InitialPool {
            if (key == null) throw new NullPointerException("key");
        }
    }

    record ProducerOutput(OutputPortId outputPort) implements MaterialSource {
        public ProducerOutput {
            if (outputPort == null) throw new NullPointerException("outputPort");
        }
    }
}
