package com.huanghuang.rsintegration.mods.summoningrituals;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.IItemHandlerModifiable;

import java.util.ArrayList;
import java.util.List;

/** 通过可写能力操作动态槽位，避免原祭坛的插入接口触发玩家交互。 */
final class SummoningRitualInventoryOps {
    private static final String ALTAR_INVENTORY_CLASS =
            "com.almostreliable.summoningrituals.inventory.AltarInventory";
    private static final String AMOUNT_TAG = "YuushaAmount";
    private static final String ORIGINAL_NAME_TAG = "srfix_original_name";

    record Inserted(int slot, int amount) {}

    private SummoningRitualInventoryOps() {}

    static int catalystSlot(IItemHandler handler) {
        return handler == null ? -1 : handler.getSlots() - 1;
    }

    static boolean valid(IItemHandler handler) {
        return handler instanceof IItemHandlerModifiable && handler.getSlots() >= 1;
    }

    static ItemStack insertCatalyst(IItemHandler handler, ItemStack stack,
                                    List<Inserted> inserted) {
        if (!valid(handler) || stack.isEmpty()) return stack.copy();
        int slot = catalystSlot(handler);
        // 旧催化剂必须先成功回收，不能覆盖它，也不能通过 insertItem 尝试激活。
        if (!handler.getStackInSlot(slot).isEmpty() || !handler.isItemValid(slot, stack)) {
            return stack.copy();
        }
        ((IItemHandlerModifiable) handler).setStackInSlot(slot, stack.copyWithCount(1));
        inserted.add(new Inserted(slot, 1));
        return stack.copyWithCount(stack.getCount() - 1);
    }

    /** 还原 SRfix 普通槽位的真实数量和原始 NBT；催化剂不使用压缩表示。 */
    static ItemStack stackInSlot(IItemHandler handler, int slot) {
        ItemStack stack = handler.getStackInSlot(slot).copy();
        CompoundTag tag = stack.getTag();
        if (slot == catalystSlot(handler) || tag == null
                || !tag.contains(AMOUNT_TAG, Tag.TAG_ANY_NUMERIC)) return stack;

        int amount = Math.max(0, tag.getInt(AMOUNT_TAG));
        tag.remove(AMOUNT_TAG);
        String originalName = tag.getString(ORIGINAL_NAME_TAG);
        tag.remove(ORIGINAL_NAME_TAG);
        CompoundTag display = tag.getCompound("display");
        if (originalName.isEmpty()) display.remove("Name");
        else display.putString("Name", originalName);
        if (display.isEmpty()) tag.remove("display");
        else tag.put("display", display);
        if (tag.isEmpty()) stack.setTag(null);
        stack.setCount(amount);
        return stack;
    }

    static ItemStack insertMaterial(IItemHandler handler, ItemStack stack,
                                    List<Inserted> inserted) {
        if (!valid(handler) || stack.isEmpty()) return stack.copy();
        ItemStack remaining = stack.copy();
        int catalystSlot = catalystSlot(handler);

        // 先合并完全相同的物品和 NBT，再使用空槽位。
        for (int slot = 0; slot < catalystSlot && !remaining.isEmpty(); slot++) {
            ItemStack existing = stackInSlot(handler, slot);
            if (existing.isEmpty() || !ItemStack.isSameItemSameTags(existing, remaining)) continue;
            remaining = insertIntoSlot(handler, slot, remaining, inserted);
        }
        for (int slot = 0; slot < catalystSlot && !remaining.isEmpty(); slot++) {
            if (!handler.getStackInSlot(slot).isEmpty()) continue;
            remaining = insertIntoSlot(handler, slot, remaining, inserted);
        }
        return remaining;
    }

    private static ItemStack insertIntoSlot(IItemHandler handler, int slot, ItemStack stack,
                                            List<Inserted> inserted) {
        if (!handler.isItemValid(slot, stack)) return stack;
        ItemStack current = stackInSlot(handler, slot);
        int limit = materialSlotLimit(handler, slot);
        int accepted = Math.min(stack.getCount(), Math.max(0, limit - current.getCount()));
        if (accepted <= 0) return stack;

        // SRfix 的 insertItem 连 simulate=true 也会写入库存；准备时直接写指定槽位。
        ItemStack combined = stack.copyWithCount(current.getCount() + accepted);
        setMaterial(handler, slot, combined);
        inserted.add(new Inserted(slot, accepted));
        return stack.copyWithCount(stack.getCount() - accepted);
    }

    private static int materialSlotLimit(IItemHandler handler, int slot) {
        // SRfix 支持普通材料超堆叠，原模组报告的 64 个上限不反映这一能力。
        return isAltarInventory(handler) ? Integer.MAX_VALUE : handler.getSlotLimit(slot);
    }

    private static boolean isAltarInventory(IItemHandler handler) {
        for (Class<?> type = handler.getClass(); type != null; type = type.getSuperclass()) {
            if (ALTAR_INVENTORY_CLASS.equals(type.getName())) return true;
        }
        return false;
    }

    private static void setMaterial(IItemHandler handler, int slot, ItemStack stack) {
        CompoundTag existingTag = handler.getStackInSlot(slot).getTag();
        if (!stack.isEmpty() && stack.getCount() > 1 && (isAltarInventory(handler)
                || existingTag != null && existingTag.contains(AMOUNT_TAG, Tag.TAG_ANY_NUMERIC))) {
            // 原版 ItemStack 保存数量使用字节；沿用 SRfix 表示，避免超堆叠在重载后截断。
            int amount = stack.getCount();
            CompoundTag tag = stack.getOrCreateTag();
            if (stack.hasCustomHoverName()) {
                tag.putString(ORIGINAL_NAME_TAG, Component.Serializer.toJson(stack.getHoverName()));
            }
            tag.putInt(AMOUNT_TAG, amount);
            stack.setCount(1);
            stack.setHoverName(Component.literal("§e[共 " + amount + " 个] §f")
                    .append(Component.translatable(stack.getItem().getDescriptionId())));
        }
        ((IItemHandlerModifiable) handler).setStackInSlot(slot, stack);
    }

    static List<ItemStack> rollbackInsertions(IItemHandler handler, List<Inserted> inserted) {
        List<ItemStack> recovered = new ArrayList<>();
        for (int i = inserted.size() - 1; i >= 0; i--) {
            Inserted record = inserted.get(i);
            int remaining = record.amount();
            while (remaining > 0) {
                ItemStack extracted = extractPart(handler, record.slot(), remaining);
                if (extracted.isEmpty()) break;
                recovered.add(extracted);
                remaining -= extracted.getCount();
            }
        }
        inserted.clear();
        return recovered;
    }

    static List<ItemStack> extractSlot(IItemHandler handler, int slot, boolean simulate) {
        List<ItemStack> extracted = new ArrayList<>();
        if (!valid(handler) || slot < 0 || slot >= handler.getSlots()) return extracted;
        ItemStack current = stackInSlot(handler, slot);
        if (current.isEmpty()) return extracted;
        if (!simulate) {
            // 原祭坛的催化剂 extractItem 恒为空，必须通过可写能力清槽才能回收。
            ((IItemHandlerModifiable) handler).setStackInSlot(slot, ItemStack.EMPTY);
        }
        int remaining = current.getCount();
        while (remaining > 0) {
            int count = Math.min(remaining, current.getMaxStackSize());
            extracted.add(current.copyWithCount(count));
            remaining -= count;
        }
        return extracted;
    }

    private static ItemStack extractPart(IItemHandler handler, int slot, int amount) {
        if (!valid(handler)) return ItemStack.EMPTY;
        ItemStack current = stackInSlot(handler, slot);
        if (current.isEmpty()) return ItemStack.EMPTY;
        int count = Math.min(amount, Math.min(current.getCount(), current.getMaxStackSize()));
        ItemStack extracted = current.copyWithCount(count);
        ItemStack remaining = current.copyWithCount(current.getCount() - count);
        if (slot == catalystSlot(handler)) {
            ((IItemHandlerModifiable) handler).setStackInSlot(slot, remaining);
        } else {
            setMaterial(handler, slot, remaining);
        }
        return extracted;
    }
}
