package com.huanghuang.rsintegration.crafting.graph;

public sealed interface MaterialSource permits MaterialSource.InitialPool, MaterialSource.ProducerOutput {

    record InitialPool(MaterialKey key) implements MaterialSource {
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
