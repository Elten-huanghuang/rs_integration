package com.huanghuang.rsintegration.compat.ftbquests;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.CraftStorageEndpoint;
import com.huanghuang.rsintegration.storage.StorageRestockSupport;
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
        boolean locked = data == null || data.isLocked();
        boolean completed = data != null && data.isCompleted(task);
        boolean sequenceAllowed = data != null
                && ((ItemTaskSequenceAccessor) task).rsi$checkTaskSequence(data);
        long progress = data == null ? 0L : data.getProgress(task);
        long maxProgress = task.getMaxProgress();
        RSIntegrationMod.LOGGER.info(
                "[RSI-FTBQuests] Submit packet task={} locked={} screenOnly={} onlyFromCrafting={} consumes={} completed={} sequenceAllowed={} progress={}/{}",
                FtbQuestObjectId.getId(task), locked, task.isTaskScreenOnly(), task.isOnlyFromCrafting(),
                task.consumesResources(), completed, sequenceAllowed, progress, maxProgress);
        if (locked || task.isTaskScreenOnly()
                || task.isOnlyFromCrafting() || !task.consumesResources()
                || completed || !sequenceAllowed) {
            RSIntegrationMod.LOGGER.info(
                    "[RSI-FTBQuests] Native fallback task={} reason=eligibility",
                    FtbQuestObjectId.getId(task));
            return false;
        }

        long remaining = Math.max(0L, maxProgress - progress);
        if (remaining <= 0L) {
            RSIntegrationMod.LOGGER.info(
                    "[RSI-FTBQuests] Native fallback task={} reason=no-remaining-progress",
                    FtbQuestObjectId.getId(task));
            return false;
        }
        RSIntegrationMod.LOGGER.debug(
                "[RSI-FTBQuests] Explicit transaction task={} progress={}, remaining={}",
                FtbQuestObjectId.getId(task), data.getProgress(task), remaining);

        ItemStack display = displayStack(task);
        // A dedicated server may not have the item-filter display cache
        // populated yet. In that case the native FTB callback is still
        // able to scan the player's inventory with ItemTask#test; do not
        // cancel it just because RSI cannot produce a missing-item preview.
        if (display.isEmpty()) {
            RSIntegrationMod.LOGGER.info(
                    "[RSI-FTBQuests] Native fallback task={} reason=no-display-item",
                    FtbQuestObjectId.getId(task));
            return false;
        }
        if (remaining > Integer.MAX_VALUE) {
            sendMissing(player, display, remaining);
            return true;
        }

        CraftStorageEndpoint endpoint = null;
        try {
            endpoint = StorageRestockSupport.resolve(player).orElse(null);
        } catch (RuntimeException | LinkageError exception) {
            // Storage discovery is optional for explicit FTB submission. Keep
            // the inventory transaction available when a backend is broken.
            RSIntegrationMod.LOGGER.error(
                    "[RSI-FTBQuests] Storage discovery failed for task {}; using inventory only",
                    FtbQuestObjectId.getId(task), exception);
        }
        RSIntegrationMod.LOGGER.info(
                "[RSI-FTBQuests] Prepared task={} display={} endpoint={}",
                FtbQuestObjectId.getId(task), display.getHoverName().getString(), endpoint == null ? "none"
                        : endpoint.session().reference().backendId());
        INetwork network = endpoint != null && "refinedstorage".equals(
                endpoint.session().reference().backendId().value())
                ? RSIntegrationNetwork.resolveNetworkFromPlayer(player) : null;
        CraftStorageEndpoint transactionEndpoint = endpoint;
        if (endpoint != null && "refinedstorage".equals(
                endpoint.session().reference().backendId().value())
                && (network == null || !canExtract(network, player))) {
            // Do not leave an RS endpoint attached after the native permission
            // check rejects extraction; otherwise the endpoint-aware ledger
            // could bypass the intended inventory-only fallback.
            transactionEndpoint = null;
            network = null;
        }
        return submitTransaction(task, data, player, transactionEndpoint, network,
                (int) remaining, display);
    }

    /** Returns false only when no mutation occurred and FTB may safely retry natively. */
    private static boolean submitTransaction(ItemTask task, TeamData data, ServerPlayer player,
                                             @Nullable CraftStorageEndpoint endpoint,
                                             @Nullable INetwork network, int count,
                                             ItemStack display) {
        try (ExtractionLedger ledger = new ExtractionLedger()) {
            ledger.setStorageEndpoint(endpoint);
            int mark = ledger.reservationMark();
            Ingredient ingredient = new ItemTaskIngredient(task, display);
            int reserved = ledger.reserveUpToFromMainInventoryThenNetwork(
                    ingredient, count, player, network);
            if (reserved <= 0) {
                RSIntegrationMod.LOGGER.info(
                        "[RSI-FTBQuests] No ledger match for task={} endpoint={}; trying native inventory submission",
                        FtbQuestObjectId.getId(task), endpoint == null ? "none"
                                : endpoint.session().reference().backendId());
                return false;
            }
            RSIntegrationMod.LOGGER.info(
                    "[RSI-FTBQuests] Reserved task={} requested={} reserved={} endpoint={}",
                    FtbQuestObjectId.getId(task), count, reserved, endpoint == null ? "none"
                            : endpoint.session().reference().backendId());
            ExtractionLedger.ReservationToken token = ledger.tokenSince(mark);
            boolean committed;
            try (QuestInventorySubmissionContext.Scope ignored =
                         QuestInventorySubmissionContext.open()) {
                committed = ledger.commit(network, player);
            }
            if (!committed) {
                RSIntegrationMod.LOGGER.warn(
                        "[RSI-FTBQuests] Ledger commit rejected task={}; trying native inventory submission",
                        FtbQuestObjectId.getId(task));
                return false;
            }

            // Inventory extraction can synchronously trigger other FTB callbacks. Re-check
            // the lock after commit because TeamData#setProgress silently ignores writes
            // while locked; without this guard the paid items would be settled at 0 progress.
            if (data.isLocked()) {
                RSIntegrationMod.LOGGER.warn(
                        "[RSI-FTBQuests] Team data locked after reserving task {}; refunding {} item(s)",
                        FtbQuestObjectId.getId(task), reserved);
                ledger.refundCommitted(token, network, player);
                return true;
            }

            long before = data.getProgress(task);
            long expectedAccepted = QuestProgressSettlement.expectedAccepted(
                    before, task.getMaxProgress(), reserved);
            if (expectedAccepted != reserved) {
                RSIntegrationMod.LOGGER.warn(
                        "[RSI-FTBQuests] Reservation exceeds task capacity task={} reserved={} before={} max={}",
                        FtbQuestObjectId.getId(task), reserved, before, task.getMaxProgress());
                ledger.refundCommitted(token, network, player);
                sendMissing(player, display,
                        Math.max(0L, task.getMaxProgress() - before));
                return true;
            }
            long after;
            boolean reachedCompletion;
            try (QuestSubmissionAutoCompletionContext.Scope ignored =
                         QuestSubmissionAutoCompletionContext.open()) {
                data.addProgress(task, reserved);
                after = data.getProgress(task);
                reachedCompletion = data.isCompleted(task);
            }
            long accepted = Math.max(0L, after - before);
            if (accepted != reserved && !reachedCompletion) {
                RSIntegrationMod.LOGGER.error(
                        "[RSI-FTBQuests] Transaction invariant failed for task {}: reserved={}, accepted={}",
                        FtbQuestObjectId.getId(task), reserved, accepted);
                if (QuestProgressSettlement.shouldRefundRejectedProgress(accepted)) {
                    ledger.refundCommitted(token, network, player);
                    sendMissing(player, display,
                            Math.max(0L, task.getMaxProgress() - after));
                    return true;
                }
                // Preserve the established partial-progress behavior. The task
                // has already accepted part of the reservation.
                ledger.settleCommitted(token);
                sendMissing(player, display,
                        Math.max(0L, task.getMaxProgress() - after));
                return true;
            }
            ledger.settleCommitted(token);
            RSIntegrationMod.LOGGER.info(
                    "[RSI-FTBQuests] Settled task={} progress={} -> {} completed={}",
                    FtbQuestObjectId.getId(task), before, after, reachedCompletion);
            if (reachedCompletion) {
                // Run FTB's normal auto-claim/reset only after the ledger has
                // irrevocably settled the consumed items.
                data.checkAutoCompletion(task.getQuest());
            }
            long stillMissing = reachedCompletion
                    ? 0L : Math.max(0L, task.getMaxProgress() - after);
            if (stillMissing > 0L) sendMissing(player, display, stillMissing);
            return true;
        } catch (RuntimeException exception) {
            RSIntegrationMod.LOGGER.error(
                    "[RSI-FTBQuests] Explicit transaction failed for task {}",
                    FtbQuestObjectId.getId(task), exception);
            sendMissing(player, display,
                    Math.max(0L, task.getMaxProgress() - data.getProgress(task)));
            return true;
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
                    FtbQuestObjectId.getId(task), exception);
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
