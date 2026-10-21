package com.huanghuang.rsintegration.machine.bd;

import com.huanghuang.rsintegration.crafting.endpoint.CraftStorageEndpoint;
import com.huanghuang.rsintegration.crafting.endpoint.CraftStorageEndpoints;
import com.huanghuang.rsintegration.machine.FurnaceMachineAccess;
import com.huanghuang.rsintegration.machine.MachineSlotType;
import com.huanghuang.rsintegration.network.gui.MachineInteractionAccess;
import com.huanghuang.rsintegration.network.binding.BindingEventHandler;
import com.huanghuang.rsintegration.network.binding.BindingStorage;
import com.huanghuang.rsintegration.network.binding.MachineBindingService;
import com.huanghuang.rsintegration.network.binding.MachineUnbindService;
import com.huanghuang.rsintegration.network.binding.BindingInfo;
import com.huanghuang.rsintegration.network.binding.MachineBindingInfoFactory;
import com.huanghuang.rsintegration.storage.StorageBackendId;
import com.huanghuang.rsintegration.storage.StorageOperationResult;
import com.huanghuang.rsintegration.storage.StorageReference;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.registries.ForgeRegistries;
import com.huanghuang.rsintegration.network.packet.NetworkHandler;
import com.huanghuang.rsintegration.network.machine.bd.BeyondDimensionsBindingSyncPacket;
import com.huanghuang.rsintegration.network.machine.MachineNetworkHandler;
import com.huanghuang.rsintegration.network.binding.BindingSyncCodec;
import com.huanghuang.rsintegration.util.CuriosAccess;
import net.minecraftforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/** Server-side machine-center operations for BD-bound machines. */
public final class BeyondDimensionsMachineOperations {
    private static final ResourceLocation BD_TERMINAL =
            ResourceLocation.fromNamespaceAndPath("beyonddimensions", "net_terminal_item");
    private static final StorageBackendId BD_BACKEND = new StorageBackendId("beyonddimensions");
    private static final AtomicLong BINDING_SYNC_GENERATION = new AtomicLong(1L);

    private BeyondDimensionsMachineOperations() {}

    public static void collect(ServerPlayer player, ResourceLocation dim, BlockPos pos,
                                boolean toNetwork) {
        ServerLevel level = resolveTarget(player, dim, pos);
        if (level == null) return;
        CraftStorageEndpoint endpoint = null;
        if (toNetwork) {
            endpoint = resolveEndpoint(player, dim, pos).orElse(null);
            if (endpoint == null) {
                player.sendSystemMessage(Component.translatable("rsi.generic.error.network_unavailable"));
                return;
            }
        }

        BlockEntity be = level.getBlockEntity(pos);
        FurnaceMachineAccess.Result extraction =
                FurnaceMachineAccess.extractOutput(be);
        if (!extraction.success()) {
            Component error = FurnaceMachineAccess.errorMessage(
                    extraction.error());
            if (error != null) player.sendSystemMessage(error);
            return;
        }
        ItemStack output = extraction.stack();

        if (toNetwork) {
            StorageOperationResult result = endpoint.insert(player, output, false);
            ItemStack remainder = result.remainder().orElse(output);
            if (!remainder.isEmpty()) player.drop(remainder, false);
        } else if (!player.getInventory().add(output)) {
            player.drop(output, false);
        }
        player.sendSystemMessage(Component.translatable(
                "rsi.machine.collected", output.getCount(), output.getHoverName()));
    }

    public static void insert(ServerPlayer player, ResourceLocation dim, BlockPos pos,
                              MachineSlotType slot) {
        ServerLevel level = resolveTarget(player, dim, pos);
        if (level == null) return;
        BlockEntity be = level.getBlockEntity(pos);
        FurnaceMachineAccess.Result insertion =
                FurnaceMachineAccess.insertCarried(player, be, slot);
        if (!insertion.success()) {
            Component error = FurnaceMachineAccess.errorMessage(
                    insertion.error());
            if (error != null) player.sendSystemMessage(error);
        }
    }

    public static void unbind(ServerPlayer player, ResourceLocation dim, BlockPos pos) {
        MachineUnbindService.Result result =
                MachineUnbindService.unbind(player, dim, pos);
        player.sendSystemMessage(result.message());
        sendBindingSync(player);
    }

    /** Refresh the binding cache using the mixed RS/BD path when available. */
    public static void sendBindingSync(ServerPlayer player) {
        // The RS side-panel sync already contains both RS and BD bindings in
        // mixed installations. Do not replace that shared cache with a
        // BD-only snapshot.
        if (ModList.get().isLoaded("refinedstorage")) {
            try {
                MachineNetworkHandler
                        .sendBindingSync(player);
            } catch (Exception | LinkageError ignored) {
                // The standalone packet below remains the fallback for a
                // partially initialized optional RS environment.
            }
            return;
        }
        List<BindingInfo> bindings = MachineBindingService
                .collectPlayerEndpoints(player).stream()
                .filter(endpoint -> BD_TERMINAL.equals(endpoint.itemKey()))
                .map(endpoint -> MachineBindingInfoFactory.fromEndpoint(
                        endpoint, BD_TERMINAL.toString()))
                .toList();
        long generation = BINDING_SYNC_GENERATION.incrementAndGet();
        for (BindingSyncCodec.Payload chunk :
                BindingSyncCodec.chunks(bindings, generation)) {
            NetworkHandler.CHANNEL.send(
                    PacketDistributor.PLAYER.with(() -> player),
                    new BeyondDimensionsBindingSyncPacket(
                            chunk.bindings(), chunk.generation(),
                            chunk.chunkIndex(), chunk.totalChunks()));
        }
    }

    private static ServerLevel resolveTarget(ServerPlayer player, ResourceLocation dim, BlockPos pos) {
        MachineInteractionAccess.Result result =
                MachineInteractionAccess.resolve(
                        player, dim, pos, true, true);
        if (!result.success()) {
            MachineInteractionAccess.sendError(player, result.error());
            return null;
        }
        return result.target().level();
    }

    private static Optional<CraftStorageEndpoint> resolveEndpoint(ServerPlayer player,
                                                                    ResourceLocation dim,
                                                                    BlockPos pos) {
        for (ItemStack stack : connectorStacks(player)) {
            if (!BD_TERMINAL.equals(ForgeRegistries.ITEMS.getKey(stack.getItem()))) continue;
            if (!BindingStorage.hasBinding(stack, dim, pos)) continue;
            int networkId = readNetworkId(stack);
            if (networkId < 0) continue;
            Optional<CraftStorageEndpoint> endpoint = CraftStorageEndpoints.resolve(
                    new StorageReference(BD_BACKEND, Integer.toString(networkId)), player);
            if (endpoint.isPresent()) return endpoint;
        }
        return Optional.empty();
    }

    private static List<ItemStack> connectorStacks(ServerPlayer player) {
        List<ItemStack> stacks = new ArrayList<>();
        stacks.addAll(player.getInventory().items);
        stacks.addAll(player.getInventory().offhand);
        stacks.addAll(player.getInventory().armor);
        stacks.addAll(CuriosAccess.stacks(player));
        return stacks;
    }

    private static int readNetworkId(ItemStack stack) {
        // BD derives the network id through NetedItem#getNetId rather than a
        // stable public NBT contract. Keep the optional class isolated here so
        // RS-only installations never link BD classes.
        try {
            Class<?> netedItem = Class.forName(
                    "com.wintercogs.beyonddimensions.common.item.NetedItem", false,
                    BeyondDimensionsMachineOperations.class.getClassLoader());
            if (!netedItem.isInstance(stack.getItem())) return -1;
            Object value = netedItem.getMethod("getNetId", ItemStack.class).invoke(null, stack);
            return value instanceof Number number ? number.intValue() : -1;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return -1;
        }
    }

}
