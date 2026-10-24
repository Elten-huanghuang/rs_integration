package com.huanghuang.rsintegration.feature.ftbquests;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.AsyncCraftChain;
import com.huanghuang.rsintegration.crafting.AsyncCraftManager;
import com.huanghuang.rsintegration.crafting.CraftingPlanningTimeoutException;
import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.endpoint.CraftStorageEndpoint;
import com.huanghuang.rsintegration.storage.StorageRestockSupport;
import com.huanghuang.rsintegration.network.RSIntegrationNetwork;
import com.huanghuang.rsintegration.network.packet.NetworkHandler;
import com.huanghuang.rsintegration.mixin.ftbquests.ItemTaskSequenceAccessor;
import com.refinedmods.refinedstorage.api.network.INetwork;
import com.refinedmods.refinedstorage.api.network.security.Permission;
import dev.ftb.mods.ftbquests.quest.TeamData;
import dev.ftb.mods.ftbquests.quest.task.ItemTask;
import net.minecraft.network.chat.Component;
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

    private enum SubmissionStatus {
        NO_MUTATION,
        SETTLED,
        HANDLED
    }

    private record SubmissionResult(SubmissionStatus status, long remaining) {
        private boolean handled() {
            return status != SubmissionStatus.NO_MUTATION;
        }

        private boolean canCraftRemaining() {
            return status == SubmissionStatus.SETTLED;
        }
    }

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
        SubmissionResult direct = submitTransaction(task, data, player, transactionEndpoint,
                (int) remaining, display);
        if (NativeSubmissionRecoveryPolicy.shouldCraftRemainder(
                direct.handled(), direct.canCraftRemaining(), direct.remaining())) {
            return startRecursiveSubmission(task, data, player, transactionEndpoint,
                    network, display, direct.handled());
        }
        if (direct.remaining() > 0L) {
            sendMissing(player, display, direct.remaining());
        }
        return direct.handled();
    }

    /**
     * Native FTB submission only consumes items already available. If that fails,
     * retain the explicit submit intent and build the same recursive graph used by
     * the quest submission planner before falling back to FTB's missing-item hint.
     */
    private static boolean startRecursiveSubmission(
            ItemTask task, TeamData data, ServerPlayer player,
            @Nullable CraftStorageEndpoint endpoint, @Nullable INetwork network,
            ItemStack display, boolean handledBeforePlanning) {
        QuestItemRequirement requirement = new QuestItemRequirement(
                FtbQuestObjectId.getId(task), display.copyWithCount(1), List.of(display.copyWithCount(1)),
                task.getMaxProgress(), data.getProgress(task), true,
                false, false, false);
        QuestSubmissionSnapshot snapshot = new QuestSubmissionSnapshot(
                FtbQuestObjectId.getId(task.getQuest()), task.getQuest().getTitle().getString(),
                display.copyWithCount(1), false, false,
                QuestSubmissionEligibility.ELIGIBLE, List.of(requirement),
                List.of(), 0, false);

        QuestSubmissionPlan plan;
        try {
            plan = FtbQuestSubmissionPlanner.plan(player, snapshot, endpoint, network);
        } catch (CraftingPlanningTimeoutException timeout) {
            player.sendSystemMessage(Component.translatable("rsi.plan.failure.planning_timeout"));
            return true;
        } catch (RuntimeException | LinkageError failure) {
            RSIntegrationMod.LOGGER.error(
                    "[RSI-FTBQuests] Recursive submit planning failed task={}",
                    FtbQuestObjectId.getId(task), failure);
            if (handledBeforePlanning) {
                sendMissing(player, display,
                        Math.max(0L, task.getMaxProgress() - data.getProgress(task)));
            }
            return handledBeforePlanning;
        }
        if (!plan.feasible() || plan.graph().nodes().isEmpty()) {
            if (handledBeforePlanning) {
                sendMissing(player, display,
                        Math.max(0L, task.getMaxProgress() - data.getProgress(task)));
            }
            return handledBeforePlanning;
        }

        try {
            AsyncCraftChain chain = new AsyncCraftChain(
                    player.getUUID(), player.getServer(), endpoint, plan.graph());
            chain.onDone(() -> {
                ServerPlayer current = player.getServer().getPlayerList().getPlayer(player.getUUID());
                if (current == null) return;
                if (chain.state() != AsyncCraftChain.State.COMPLETED) {
                    current.sendSystemMessage(Component.translatable(
                            "rsi.ftb_quest.error.crafting_failed", chain.abortReason()));
                    return;
                }
                TeamData currentData = TeamData.get(current);
                long currentRemaining = currentData == null ? 0L
                        : Math.max(0L, task.getMaxProgress() - currentData.getProgress(task));
                if (currentData == null || currentRemaining <= 0L) {
                    if (currentRemaining > 0L) sendMissing(current, display, currentRemaining);
                    return;
                }
                SubmissionResult finalSubmission = submitTransaction(
                        task, currentData, current, endpoint,
                        (int) Math.min(Integer.MAX_VALUE, currentRemaining), display);
                if (finalSubmission.remaining() > 0L) {
                    sendMissing(current, display, finalSubmission.remaining());
                }
            });
            boolean completed = AsyncCraftManager.getInstance()
                    .submitAndAdvance(chain, player.getServer());
            if (!completed) {
                player.sendSystemMessage(Component.translatable(
                    "rsi.ftb_quest.info.crafting_started", plan.graph().nodes().size()));
            }
            return true;
        } catch (RuntimeException | LinkageError failure) {
            RSIntegrationMod.LOGGER.error(
                    "[RSI-FTBQuests] Failed to start recursive submit task={}",
                    FtbQuestObjectId.getId(task), failure);
            if (handledBeforePlanning) {
                sendMissing(player, display,
                        Math.max(0L, task.getMaxProgress() - data.getProgress(task)));
            }
            return handledBeforePlanning;
        }
    }

    private static SubmissionResult submitTransaction(
            ItemTask task, TeamData data, ServerPlayer player,
            @Nullable CraftStorageEndpoint endpoint, int count, ItemStack display) {
        try (ExtractionLedger ledger = new ExtractionLedger()) {
            ledger.setStorageEndpoint(endpoint);
            int mark = ledger.reservationMark();
            Ingredient ingredient = new ItemTaskIngredient(task, display);
            int reserved = ledger.reserveUpToFromMainInventoryThenEndpoint(
                    ingredient, count, player, endpoint);
            if (reserved <= 0) {
                RSIntegrationMod.LOGGER.info(
                        "[RSI-FTBQuests] No ledger match for task={} endpoint={}; trying native inventory submission",
                        FtbQuestObjectId.getId(task), endpoint == null ? "none"
                                : endpoint.session().reference().backendId());
                return new SubmissionResult(SubmissionStatus.NO_MUTATION, count);
            }
            RSIntegrationMod.LOGGER.info(
                    "[RSI-FTBQuests] Reserved task={} requested={} reserved={} endpoint={}",
                    FtbQuestObjectId.getId(task), count, reserved, endpoint == null ? "none"
                            : endpoint.session().reference().backendId());
            ExtractionLedger.ReservationToken token = ledger.tokenSince(mark);
            boolean committed;
            try (QuestInventorySubmissionContext.Scope ignored =
                         QuestInventorySubmissionContext.open()) {
                committed = ledger.commitToEndpoint(endpoint, player);
            }
            if (!committed) {
                RSIntegrationMod.LOGGER.warn(
                        "[RSI-FTBQuests] Ledger commit rejected task={}; trying native inventory submission",
                        FtbQuestObjectId.getId(task));
                return new SubmissionResult(SubmissionStatus.NO_MUTATION,
                        Math.max(0L, task.getMaxProgress() - data.getProgress(task)));
            }

            // Inventory extraction can synchronously trigger other FTB callbacks. Re-check
            // the lock after commit because TeamData#setProgress silently ignores writes
            // while locked; without this guard the paid items would be settled at 0 progress.
            if (data.isLocked()) {
                RSIntegrationMod.LOGGER.warn(
                        "[RSI-FTBQuests] Team data locked after reserving task {}; refunding {} item(s)",
                        FtbQuestObjectId.getId(task), reserved);
                ledger.refundCommittedToEndpoint(token, endpoint, player);
                return new SubmissionResult(SubmissionStatus.HANDLED,
                        Math.max(0L, task.getMaxProgress() - beforeProgress(data, task)));
            }

            long before = beforeProgress(data, task);
            long expectedAccepted = QuestProgressSettlement.expectedAccepted(
                    before, task.getMaxProgress(), reserved);
            if (expectedAccepted != reserved) {
                RSIntegrationMod.LOGGER.warn(
                        "[RSI-FTBQuests] Reservation exceeds task capacity task={} reserved={} before={} max={}",
                        FtbQuestObjectId.getId(task), reserved, before, task.getMaxProgress());
                ledger.refundCommittedToEndpoint(token, endpoint, player);
                return new SubmissionResult(SubmissionStatus.HANDLED,
                        Math.max(0L, task.getMaxProgress() - before));
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
                    ledger.refundCommittedToEndpoint(token, endpoint, player);
                    return new SubmissionResult(SubmissionStatus.HANDLED,
                            Math.max(0L, task.getMaxProgress() - after));
                }
                // Preserve the established partial-progress behavior. The task
                // has already accepted part of the reservation.
                ledger.settleCommitted(token);
                return new SubmissionResult(SubmissionStatus.HANDLED,
                        Math.max(0L, task.getMaxProgress() - after));
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
            return new SubmissionResult(SubmissionStatus.SETTLED, stillMissing);
        } catch (RuntimeException exception) {
            RSIntegrationMod.LOGGER.error(
                    "[RSI-FTBQuests] Explicit transaction failed for task {}",
                    FtbQuestObjectId.getId(task), exception);
            return new SubmissionResult(SubmissionStatus.HANDLED,
                    Math.max(0L, task.getMaxProgress() - beforeProgress(data, task)));
        }
    }

    private static long beforeProgress(TeamData data, ItemTask task) {
        return data == null ? 0L : data.getProgress(task);
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
