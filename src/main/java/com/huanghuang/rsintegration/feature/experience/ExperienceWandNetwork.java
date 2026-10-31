package com.huanghuang.rsintegration.feature.experience;

import com.huanghuang.rsintegration.feature.experience.client.ResonantExperienceWandClient;
import com.huanghuang.rsintegration.network.packet.NetworkHandler;
import com.huanghuang.rsintegration.network.packet.NetworkPacketIds;
import com.huanghuang.rsintegration.storage.StorageSession;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/** 设置包只允许修改当前手持的同一根杖，不接受客户端库存、经验或缓冲量。 */
public final class ExperienceWandNetwork {
    public enum Action { REFRESH, SAVE, STORE_LEVELS, TAKE_LEVELS, STORE_ALL, TAKE_ALL, STOP }

    private static boolean registered;

    private ExperienceWandNetwork() {}

    public static void register() {
        if (registered) return;
        NetworkHandler.CHANNEL.registerMessage(NetworkPacketIds.EXPERIENCE_WAND_CONTROL,
                ControlPacket.class, ControlPacket::encode, ControlPacket::decode, ControlPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
        NetworkHandler.CHANNEL.registerMessage(NetworkPacketIds.EXPERIENCE_WAND_STATE,
                StatePacket.class, StatePacket::encode, StatePacket::decode, StatePacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        registered = true;
    }

    public static void open(ServerPlayer player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!(stack.getItem() instanceof ResonantExperienceWandItem)) return;
        ExperienceWandData.id(stack);
        player.getInventory().setChanged();
        sendState(player, hand, stack, true);
    }

    public static void send(Action action, InteractionHand hand, UUID id,
                            ExperienceWandSettings settings) {
        NetworkHandler.CHANNEL.sendToServer(new ControlPacket(action, hand, id, settings));
    }

    private static void apply(ServerPlayer player, ControlPacket packet) {
        if (player == null || !player.isAlive() || player.isSpectator()) return;
        ItemStack stack = player.getItemInHand(packet.hand());
        if (!ExperienceWandData.matches(stack, packet.id())) return;
        boolean refresh = packet.action() == Action.REFRESH;
        String rateKey = refresh ? "rsiExperienceRefreshTick" : "rsiExperienceControlTick";
        CompoundTag playerData = player.getPersistentData();
        long now = player.tickCount;
        long previous = playerData.getLong(rateKey);
        boolean limited = playerData.contains(rateKey) && now >= previous
                && now - previous < (refresh ? 10L : 2L);
        if (limited && packet.action() != Action.SAVE) return;
        playerData.putLong(rateKey, now);
        if (!refresh) {
            packet.settings().save(stack);
            CompoundTag data = ExperienceWandData.tag(stack);
            data.remove(ExperienceWandData.PENDING_TARGET);
            long target = switch (packet.action()) {
                case STORE_ALL -> 0L;
                case TAKE_ALL -> ExperiencePoints.MAX_POINTS;
                case STORE_LEVELS -> ExperiencePoints.forLevel(Math.max(0,
                        player.experienceLevel - packet.settings().manualLevels()));
                case TAKE_LEVELS -> ExperiencePoints.forLevel((int) Math.min(ExperiencePoints.MAX_LEVEL,
                        (long) player.experienceLevel + packet.settings().manualLevels()));
                default -> -1L;
            };
            if (target >= 0L && ExperiencePoints.supported(player)) {
                data.putLong(ExperienceWandData.PENDING_TARGET, target);
            }
            player.getInventory().setChanged();
        }
        if (!limited) sendState(player, packet.hand(), stack, false);
    }

    private static void sendState(ServerPlayer player, InteractionHand hand,
                                  ItemStack stack, boolean open) {
        StorageSession session = ExperienceWandService.session(player);
        long networkAmount = session == null ? -1L : session.snapshotFluids(player).snapshotValue()
                .map(snapshot -> snapshot.countExact(ExperienceWandService.key(session))).orElse(-1L);
        boolean busy = ExperienceWandData.tag(stack).contains(ExperienceWandData.PENDING_TARGET);
        ItemStack selected = ExperienceWandService.selected(player);
        StatePacket packet = new StatePacket(open, hand, ExperienceWandData.id(stack),
                ExperienceWandSettings.fromStack(stack), player.experienceLevel,
                networkAmount, ExperienceWandData.buffer(stack), ExperienceWandData.uncertain(stack),
                session != null, busy, selected.isEmpty() || selected == stack);
        NetworkHandler.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
    }

    public record ControlPacket(Action action, InteractionHand hand, UUID id,
                                ExperienceWandSettings settings) {
        static void encode(ControlPacket packet, FriendlyByteBuf buffer) {
            buffer.writeEnum(packet.action());
            buffer.writeEnum(packet.hand());
            buffer.writeUUID(packet.id());
            writeSettings(buffer, packet.settings());
        }

        static ControlPacket decode(FriendlyByteBuf buffer) {
            return new ControlPacket(buffer.readEnum(Action.class), buffer.readEnum(InteractionHand.class),
                    buffer.readUUID(), readSettings(buffer));
        }

        static void handle(ControlPacket packet, Supplier<NetworkEvent.Context> supplier) {
            NetworkEvent.Context context = supplier.get();
            context.enqueueWork(() -> apply(context.getSender(), packet));
            context.setPacketHandled(true);
        }
    }

    public record StatePacket(boolean open, InteractionHand hand, UUID id,
                              ExperienceWandSettings settings, int playerLevel, long networkMb,
                              long bufferedMb, long uncertainMb, boolean connected,
                              boolean busy, boolean effective) {
        static void encode(StatePacket packet, FriendlyByteBuf buffer) {
            buffer.writeBoolean(packet.open());
            buffer.writeEnum(packet.hand());
            buffer.writeUUID(packet.id());
            writeSettings(buffer, packet.settings());
            buffer.writeVarInt(packet.playerLevel());
            buffer.writeVarLong(packet.networkMb());
            buffer.writeVarLong(packet.bufferedMb());
            buffer.writeVarLong(packet.uncertainMb());
            buffer.writeBoolean(packet.connected());
            buffer.writeBoolean(packet.busy());
            buffer.writeBoolean(packet.effective());
        }

        static StatePacket decode(FriendlyByteBuf buffer) {
            return new StatePacket(buffer.readBoolean(), buffer.readEnum(InteractionHand.class),
                    buffer.readUUID(), readSettings(buffer), buffer.readVarInt(), buffer.readVarLong(),
                    buffer.readVarLong(), buffer.readVarLong(), buffer.readBoolean(),
                    buffer.readBoolean(), buffer.readBoolean());
        }

        static void handle(StatePacket packet, Supplier<NetworkEvent.Context> supplier) {
            NetworkEvent.Context context = supplier.get();
            context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                    () -> () -> ResonantExperienceWandClient.accept(packet)));
            context.setPacketHandled(true);
        }
    }

    private static void writeSettings(FriendlyByteBuf buffer, ExperienceWandSettings settings) {
        buffer.writeEnum(settings.mode());
        buffer.writeVarInt(settings.targetLevel());
        buffer.writeBoolean(settings.mending());
        buffer.writeVarInt(settings.manualLevels());
    }

    private static ExperienceWandSettings readSettings(FriendlyByteBuf buffer) {
        return new ExperienceWandSettings(buffer.readEnum(ExperienceWandSettings.Mode.class),
                buffer.readVarInt(), buffer.readBoolean(), buffer.readVarInt());
    }
}
