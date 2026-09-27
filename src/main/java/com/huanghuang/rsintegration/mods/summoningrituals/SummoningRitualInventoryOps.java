package com.huanghuang.rsintegration.mods.summoningrituals;

import com.mojang.logging.LogUtils;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.IItemHandlerModifiable;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

/** 只通过 IItemHandler 操作祭坛动态槽位。 */
final class SummoningRitualInventoryOps {
    private static final Logger LOGGER = LogUtils.getLogger();

    record Inserted(int slot, int amount) {}

    private SummoningRitualInventoryOps() {}

    static int catalystSlot(IItemHandler handler) {
        return handler == null ? -1 : handler.getSlots() - 1;
    }

    static boolean valid(IItemHandler handler) {
        return handler != null && handler.getSlots() >= 1;
    }

    static ItemStack insertCatalyst(IItemHandler handler, ItemStack stack,
                                    List<Inserted> inserted) {
        int slot = catalystSlot(handler);
        if (slot < 0 || stack.isEmpty()) return stack.copy();
        ItemStack one = stack.copyWithCount(1);
        ItemStack simulated = handler.insertItem(slot, one, true);
        if (!simulated.isEmpty()) return stack.copy();

        List<ItemStack> ordinaryBefore = snapshotOrdinarySlots(handler, slot);
        ItemStack remainder = handler.insertItem(slot, one, false);
        int accepted = remainder.isEmpty() ? 1 : Math.max(0, 1 - remainder.getCount());

        // 某些版本的祭坛在 insertItem 中转发到玩家交互，可能忽略传入槽位。
        // 规定的 insertItem 调用必须保留，但不能只相信它的返回值，必须校验动态末槽。
        ItemStack installed = handler.getStackInSlot(slot);
        if (ItemStack.isSameItemSameTags(installed, one) && installed.getCount() >= 1) {
            if (accepted > 0) inserted.add(new Inserted(slot, accepted));
            return stack.copyWithCount(stack.getCount() - 1);
        }

        // 能力实现误把物品放入普通槽时，只回收本次调用造成的增量，
        // 不碰调用前已经存在的同类材料，然后再通过能力接口写入动态末槽。
        removeCatalystMisroute(handler, slot, one, ordinaryBefore);
        installed = handler.getStackInSlot(slot);
        if (!(ItemStack.isSameItemSameTags(installed, one) && installed.getCount() >= 1)) {
            LOGGER.warn(
                    "[RSI-SummoningRituals] Catalyst insert ignored slot={} handler={} installed={} expected={}; repairing via capability",
                    slot, handler.getClass().getName(), installed, one);
            try {
                if (!(handler instanceof IItemHandlerModifiable modifiable)) {
                    return stack.copy();
                }
                modifiable.setStackInSlot(slot, one.copy());
            } catch (RuntimeException exception) {
                LOGGER.error(
                        "[RSI-SummoningRituals] Failed repairing catalyst slot={}", slot, exception);
                return stack.copy();
            }
            installed = handler.getStackInSlot(slot);
            if (!ItemStack.isSameItemSameTags(installed, one) || installed.getCount() < 1) {
                return stack.copy();
            }
        }
        inserted.add(new Inserted(slot, 1));
        LOGGER.info(
                "[RSI-SummoningRituals] Catalyst installed slot={} item={} handlerSlot={} ordinarySlots={}",
                slot, one.getItem(), handler.getStackInSlot(slot), describeOrdinarySlots(handler, slot));
        return stack.copyWithCount(stack.getCount() - 1);
    }

    private static List<ItemStack> snapshotOrdinarySlots(IItemHandler handler, int catalystSlot) {
        List<ItemStack> result = new ArrayList<>(Math.max(0, catalystSlot));
        for (int slot = 0; slot < catalystSlot; slot++) {
            result.add(handler.getStackInSlot(slot).copy());
        }
        return result;
    }

    private static void removeCatalystMisroute(IItemHandler handler, int catalystSlot,
                                               ItemStack catalyst,
                                               List<ItemStack> ordinaryBefore) {
        for (int slot = 0; slot < catalystSlot; slot++) {
            ItemStack before = slot < ordinaryBefore.size()
                    ? ordinaryBefore.get(slot) : ItemStack.EMPTY;
            ItemStack after = handler.getStackInSlot(slot);
            if (!ItemStack.isSameItemSameTags(after, catalyst)
                    || ItemStack.matches(before, after)) continue;
            ItemStack extracted = handler.extractItem(slot, 1, false);
            if (!extracted.isEmpty()) return;
        }
    }

    private static List<ItemStack> describeOrdinarySlots(IItemHandler handler, int catalystSlot) {
        List<ItemStack> result = new ArrayList<>();
        for (int slot = 0; slot < catalystSlot; slot++) {
            ItemStack stack = handler.getStackInSlot(slot);
            if (!stack.isEmpty()) result.add(stack.copy());
        }
        return result;
    }

    static ItemStack insertMaterial(IItemHandler handler, ItemStack stack,
                                    List<Inserted> inserted) {
        if (!valid(handler) || stack.isEmpty()) return stack.copy();
        ItemStack remaining = stack.copy();
        int catalystSlot = catalystSlot(handler);

        // 先合并完全相同的物品和 NBT，再使用空槽位。
        for (int slot = 0; slot < catalystSlot && !remaining.isEmpty(); slot++) {
            ItemStack existing = handler.getStackInSlot(slot);
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
        ItemStack simulated = handler.insertItem(slot, stack, true);
        int simulatedAccepted = stack.getCount() - simulated.getCount();
        if (simulatedAccepted <= 0) return stack;

        ItemStack actual = handler.insertItem(slot, stack, false);
        int accepted = stack.getCount() - actual.getCount();
        if (accepted > 0) inserted.add(new Inserted(slot, accepted));
        return actual;
    }

    static List<ItemStack> rollbackInsertions(IItemHandler handler, List<Inserted> inserted) {
        List<ItemStack> recovered = new ArrayList<>();
        for (int i = inserted.size() - 1; i >= 0; i--) {
            Inserted record = inserted.get(i);
            int remaining = record.amount();
            while (remaining > 0) {
                ItemStack extracted = handler.extractItem(record.slot(), remaining, false);
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
        // simulate=true 不能推进槽位状态，因此只预览一次；实际提取循环处理超堆叠。
        if (simulate) {
            ItemStack preview = handler.extractItem(slot, Integer.MAX_VALUE, true);
            if (!preview.isEmpty()) extracted.add(preview);
            return extracted;
        }
        while (!handler.getStackInSlot(slot).isEmpty()) {
            ItemStack part = handler.extractItem(slot, Integer.MAX_VALUE, false);
            if (part.isEmpty()) break;
            extracted.add(part);
        }
        return extracted;
    }
}
