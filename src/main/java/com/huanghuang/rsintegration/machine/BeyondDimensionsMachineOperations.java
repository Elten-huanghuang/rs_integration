package com.huanghuang.rsintegration.machine;

import com.huanghuang.rsintegration.crafting.CraftStorageEndpoint;
import com.huanghuang.rsintegration.crafting.CraftStorageEndpoints;
import com.huanghuang.rsintegration.network.ProtectionChecker;
import com.huanghuang.rsintegration.network.binding.AltarBindingRegistry;
import com.huanghuang.rsintegration.network.binding.BindingEventHandler;
import com.huanghuang.rsintegration.network.binding.BindingStorage;
import com.huanghuang.rsintegration.sidepanel.favorite.MachineFavoriteKey;
import com.huanghuang.rsintegration.sidepanel.favorite.MachineFavoritesSavedData;
import com.huanghuang.rsintegration.sidepanel.data.BindingInfo;
import com.huanghuang.rsintegration.storage.StorageBackendId;
import com.huanghuang.rsintegration.storage.StorageOperationResult;
import com.huanghuang.rsintegration.storage.StorageReference;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.ForgeHooks;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Server-side machine-center operations for BD-bound machines. */
public final class BeyondDimensionsMachineOperations {
    private static final ResourceLocation BD_TERMINAL =
            ResourceLocation.fromNamespaceAndPath("beyonddimensions", "net_terminal_item");
    private static final StorageBackendId BD_BACKEND = new StorageBackendId("beyonddimensions");

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
        ItemStack output;
        if (isIronFurnace(be)) {
            if (!(be instanceof Container container) || !isOrdinaryIronFurnace(be)) {
                player.sendSystemMessage(Component.translatable("rsi.ironfurnaces.error.machine_mode_unsupported"));
                return;
            }
            output = container.getItem(2).copy();
            if (!output.isEmpty()) container.setItem(2, ItemStack.EMPTY);
        } else if (be instanceof AbstractFurnaceBlockEntity furnace) {
            output = furnace.getItem(2).copy();
            if (!output.isEmpty()) furnace.setItem(2, ItemStack.EMPTY);
        } else {
            player.sendSystemMessage(Component.translatable("rsi.machine.error.wrong_type"));
            return;
        }
        if (output.isEmpty()) {
            player.sendSystemMessage(Component.translatable("rsi.machine.error.no_output"));
            return;
        }
        be.setChanged();

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
        ItemStack carried = player.containerMenu.getCarried();
        if (carried.isEmpty()) return;
        ServerLevel level = resolveTarget(player, dim, pos);
        if (level == null) return;
        BlockEntity be = level.getBlockEntity(pos);
        int targetSlot = slot == MachineSlotType.FUEL ? 1 : 0;
        if (isIronFurnace(be)) {
            if (!(be instanceof Container container) || !isOrdinaryIronFurnace(be)) {
                player.sendSystemMessage(Component.translatable("rsi.ironfurnaces.error.machine_mode_unsupported"));
                return;
            }
            if (slot == MachineSlotType.FUEL
                    && ForgeHooks.getBurnTime(carried, ironRecipeType(be)) <= 0) {
                player.sendSystemMessage(Component.translatable("rsi.machine.error.not_fuel"));
                return;
            }
            insertIntoContainer(player, be, container, carried, targetSlot);
            return;
        }
        if (!(be instanceof AbstractFurnaceBlockEntity furnace)) {
            player.sendSystemMessage(Component.translatable("rsi.machine.error.wrong_type"));
            return;
        }
        if (slot == MachineSlotType.FUEL && ForgeHooks.getBurnTime(carried, null) <= 0) {
            player.sendSystemMessage(Component.translatable("rsi.machine.error.not_fuel"));
            return;
        }
        insertIntoFurnace(player, furnace, carried, targetSlot);
    }

    public static void unbind(ServerPlayer player, ResourceLocation dim, BlockPos pos) {
        BindingStorage.BindingEntry entry = AltarBindingRegistry.findBindingEntry(player, dim, pos);
        if (entry == null) {
            player.sendSystemMessage(Component.translatable("rsi.machine.error.not_bound"));
            sendBindingSync(player);
            return;
        }
        int removed = AltarBindingRegistry.removePlayerBinding(player, dim, pos);
        if (removed == 0) {
            player.sendSystemMessage(Component.translatable("rsi.machine.error.not_bound"));
            sendBindingSync(player);
            return;
        }
        Component name = BindingEventHandler.resolveBlockName(
                entry.blockKey(), entry.blockRegKey(), entry.displayStack());
        MachineFavoritesSavedData.get(player.server).remove(player.getUUID(),
                new MachineFavoriteKey(dim, pos, entry.blockKey()));
        player.sendSystemMessage(Component.translatable("gui.rs_integration.altar.unbound", name));
        sendBindingSync(player);
    }

    /** Refresh the binding cache using the mixed RS/BD path when available. */
    public static void sendBindingSync(ServerPlayer player) {
        // The RS side-panel sync already contains both RS and BD bindings in
        // mixed installations. Do not replace that shared cache with a
        // BD-only snapshot.
        if (ModList.get().isLoaded("refinedstorage")) {
            try {
                com.huanghuang.rsintegration.sidepanel.RSSidePanelNetworkHandler
                        .sendBindingSync(player);
            } catch (Exception | LinkageError ignored) {
                // The standalone packet below remains the fallback for a
                // partially initialized optional RS environment.
            }
            return;
        }
        List<BindingInfo> bindings = new ArrayList<>();
        for (ItemStack stack : connectorStacks(player)) {
            if (!BD_TERMINAL.equals(ForgeRegistries.ITEMS.getKey(stack.getItem()))) continue;
            for (BindingStorage.BindingEntry entry : BindingStorage.getBindings(stack)) {
                String display = BindingEventHandler.resolveBlockName(
                        entry.blockKey(), entry.blockRegKey(), entry.displayStack()).getString();
                bindings.add(new BindingInfo(BD_TERMINAL.toString(), entry.dim(), entry.pos(),
                        entry.blockKey(), display, entry.blockRegKey(), entry.displayStack()));
            }
        }
        com.huanghuang.rsintegration.network.packet.NetworkHandler.CHANNEL.send(
                net.minecraftforge.network.PacketDistributor.PLAYER.with(() -> player),
                new BeyondDimensionsBindingSyncPacket(bindings));
    }

    private static ServerLevel resolveTarget(ServerPlayer player, ResourceLocation dim, BlockPos pos) {
        ResourceKey<Level> dimKey = ResourceKey.create(
                net.minecraft.core.registries.Registries.DIMENSION, dim);
        if (!AltarBindingRegistry.isBound(dimKey, pos, player)) {
            player.sendSystemMessage(Component.translatable("rsi.machine.error.not_bound"));
            return null;
        }
        PlayerInteractEvent.RightClickBlock event = new PlayerInteractEvent.RightClickBlock(
                player, InteractionHand.MAIN_HAND, pos,
                new BlockHitResult(new Vec3(pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5),
                        Direction.UP, pos, false));
        MinecraftForge.EVENT_BUS.post(event);
        if (event.isCanceled()) {
            player.sendSystemMessage(Component.translatable("rsi.error.protected_block"));
            return null;
        }
        MinecraftServer server = player.getServer();
        ServerLevel level = server == null ? null : server.getLevel(dimKey);
        if (level == null) {
            player.sendSystemMessage(Component.translatable("rsi.error.dim_not_loaded"));
            return null;
        }
        if (!level.hasChunkAt(pos)) {
            player.sendSystemMessage(Component.translatable("rsi.error.chunk_unloaded"));
            return null;
        }
        if (!ProtectionChecker.canInteract(player, level, pos)) {
            player.sendSystemMessage(Component.translatable("rsi.error.protected_block"));
            return null;
        }
        return level;
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
        stacks.addAll(com.huanghuang.rsintegration.util.CuriosAccess.stacks(player));
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

    private static void insertIntoContainer(ServerPlayer player, BlockEntity be, Container container,
                                             ItemStack carried, int slot) {
        ItemStack existing = container.getItem(slot);
        int canInsert = Math.min(carried.getCount(), be instanceof AbstractFurnaceBlockEntity furnace
                ? furnace.getMaxStackSize() - existing.getCount() : 64 - existing.getCount());
        if (!existing.isEmpty() && !ItemStack.isSameItemSameTags(existing, carried)) {
            player.sendSystemMessage(Component.translatable("rsi.machine.error.slot_mismatch"));
            return;
        }
        if (canInsert <= 0) {
            player.sendSystemMessage(Component.translatable("rsi.machine.error.slot_full"));
            return;
        }
        ItemStack merged = existing.isEmpty() ? carried.copy() : existing.copy();
        merged.setCount(existing.getCount() + canInsert);
        container.setItem(slot, merged);
        player.containerMenu.setCarried(carried.copyWithCount(carried.getCount() - canInsert));
        be.setChanged();
    }

    private static void insertIntoFurnace(ServerPlayer player, AbstractFurnaceBlockEntity furnace,
                                          ItemStack carried, int slot) {
        ItemStack existing = furnace.getItem(slot);
        if (!existing.isEmpty() && !ItemStack.isSameItemSameTags(existing, carried)) {
            player.sendSystemMessage(Component.translatable("rsi.machine.error.slot_mismatch"));
            return;
        }
        int canInsert = Math.min(carried.getCount(), furnace.getMaxStackSize() - existing.getCount());
        if (canInsert <= 0) {
            player.sendSystemMessage(Component.translatable("rsi.machine.error.slot_full"));
            return;
        }
        ItemStack merged = existing.isEmpty() ? carried.copy() : existing.copy();
        merged.setCount(existing.getCount() + canInsert);
        furnace.setItem(slot, merged);
        player.containerMenu.setCarried(carried.copyWithCount(carried.getCount() - canInsert));
        furnace.setChanged();
    }

    private static boolean isIronFurnace(BlockEntity be) {
        Class<?> type = be == null ? null : be.getClass();
        while (type != null) {
            if ("ironfurnaces.tileentity.furnaces.BlockIronFurnaceTileBase".equals(type.getName())) return true;
            type = type.getSuperclass();
        }
        return false;
    }

    private static boolean isOrdinaryIronFurnace(BlockEntity be) {
        try {
            return (boolean) be.getClass().getMethod("isFurnace").invoke(be);
        } catch (ReflectiveOperationException exception) {
            return false;
        }
    }

    private static RecipeType<?> ironRecipeType(BlockEntity be) {
        try {
            Class<?> base = Class.forName("ironfurnaces.tileentity.furnaces.BlockIronFurnaceTileBase");
            return (RecipeType<?>) base.getField("recipeType").get(be);
        } catch (ReflectiveOperationException exception) {
            return null;
        }
    }
}
