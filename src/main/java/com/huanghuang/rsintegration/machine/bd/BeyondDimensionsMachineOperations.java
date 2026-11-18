package com.huanghuang.rsintegration.machine.bd;

import com.huanghuang.rsintegration.crafting.endpoint.CraftStorageEndpoint;
import com.huanghuang.rsintegration.crafting.endpoint.CraftStorageEndpoints;
import com.huanghuang.rsintegration.network.binding.BindingStorage;
import com.huanghuang.rsintegration.network.binding.MachineBindingSyncBridge;
import com.huanghuang.rsintegration.network.binding.MachineUnbindService;
import com.huanghuang.rsintegration.storage.StorageBackendId;
import com.huanghuang.rsintegration.storage.StorageReference;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;
import com.huanghuang.rsintegration.util.CuriosAccess;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;

/** BD 绑定机器的服务端操作。 */
public final class BeyondDimensionsMachineOperations {
    private static final ResourceLocation BD_TERMINAL =
            ResourceLocation.fromNamespaceAndPath("beyonddimensions", "net_terminal_item");
    private static final StorageBackendId BD_BACKEND = new StorageBackendId("beyonddimensions");

    private BeyondDimensionsMachineOperations() {}

    /** 只解析匹配该机器绑定的 BD 终端，并通过已有会话入库。 */
    public static Optional<UnaryOperator<ItemStack>> resolveOutput(
            ServerPlayer player, ResourceLocation dim, BlockPos pos) {
        return resolveEndpoint(player, dim, pos).map(endpoint ->
                output -> endpoint.insert(player, output, false).remainder().orElse(output));
    }

    public static void unbind(ServerPlayer player, ResourceLocation dim, BlockPos pos) {
        MachineUnbindService.Result result =
                MachineUnbindService.unbind(player, dim, pos);
        player.sendSystemMessage(result.message());
        MachineBindingSyncBridge.send(player);
    }

    private static Optional<CraftStorageEndpoint> resolveEndpoint(ServerPlayer player,
                                                                    ResourceLocation dim,
                                                                    BlockPos pos) {
        for (ItemStack stack : connectorStacks(player)) {
            if (!BD_TERMINAL.equals(ForgeRegistries.ITEMS.getKey(stack.getItem()))) continue;
            if (!BindingStorage.hasBinding(player.server, stack, dim, pos)) continue;
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
