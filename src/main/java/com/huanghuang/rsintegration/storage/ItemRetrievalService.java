package com.huanghuang.rsintegration.storage;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.api.RSIItemRetrieval.*;
import com.huanghuang.rsintegration.api.RSIItemRetrievalFallback;
import com.huanghuang.rsintegration.crafting.endpoint.CraftStorageEndpoint;
import com.huanghuang.rsintegration.crafting.endpoint.SessionCraftStorageEndpoint;
import com.huanghuang.rsintegration.storage.rs.RefinedStorageBackend;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.items.ItemHandlerHelper;
import net.minecraftforge.items.wrapper.PlayerMainInvWrapper;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/** 原生 JEI 取物和世界选块共用的交付与返还流程。 */
public final class ItemRetrievalService {
    private ItemRetrievalService() {}

    public static Result retrieve(Request request) {
        ServerPlayer player = request.player();
        StorageThreadGuard.requireServerThread(player);
        ItemStack target = request.target();
        boolean worldPick = request.scene() == Scene.WORLD_PICK;
        if (target.isEmpty() || request.count() <= 0 || player instanceof FakePlayer || player.isSpectator()
                || (worldPick && (player.isCreative() || !(request.destination() instanceof SelectedHotbar)))
                || (request.intent() == Intent.TOP_UP && !(request.destination() instanceof SelectedHotbar))) {
            return result(Status.INVALID_REQUEST);
        }

        int delivered = 0;
        boolean mutating = false;
        try {
            var delivery = new Delivery(request, target);
            if (!delivery.valid()) return result(Status.INVALID_REQUEST);
            StorageResolutionResult resolution = StorageRestockSupport.resolveForRetrieval(player);
            if (!resolution.resolved()) return result(switch (resolution.status()) {
                case DENIED -> Status.NO_PERMISSION;
                case FAILED, INVALID_REFERENCE -> Status.BACKEND_FAILURE;
                default -> Status.NO_NETWORK;
            });
            CraftStorageEndpoint endpoint = new SessionCraftStorageEndpoint(resolution.session().orElseThrow());
            Status permission = permissionFailure(endpoint, request);
            if (permission != null) return result(permission);
            StorageSession session = endpoint.session();
            StorageItemKey key = session.itemKey(target);
            int capacity = delivery.capacity(target, existing -> key.equals(session.itemKey(existing)));
            if (capacity == 0) return result(Status.NO_SPACE);
            int requested = switch (request.intent()) {
                case SINGLE -> 1;
                case STACK -> target.getMaxStackSize();
                case COUNT -> request.count();
                case TOP_UP -> Math.min(request.count(), capacity);
            };
            int amount = Math.min(requested, capacity);

            if (worldPick) {
                StorageOperationResult simulated = endpoint.extractExact(player, target, amount, true);
                if (StorageRestockSupport.confirmedMissing(simulated)) {
                    return missing(request, delivery, endpoint, requested, amount);
                }
                if (simulated.status() != StorageOperationStatus.SUCCESS
                        && simulated.status() != StorageOperationStatus.PARTIAL) {
                    return result(extractionFailure(simulated, false));
                }
                amount = (int) Math.min(amount, simulated.transferredAmount().orElse(0));
                if (amount <= 0) return result(Status.BACKEND_FAILURE);
            }

            mutating = true;
            StorageOperationResult extraction = endpoint.extractExact(player, target, amount, false);
            if (StorageRestockSupport.confirmedMissing(extraction)) {
                return missing(request, delivery, endpoint, requested, amount);
            }
            boolean known = StorageRestockSupport.refund(endpoint, player, extraction.recoveryStacks(), "recovery");
            List<ItemStack> stacks = extraction.extractedStacks();
            boolean validFragments;
            try {
                validFragments = validFragments(session, key, extraction, amount, stacks);
            } catch (RuntimeException | LinkageError failure) {
                RSIntegrationMod.LOGGER.warn("[RSI-Retrieval] Could not validate extracted fragments", failure);
                validFragments = false;
            }
            if (!validFragments) {
                known &= StorageRestockSupport.refund(endpoint, player, stacks, "invalid exact extraction");
                return result(known ? extractionFailure(extraction, true) : Status.INDETERMINATE);
            }

            List<ItemStack> remainder = new ArrayList<>();
            boolean deliveryFailed = false;
            for (ItemStack fragment : stacks) {
                if (deliveryFailed) {
                    remainder.add(fragment);
                    continue;
                }
                int toDeliver = 0;
                int inserted = 0;
                try {
                    toDeliver = Math.min(delivery.capacity(fragment, existing -> sameStack(fragment, existing), delivered > 0),
                            fragment.getCount());
                    if (toDeliver > 0) inserted = delivery.insert(fragment.copyWithCount(toDeliver));
                    delivered += inserted;
                } catch (RuntimeException | LinkageError failure) {
                    RSIntegrationMod.LOGGER.warn("[RSI-Retrieval] Fragment delivery failed", failure);
                    deliveryFailed = true;
                    known = false;
                    // 插入一旦开始，只能安全返还尚未尝试插入的部分。
                    inserted = toDeliver;
                }
                if (inserted < fragment.getCount()) remainder.add(fragment.copyWithCount(fragment.getCount() - inserted));
            }
            known &= StorageRestockSupport.refund(endpoint, player, remainder, "destination remainder");
            if (delivered > 0) {
                delivery.sync();
                if (worldPick && "refinedstorage".equals(endpoint.session().reference().backendId().value())) {
                    RefinedStorageEnergy.drain(endpoint.session().reference(), player);
                }
            }
            if (!known || extraction.transferredAmount().isEmpty()) {
                return new Result(Status.INDETERMINATE, delivered);
            }
            if (delivered > 0) return delivered(requested, delivered);
            if (extraction.status() != StorageOperationStatus.SUCCESS
                    && extraction.status() != StorageOperationStatus.PARTIAL) {
                return new Result(extractionFailure(extraction, true), delivered);
            }
            return result(Status.NO_SPACE);
        } catch (RuntimeException | LinkageError failure) {
            RSIntegrationMod.LOGGER.warn("[RSI-Retrieval] Could not retrieve {}", target, failure);
            return new Result(mutating ? Status.INDETERMINATE : Status.BACKEND_FAILURE, delivered);
        }
    }

    static boolean validFragments(StorageSession session, StorageItemKey requestedKey,
                                  StorageOperationResult extraction, int requested, List<ItemStack> fragments) {
        if (extraction.kind() != StorageOperationResult.Kind.EXTRACT
                || extraction.mode() != StorageOperationMode.PERFORM || extraction.requestedAmount() != requested) {
            return false;
        }
        long total = 0;
        for (ItemStack fragment : fragments) {
            if (!requestedKey.equals(session.itemKey(fragment))) return false;
            total += fragment.getCount();
            if (total > requested) return false;
        }
        return extraction.transferredAmount().orElse(total) == total;
    }

    private static Status permissionFailure(CraftStorageEndpoint endpoint, ServerPlayer player,
                                            StoragePermission permission) {
        return switch (endpoint.session().checkPermission(player, permission).status()) {
            case ALLOWED -> null;
            case DENIED -> Status.NO_PERMISSION;
            case UNAVAILABLE -> Status.NO_NETWORK;
            case FAILED -> Status.BACKEND_FAILURE;
        };
    }

    private static Status permissionFailure(CraftStorageEndpoint endpoint, Request request) {
        Status failure = permissionFailure(endpoint, request.player(), StoragePermission.EXTRACT);
        return failure == null && request.scene() == Scene.WORLD_PICK
                ? permissionFailure(endpoint, request.player(), StoragePermission.VIEW) : failure;
    }

    private static Status extractionFailure(StorageOperationResult extraction, boolean performed) {
        if (performed && extraction.transferredAmount().isEmpty()) return Status.INDETERMINATE;
        return switch (extraction.status()) {
            case DENIED -> Status.NO_PERMISSION;
            case UNAVAILABLE -> Status.NO_NETWORK;
            default -> Status.BACKEND_FAILURE;
        };
    }

    private static Result missing(Request request, Delivery delivery, CraftStorageEndpoint endpoint,
                                  int requested, int amount) {
        if (request.fallback() != Fallback.REGISTERED) return result(Status.OUT_OF_STOCK);
        if (delivery.capacity(delivery.target, existing -> sameStack(delivery.target, existing)) < amount) {
            return result(Status.NO_SPACE);
        }
        Status permission = permissionFailure(endpoint, request);
        if (permission != null) return result(permission);
        var scene = request.scene() == Scene.JEI
                ? RSIItemRetrievalFallback.Scene.JEI : RSIItemRetrievalFallback.Scene.WORLD_PICK;
        RSIItemRetrievalFallback.Destination destination;
        int destinationIndex;
        if (request.destination() instanceof Cursor cursor) {
            destination = RSIItemRetrievalFallback.Destination.CURSOR;
            destinationIndex = cursor.containerId();
        } else if (request.destination() instanceof SelectedHotbar hotbar) {
            destination = RSIItemRetrievalFallback.Destination.SELECTED_HOTBAR_SLOT;
            destinationIndex = hotbar.slot();
        } else {
            destination = RSIItemRetrievalFallback.Destination.PLAYER_INVENTORY;
            destinationIndex = -1;
        }
        var reference = endpoint.session().reference();
        int actual = ItemRetrievalFallbacks.tryOnMissing(new RSIItemRetrievalFallback.Request(
                request.player(), delivery.target, amount, scene, destination,
                reference.backendId().value(), reference.networkId(), request.intent(), destinationIndex));
        if (actual < 0) return result(Status.INDETERMINATE);
        if (actual == 0) return result(Status.OUT_OF_STOCK);
        delivery.sync();
        return delivered(requested, actual);
    }

    private static Result result(Status status) { return new Result(status, 0); }

    private static Result delivered(int requested, int count) {
        return new Result(count == requested ? Status.SUCCESS : Status.PARTIAL, count);
    }

    private static boolean sameStack(ItemStack left, ItemStack right) {
        return ItemStack.isSameItemSameTags(left, right) && left.areCapsCompatible(right);
    }

    private static final class Delivery {
        private final Request request;
        private final ItemStack target;
        private final ServerPlayer player;
        private final AbstractContainerMenu menu;

        Delivery(Request request, ItemStack target) {
            this.request = request;
            this.target = target;
            this.player = request.player();
            this.menu = player.containerMenu;
        }

        boolean valid() {
            if (request.destination() instanceof Cursor cursor) {
                return menu != null && player.containerMenu == menu && menu.containerId == cursor.containerId()
                        && (!player.isCreative() || menu != player.inventoryMenu)
                        && menu.stillValid(player);
            }
            if (request.destination() instanceof SelectedHotbar hotbar) {
                return hotbar.slot() >= 0 && hotbar.slot() < 9 && player.getInventory().selected == hotbar.slot();
            }
            return true;
        }

        int capacity(ItemStack incoming, Predicate<ItemStack> compatible) {
            return capacity(incoming, compatible, false);
        }

        int capacity(ItemStack incoming, Predicate<ItemStack> compatible, boolean continuing) {
            if (!valid()) return 0;
            if (request.destination() instanceof Inventory) {
                long space = player.getInventory().items.stream()
                        .mapToLong(existing -> stackSpace(incoming, existing, compatible)).sum();
                return (int) Math.min(Integer.MAX_VALUE, space);
            }
            ItemStack existing = current();
            if (request.destination() instanceof SelectedHotbar) {
                if (request.intent() == Intent.TOP_UP && existing.isEmpty()) return 0;
                if (!continuing && request.scene() == Scene.WORLD_PICK && request.intent() != Intent.TOP_UP
                        && !existing.isEmpty()) return 0;
            }
            return stackSpace(incoming, existing, compatible);
        }

        private int stackSpace(ItemStack incoming, ItemStack existing, Predicate<ItemStack> compatible) {
            int limit = Math.min(incoming.getMaxStackSize(), player.getInventory().getMaxStackSize());
            if (existing.isEmpty()) return limit;
            return compatible.test(existing) ? Math.max(0,
                    Math.min(limit, existing.getMaxStackSize()) - existing.getCount()) : 0;
        }

        private ItemStack current() {
            return request.destination() instanceof Cursor ? menu.getCarried() : player.getInventory().getSelected();
        }

        int insert(ItemStack stack) {
            if (request.destination() instanceof Inventory) {
                int count = stack.getCount();
                ItemStack remainder = ItemHandlerHelper.insertItemStacked(
                        new PlayerMainInvWrapper(player.getInventory()), stack, false);
                return count - remainder.getCount();
            }
            ItemStack existing = current();
            ItemStack updated = existing.isEmpty() ? stack : existing.copyWithCount(existing.getCount() + stack.getCount());
            if (request.destination() instanceof SelectedHotbar hotbar) player.getInventory().setItem(hotbar.slot(), updated);
            else menu.setCarried(updated);
            return stack.getCount();
        }

        void sync() {
            try {
                player.getInventory().setChanged();
                player.containerMenu.broadcastChanges();
                if (request.destination() instanceof SelectedHotbar hotbar) {
                    if (player.containerMenu != player.inventoryMenu) player.inventoryMenu.broadcastChanges();
                    player.connection.send(new ClientboundContainerSetSlotPacket(
                            ClientboundContainerSetSlotPacket.PLAYER_INVENTORY, 0,
                            hotbar.slot(), player.getInventory().getItem(hotbar.slot())));
                }
            } catch (RuntimeException | LinkageError failure) {
                RSIntegrationMod.LOGGER.warn("[RSI-Retrieval] Could not sync confirmed delivery", failure);
            }
        }
    }

    /** 只在确认使用 RS 后端时调用，避免仅安装超越维度的服务端加载 RS 类型。 */
    private static final class RefinedStorageEnergy {
        static void drain(StorageReference reference, ServerPlayer player) {
            try {
                var network = RefinedStorageBackend.resolveNativeNetwork(reference, player);
                if (network == null || network.getNetworkItemManager() == null) return;
                int cost = com.refinedmods.refinedstorage.RS.SERVER_CONFIG.getWirelessGrid().getExtractUsage();
                network.getNetworkItemManager().drainEnergy(player, cost);
            } catch (RuntimeException | LinkageError failure) {
                RSIntegrationMod.LOGGER.warn("[RSI-Retrieval] Energy drain failed after delivery", failure);
            }
        }
    }
}
