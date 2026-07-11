package com.huanghuang.rsintegration.sidepanel.network;

import com.huanghuang.rsintegration.machine.MachineSlotType;
import com.huanghuang.rsintegration.network.binding.AltarBindingRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.ForgeHooks;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Client → Server: insert cursor item into a Quick-type bound machine's input/fuel slot.
 * ID 8.
 */
public final class MachineInsertPacket {

    private final ResourceLocation dim;
    private final BlockPos pos;
    private final MachineSlotType slot;

    public MachineInsertPacket(ResourceLocation dim, BlockPos pos, MachineSlotType slot) {
        this.dim = dim;
        this.pos = pos;
        this.slot = slot;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeResourceLocation(dim);
        buf.writeBlockPos(pos);
        slot.encode(buf);
    }

    public static MachineInsertPacket decode(FriendlyByteBuf buf) {
        return new MachineInsertPacket(
            buf.readResourceLocation(),
            buf.readBlockPos(),
            MachineSlotType.decode(buf)
        );
    }

    public static void handle(MachineInsertPacket packet, Supplier<NetworkEvent.Context> ctx) {
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

            ItemStack carried = player.containerMenu.getCarried();
            if (carried.isEmpty()) return;

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

            BlockEntity be = targetLevel.getBlockEntity(packet.pos);
            if (!(be instanceof AbstractFurnaceBlockEntity furnace)) {
                player.sendSystemMessage(
                    Component.translatable("rsi.machine.error.wrong_type"));
                return;
            }

            int targetSlot = packet.slot == MachineSlotType.FUEL ? 1 : 0;

            if (packet.slot == MachineSlotType.FUEL) {
                if (ForgeHooks.getBurnTime(carried, null) <= 0) {
                    player.sendSystemMessage(
                        Component.translatable("rsi.machine.error.not_fuel"));
                    return;
                }
            }

            ItemStack existing = furnace.getItem(targetSlot);
            if (!existing.isEmpty() && !ItemStack.isSameItemSameTags(existing, carried)) {
                player.sendSystemMessage(
                    Component.translatable("rsi.machine.error.slot_mismatch"));
                return;
            }

            int maxStack = furnace.getMaxStackSize();
            int canInsert = Math.min(carried.getCount(), maxStack - existing.getCount());
            if (canInsert <= 0) {
                player.sendSystemMessage(
                    Component.translatable("rsi.machine.error.slot_full"));
                return;
            }

            int newCount = existing.getCount() + canInsert;
            ItemStack slotStack = carried.copy();
            slotStack.setCount(newCount);
            furnace.setItem(targetSlot, slotStack);

            ItemStack remaining = carried.copy();
            remaining.shrink(canInsert);
            player.containerMenu.setCarried(remaining.isEmpty() ? ItemStack.EMPTY : remaining);

            furnace.setChanged();
        });
        context.setPacketHandled(true);
    }
}
