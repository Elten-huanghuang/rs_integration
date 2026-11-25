package com.huanghuang.rsintegration.feature.ftbquests;

import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.endpoint.CraftStorageEndpoint;
import com.huanghuang.rsintegration.util.PlayerUtils;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;

import java.util.ArrayList;
import java.util.List;

/** 在各目标结算前暂存从库存提取的任务物品。 */
final class QuestSubmissionEscrow implements AutoCloseable {

    record Entry(long taskId, ItemStack stack, ExtractionLedger.ReservationToken token) {}

    private final ExtractionLedger ledger = ExtractionLedger.external();
    private final List<Entry> entries = new ArrayList<>();
    private final ServerPlayer player;
    private final CraftStorageEndpoint endpoint;
    private boolean committed;

    QuestSubmissionEscrow(ServerPlayer player, CraftStorageEndpoint endpoint) {
        this.player = player;
        this.endpoint = endpoint;
        this.ledger.setStorageEndpoint(this.endpoint);
    }

    boolean reserve(long taskId, Ingredient ingredient, int count) {
        int mark = ledger.reservationMark();
        // 与规划阶段相同，合并网络和背包的材料，避免两边各有一部分时提交失败。
        ItemStack stack = endpoint == null ? ledger.reserveFromInventory(ingredient, count, player)
                : ledger.reserve(ingredient, count, endpoint, player, null, null);
        if (stack.isEmpty()) return false;
        entries.add(new Entry(taskId, stack.copy(), ledger.tokenSince(mark)));
        return true;
    }

    boolean commit() {
        committed = ledger.commitToEndpoint(endpoint, player);
        return committed;
    }

    Entry entry(long taskId) {
        if (!committed) throw new IllegalStateException("escrow is not committed");
        return entries.stream().filter(entry -> entry.taskId() == taskId).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown task " + taskId));
    }

    void settle(Entry entry, ItemStack unconsumed) {
        if (!committed) throw new IllegalStateException("escrow is not committed");
        ledger.settleCommitted(entry.token());
        refund(unconsumed);
    }

    private void refund(ItemStack stack) {
        if (stack.isEmpty()) return;
        ItemStack remainder = endpoint == null ? stack
                : CraftStorageEndpoint.insertInternal(endpoint, player, stack, false)
                        .remainder().orElse(ItemStack.EMPTY);
        if (!remainder.isEmpty()) {
            PlayerUtils.safeGiveToStorage(player, remainder, null);
        }
    }

    @Override
    public void close() {
        if (!committed) {
            ledger.close();
            return;
        }
        if (ledger.size() > 0) ledger.rollback(player);
    }
}
