package com.huanghuang.rsintegration.mods.goety.magic;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;

/** 读写灵魂图腾物品内的灵魂数量，不改变图腾本身的物品身份。 */
public final class GoetySoulTotem {
    public static final String SOULS = "Souls";
    public static final String MAX_SOULS = "Max Souls";
    private static final String TOTEM = "com.Polarice3.Goety.api.items.magic.ITotem";
    private static final String TOTEM_ITEM = "com.Polarice3.Goety.common.items.magic.TotemOfSouls";


    private static final java.util.Set<ItemStack> testTotems = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

    private GoetySoulTotem() {}

    /** 测试使用已注册物品模拟图腾，只标记当前物品堆。 */
    public static ItemStack markTestTotem(ItemStack stack) {
        testTotems.add(stack);
        return stack;
    }

    public static boolean isTotem(ItemStack stack) {
        return stack != null && !stack.isEmpty() && (testTotems.contains(stack) || matches(stack.getItem().getClass()));
    }

    private static boolean matches(Class<?> type) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            if (TOTEM.equals(current.getName()) || TOTEM_ITEM.equals(current.getName()) || implementsTotem(current)) return true;
        }
        return false;
    }



    private static boolean implementsTotem(Class<?> type) {
        for (Class<?> implemented : type.getInterfaces()) {
            if (TOTEM.equals(implemented.getName()) || implementsTotem(implemented)) return true;
        }
        return false;
    }

    public static int souls(ItemStack stack) {
        CompoundTag tag = isTotem(stack) ? stack.getTag() : null;
        return tag == null ? 0 : Math.max(0, tag.getInt(SOULS));
    }

    public static int capacity(ItemStack stack) {
        CompoundTag tag = isTotem(stack) ? stack.getTag() : null;
        return tag == null ? 0 : Math.max(0, tag.getInt(MAX_SOULS));
    }

    public static int space(ItemStack stack) { return Math.max(0, capacity(stack) - souls(stack)); }

    public static int insert(ItemStack stack, int requested, boolean perform) {
        int accepted = Math.min(Math.max(0, requested), space(stack));
        if (perform && accepted > 0) stack.getOrCreateTag().putInt(SOULS, Math.addExact(souls(stack), accepted));
        return accepted;
    }

    public static int extract(ItemStack stack, int requested, boolean perform) {
        int taken = Math.min(Math.max(0, requested), souls(stack));
        if (perform && taken > 0) stack.getOrCreateTag().putInt(SOULS, souls(stack) - taken);
        return taken;
    }

    /** 直接写入余额；不是图腾时不做任何事。 */
    public static void set(ItemStack stack, int amount) {
        if (!isTotem(stack)) return;
        stack.getOrCreateTag().putInt(SOULS, Math.max(0, amount));
    }
}
