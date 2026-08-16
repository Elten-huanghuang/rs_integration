package com.huanghuang.rsintegration.compat.ftbquests;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.network.RSIntegrationNetwork;
import com.huanghuang.rsintegration.network.packet.NetworkHandler;
import com.huanghuang.rsintegration.mixin.ftbquests.ItemTaskSequenceAccessor;
import com.refinedmods.refinedstorage.api.network.INetwork;
import com.refinedmods.refinedstorage.api.network.security.Permission;
import dev.ftb.mods.ftbquests.quest.TeamData;
import dev.ftb.mods.ftbquests.quest.task.ItemTask;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraftforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.util.List;
import java.util.stream.Stream;

/** Atomically owns inventory-first item consumption for an explicit FTB submit click. */
public final class NativeItemTaskSubmissionService {
    private NativeItemTaskSubmissionService() {}

    /** Returns true when the native submit callback must be cancelled. */
    public static boolean handleExplicitSubmission(ItemTask task, TeamData data, ServerPlayer player) {
        if (data == null || data.isLocked() || task.isTaskScreenOnly()
                || task.isOnlyFromCrafting() || !task.consumesResources()
                || data.isCompleted(task)
                || !((ItemTaskSequenceAccessor) task).rsi$checkTaskSequence(data)) return false;

        long remaining = Math.max(0L, task.getMaxProgress() - data.getProgress(task));
        if (remaining <= 0L) return false;
        RSIntegrationMod.LOGGER.debug(
                "[RSI-FTBQuests] Explicit transaction task={} progress={}, remaining={}",
                task.getId(), data.getProgress(task), remaining);

        ItemStack display = displayStack(task);
        // A dedicated server may not have the item-filter display cache
        // populated yet. In that case the native FTB callback is still
        // able to scan the player's inventory with ItemTask#test; do not
        // cancel it just because RSI cannot produce a missing-item preview.
        if (display.isEmpty()) return false;
        if (remaining > Integer.MAX_VALUE) {
            sendMissing(player, display, remaining);
            return true;
        }

        INetwork network = RSIntegrationNetwork.resolveNetworkFromPlayer(player);
        if (network != null && !canExtract(network, player)) network = null;
        submitTransaction(task, data, player, network, (int) remaining, display);
        return true;
    }

    private static void submitTransaction(ItemTask task, TeamData data, ServerPlayer player,
                                          @Nullable INetwork network, int count,
                                          ItemStack display) {
        try (ExtractionLedger ledger = new ExtractionLedger()) {
            int mark = ledger.reservationMark();
            Ingredient ingredient = new ItemTaskIngredient(task, display);
            int reserved = ledger.reserveUpToFromMainInventoryThenNetwork(
                    ingredient, count, player, network);
            if (reserved <= 0) {
                sendMissing(player, display, count);
                return;
            }
            ExtractionLedger.ReservationToken token = ledger.tokenSince(mark);
            if (!ledger.commit(network, player)) {
                sendMissing(player, display, count);
                return;
            }

            // Inventory extraction can synchronously trigger other FTB callbacks. Re-check
            // the lock after commit because TeamData#setProgress silently ignores writes
            // while locked; without this guard the paid items would be settled at 0 progress.
            if (data.isLocked()) {
                RSIntegrationMod.LOGGER.warn(
                        "[RSI-FTBQuests] Team data locked after reserving task {}; refunding {} item(s)",
                        task.getId(), reserved);
                ledger.refundCommitted(token, network, player);
                return;
            }

            long before = data.getProgress(task);
            data.addProgress(task, reserved);
            long accepted = data.getProgress(task) - before;
            if (accepted != reserved) {
                RSIntegrationMod.LOGGER.error(
                        "[RSI-FTBQuests] Transaction invariant failed for task {}: reserved={}, accepted={}",
                        task.getId(), reserved, accepted);
                if (QuestProgressSettlement.shouldRefundRejectedProgress(accepted)) {
                    ledger.refundCommitted(token, network, player);
                    sendMissing(player, display,
                            Math.max(0L, task.getMaxProgress() - data.getProgress(task)));
                    return;
                }
                // A partial write may already have fired completion side effects. The token
                // cannot be split safely here, so keep the conservative paid settlement.
                ledger.settleCommitted(token);
                return;
            }
            ledger.settleCommitted(token);
            long stillMissing = Math.max(0L, task.getMaxProgress() - data.getProgress(task));
            if (stillMissing > 0L) sendMissing(player, display, stillMissing);
        } catch (RuntimeException exception) {
            RSIntegrationMod.LOGGER.error(
                    "[RSI-FTBQuests] Explicit transaction failed for task {}", task.getId(), exception);
            sendMissing(player, display,
                    Math.max(0L, task.getMaxProgress() - data.getProgress(task)));
        }
    }

    private static boolean canExtract(INetwork network, ServerPlayer player) {
        var security = network.getSecurityManager();
        return security == null || security.hasPermission(Permission.EXTRACT, player);
    }

    private static ItemStack displayStack(ItemTask task) {
        try {
            List<ItemStack> valid = task.getValidDisplayItems();
            for (ItemStack stack : valid) if (!stack.isEmpty()) return stack.copyWithCount(1);
        } catch (RuntimeException exception) {
            RSIntegrationMod.LOGGER.debug(
                    "[RSI-FTBQuests] Failed to resolve display items for task {}; using native submit",
                    task.getId(), exception);
        }
        ItemStack configured = task.getItemStack();
        return configured.isEmpty() ? ItemStack.EMPTY : configured.copyWithCount(1);
    }

    private static void sendMissing(ServerPlayer player, ItemStack display, long count) {
        if (display.isEmpty() || count <= 0L) return;
        NetworkHandler.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new QuestMissingBookmarkPacket(display, count));
    }

    /** Uses FTB Quests' matcher, including item filters and its configured NBT semantics. */
    private static final class ItemTaskIngredient extends Ingredient {
        private final ItemTask task;

        private ItemTaskIngredient(ItemTask task, ItemStack display) {
            super(Stream.of(new ItemValue(display)));
            this.task = task;
        }

        @Override
        public boolean test(@Nullable ItemStack stack) {
            return stack != null && task.test(stack);
        }
    }
}
