package com.huanghuang.rsintegration.api.consumer;

public sealed interface ConsumerRequestAmount {
    record Executions(int count) implements ConsumerRequestAmount {
        public Executions {
            if (count <= 0) {
                throw new IllegalArgumentException("count must be positive");
            }
        }
    }

    record OutputUnits(long quantity) implements ConsumerRequestAmount {
        public OutputUnits {
            if (quantity <= 0L) {
                throw new IllegalArgumentException("quantity must be positive");
            }
        }
    }
}
