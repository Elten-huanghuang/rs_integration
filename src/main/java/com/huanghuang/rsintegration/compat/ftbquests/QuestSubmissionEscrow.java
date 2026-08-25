package com.huanghuang.rsintegration.compat.ftbquests;

import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.CraftStorageEndpoints;
import com.huanghuang.rsintegration.crafting.CraftStorageEndpoint;
import com.huanghuang.rsintegration.util.PlayerUtils;
import com.refinedmods.refinedstorage.api.network.INetwork;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;

import java.util.ArrayList;
import java.util.List;

/** Owns items extracted for a quest until each task has accepted or rejected them. */
final class QuestSubmissionEscrow implements AutoCloseable {

    record Entry(long taskId, ItemStack stack, ExtractionLedger.ReservationToken token) {}

    private final ExtractionLedger ledger = new ExtractionLedger();
    private final List<Entry> entries = new ArrayList<>();
    private final ServerPlayer player;
    private final INetwork network;
    private final CraftStorageEndpoint endpoint;
    private boolean committed;

    QuestSubmissionEscrow(ServerPlayer player, CraftStorageEndpoint endpoint, INetwork network) {
        this.player = player;
        this.network = network;
        this.endpoint = endpoint;
        this.ledger.setStorageEndpoint(endpoint);
    }

    boolean reserve(long taskId, Ingredient ingredient, int count) {
        int mark = ledger.reservationMark();
        ItemStack stack = endpoint != null
                ? ledger.reserveFromEndpoint(ingredient, count, endpoint, player)
                : ledger.reserveFromNetwork(ingredient, count, network, player);
        if (stack.isEmpty()) stack = ledger.reserveFromInventory(ingredient, count, player);
        if (stack.isEmpty()) return false;
        entries.add(new Entry(taskId, stack.copy(), ledger.tokenSince(mark)));
        return true;
    }

    boolean commit() {
        committed = ledger.commit(network, player);
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
        ItemStack remainder = ledgerStorageInsert(stack);
        if (!remainder.isEmpty()) PlayerUtils.safeGiveToPlayer(player, remainder, network);
    }

    private ItemStack ledgerStorageInsert(ItemStack stack) {
        if (endpoint != null) {
            return endpoint.insert(player, stack, false)
                    .remainder().orElse(ItemStack.EMPTY);
        }
        if (network != null) {
            return CraftStorageEndpoints.fromLegacyNetwork(network)
                    .insert(player, stack, false).remainder().orElse(ItemStack.EMPTY);
        }
        return stack.copy();
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
