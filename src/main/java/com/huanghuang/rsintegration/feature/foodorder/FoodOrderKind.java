package com.huanghuang.rsintegration.feature.foodorder;

import java.util.Locale;

/** 点餐列表里一道菜的可用性状态。 */
public enum FoodOrderKind {

    WAITING(0),
    READY(1),
    MISSING(2),
    NO_MACHINE(3),
    LOCKED(4),
    NORECIPE(5),
    INDEXING(6);

    private final int wireId;

    FoodOrderKind(int wireId) {
        this.wireId = wireId;
    }

    public int wireId() {
        return wireId;
    }

    public boolean orderable() {
        return this == READY;
    }

    /** 列表排序权重：可下单的排最前，其次缺材料、缺机器，其余靠后。 */
    public int rank() {
        return switch (this) {
            case READY -> 0;
            case MISSING -> 1;
            case NO_MACHINE -> 2;
            case WAITING -> 3;
            case LOCKED -> 4;
            case NORECIPE -> 5;
            case INDEXING -> 6;
        };
    }

    public String statusKey() {
        return "rsi.food_order.status." + name().toLowerCase(Locale.ROOT);
    }

    public String detailKey() {
        return "rsi.food_order.detail." + name().toLowerCase(Locale.ROOT);
    }

    public static FoodOrderKind fromWire(int wireId) {
        for (FoodOrderKind kind : values()) {
            if (kind.wireId == wireId) {
                return kind;
            }
        }
        return WAITING;
    }
}
