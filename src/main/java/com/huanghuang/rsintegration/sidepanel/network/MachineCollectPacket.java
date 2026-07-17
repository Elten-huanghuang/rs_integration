package com.huanghuang.rsintegration.sidepanel.network;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.network.binding.AltarBindingRegistry;
import com.huanghuang.rsintegration.network.RSIntegrationNetwork;
import com.huanghuang.rsintegration.util.ChunkUtils;
import com.refinedmods.refinedstorage.api.network.INetwork;
import com.refinedmods.refinedstorage.api.util.Action;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Client → Server: collect output from a Quick-type bound machine.
 * ID 7.
 */
public final class MachineCollectPacket {

    private final ResourceLocation dim;
    private final BlockPos pos;
    private final boolean toRS;

    public MachineCollectPacket(ResourceLocation dim, BlockPos pos, boolean toRS) {
        this.dim = dim;
        this.pos = pos;
        this.toRS = toRS;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeResourceLocation(dim);
        buf.writeBlockPos(pos);
        buf.writeBoolean(toRS);
    }

    public static MachineCollectPacket decode(FriendlyByteBuf buf) {
        return new MachineCollectPacket(
            buf.readResourceLocation(),
            buf.readBlockPos(),
            buf.readBoolean()
        );
    }

    public static void handle(MachineCollectPacket packet, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        ServerPlayer player = context.getSender();
        if (player == null) {
            context.setPacketHandled(true);
            return;
        }
        if (player instanceof net.minecraftforge.common.util.FakePlayer) {
            context.setPacketHandled(true);
            return;
        }
        context.enqueueWork(() -> {

            ResourceKey<Level> dimKey = ResourceKey.create(
                net.minecraft.core.registries.Registries.DIMENSION, packet.dim);

            if (!AltarBindingRegistry.isBound(dimKey, packet.pos, player)) {
                player.sendSystemMessage(
                    Component.translatable("rsi.machine.error.not_bound"));
                return;
            }

            // Protection check — same pattern as OpenBoundMachineGuiPacket
            PlayerInteractEvent.RightClickBlock event = new PlayerInteractEvent.RightClickBlock(
                    player, InteractionHand.MAIN_HAND, packet.pos,
                    new BlockHitResult(new Vec3(packet.pos.getX() + 0.5,
                            packet.pos.getY() + 1.0, packet.pos.getZ() + 0.5),
                            Direction.UP, packet.pos, false));
            MinecraftForge.EVENT_BUS.post(event);
            if (event.isCanceled()) {
                player.sendSystemMessage(
                    Component.translatable("rsi.error.protected_block"));
                return;
            }

            MinecraftServer server = player.getServer();
            if (server == null) return;
            ServerLevel targetLevel = server.getLevel(dimKey);
            if (targetLevel == null) {
                player.sendSystemMessage(
                    Component.translatable("rsi.error.dim_not_loaded"));
                return;
            }

            ChunkUtils.loadChunk(targetLevel, packet.pos);
            BlockEntity be = targetLevel.getBlockEntity(packet.pos);
            if (isIronFurnace(be)) {
                if (!(be instanceof Container iron) || !isOrdinaryIronFurnace(be)) {
                    player.sendSystemMessage(Component.translatable("rsi.ironfurnaces.error.machine_mode_unsupported"));
                    return;
                }
                ItemStack output = iron.getItem(2).copy();
                if (output.isEmpty()) {
                    player.sendSystemMessage(Component.translatable("rsi.machine.error.no_output"));
                    return;
                }
                iron.setItem(2, ItemStack.EMPTY);
                be.setChanged();
                if (packet.toRS) {
                    INetwork network = RSIntegrationNetwork.resolveNetworkFromPlayer(player);
                    if (network != null) {
                        ItemStack leftover = network.insertItem(output, output.getCount(), Action.PERFORM);
                        if (!leftover.isEmpty()) player.drop(leftover, false);
                    } else player.drop(output, false);
                } else if (!player.getInventory().add(output)) player.drop(output, false);
                player.sendSystemMessage(Component.translatable("rsi.machine.collected", output.getCount(), output.getHoverName()));
                return;
            }
            if (!(be instanceof AbstractFurnaceBlockEntity furnace)) {
                player.sendSystemMessage(
                    Component.translatable("rsi.machine.error.wrong_type"));
                return;
            }

            ItemStack output = furnace.getItem(2).copy();
            if (output.isEmpty()) {
                player.sendSystemMessage(
                    Component.translatable("rsi.machine.error.no_output"));
                return;
            }

            furnace.setItem(2, ItemStack.EMPTY);
            furnace.setChanged();

            if (packet.toRS) {
                INetwork network = RSIntegrationNetwork.resolveNetworkFromPlayer(player);
                if (network != null) {
                    ItemStack leftover = network.insertItem(output, output.getCount(), Action.PERFORM);
                    if (!leftover.isEmpty()) {
                        player.drop(leftover, false);
                    }
                } else {
                    player.drop(output, false);
                }
            } else {
                if (!player.getInventory().add(output)) {
                    player.drop(output, false);
                }
            }

            player.sendSystemMessage(
                Component.translatable("rsi.machine.collected", output.getCount(), output.getHoverName()));
        });
        context.setPacketHandled(true);
    }

    private static boolean isIronFurnace(BlockEntity be) {
        Class<?> type = be != null ? be.getClass() : null;
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
}
