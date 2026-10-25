package com.huanghuang.rsintegration.api.recipe;

public record OutputAmount(long min, long max) {
    public OutputAmount {
        if (min < 0L || max < min) {
            throw new IllegalArgumentException("invalid output amount range");
        }
        if (max == 0L) {
            throw new IllegalArgumentException("output amount range must not be empty");
        }
    }

    public static OutputAmount exactly(long amount) {
        return new OutputAmount(amount, amount);
    }
}
