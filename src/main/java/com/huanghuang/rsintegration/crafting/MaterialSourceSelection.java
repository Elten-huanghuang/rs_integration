package com.huanghuang.rsintegration.crafting;

/** 原材料来源偏好；作用域仅覆盖一次同步规划或合成推进，退出后恢复。 */
public record MaterialSourceSelection(boolean inventory, boolean storageNetwork, boolean backpacks) {
    public static final MaterialSourceSelection ALL = new MaterialSourceSelection(true, true, true);
    private static final ThreadLocal<MaterialSourceSelection> CURRENT = new ThreadLocal<>();

    public enum Source { INVENTORY, STORAGE_NETWORK, BACKPACKS }

    public boolean includes(Source source) {
        return switch (source) {
            case INVENTORY -> inventory;
            case STORAGE_NETWORK -> storageNetwork;
            case BACKPACKS -> backpacks;
        };
    }

    public MaterialSourceSelection toggle(Source source) {
        return new MaterialSourceSelection(source == Source.INVENTORY ? !inventory : inventory,
                source == Source.STORAGE_NETWORK ? !storageNetwork : storageNetwork,
                source == Source.BACKPACKS ? !backpacks : backpacks);
    }

    public int mask() {
        return (inventory ? 1 : 0) | (storageNetwork ? 2 : 0) | (backpacks ? 4 : 0);
    }

    public static MaterialSourceSelection fromMask(int mask) {
        if ((mask & ~7) != 0) throw new IllegalArgumentException("invalid material sources");
        return new MaterialSourceSelection((mask & 1) != 0, (mask & 2) != 0, (mask & 4) != 0);
    }

    public static MaterialSourceSelection current() {
        MaterialSourceSelection selection = CURRENT.get();
        return selection == null ? ALL : selection;
    }

    public Scope enter() {
        MaterialSourceSelection previous = CURRENT.get();
        CURRENT.set(this);
        return () -> {
            if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
        };
    }

    public interface Scope extends AutoCloseable {
        @Override void close();
    }
}
