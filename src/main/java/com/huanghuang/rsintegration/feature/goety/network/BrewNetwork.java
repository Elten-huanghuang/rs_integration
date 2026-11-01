package com.huanghuang.rsintegration.feature.goety.network;

import com.huanghuang.rsintegration.feature.goety.menu.WitchCauldronMenu;
import com.huanghuang.rsintegration.feature.goety.client.GoetyRsiClient;
import com.huanghuang.rsintegration.crafting.MaterialLocks;
import com.huanghuang.rsintegration.crafting.planning.PlanningPreferences;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.*;
import net.minecraftforge.network.simple.SimpleChannel;
import java.util.*;

public final class BrewNetwork {
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            ResourceLocation.fromNamespaceAndPath("rs_integration", "brewing"), () -> "4", "4"::equals, "4"::equals);
    private BrewNetwork() {}
    public static void register() {
        CHANNEL.messageBuilder(Edit.class, 0, NetworkDirection.PLAY_TO_SERVER)
                .encoder(Edit::write).decoder(Edit::read).consumerMainThread((msg, context) -> {
                    var player = context.get().getSender();
                    if (player != null && player.containerMenu instanceof WitchCauldronMenu menu
                            && menu.containerId == msg.menuId && menu.stillValid(player)) menu.handle(player, msg);
                    context.get().setPacketHandled(true);
                }).add();
        CHANNEL.messageBuilder(State.class, 1, NetworkDirection.PLAY_TO_CLIENT)
                .encoder((msg, buf) -> { buf.writeVarInt(msg.menuId); buf.writeNbt(msg.data); })
                .decoder(buf -> new State(buf.readVarInt(), buf.readNbt()))
                .consumerMainThread((msg, context) -> {
                    GoetyRsiClient.accept(msg);
                    context.get().setPacketHandled(true);
                }).add();
        CHANNEL.messageBuilder(PlanAction.class, 2, NetworkDirection.PLAY_TO_SERVER)
                .encoder(PlanAction::write).decoder(PlanAction::read)
                .consumerMainThread((msg, context) -> {
                    var player = context.get().getSender();
                    if (player != null && player.containerMenu instanceof WitchCauldronMenu menu
                            && menu.containerId == msg.menuId && menu.stillValid(player)) menu.handlePlan(player, msg);
                    context.get().setPacketHandled(true);
                }).add();
    }
    public static void sync(ServerPlayer player, int menuId, CompoundTag data) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new State(menuId, data));
    }
    public record State(int menuId, CompoundTag data) {}
    public record PlanAction(int menuId, int revision, boolean preview, long requestId,
                             Map<String, String> forced, Map<String, ItemStack> locks,
                             PlanningPreferences preferences) {
        public PlanAction {
            if (menuId < 0 || revision < 0 || requestId <= 0) {
                throw new IllegalArgumentException("Invalid brew plan request identity");
            }
            if (forced.size() > PlanningPreferences.MAX_RECIPE_CHOICES) {
                throw new IllegalArgumentException("Too many brew recipe choices");
            }
            forced = Map.copyOf(forced);
            locks = MaterialLocks.immutableCopy(locks);
        }
        public void write(FriendlyByteBuf buf) {
            buf.writeVarInt(menuId); buf.writeVarInt(revision);
            buf.writeBoolean(preview); buf.writeVarLong(requestId);
            buf.writeVarInt(forced.size());
            forced.forEach((key, value) -> { buf.writeUtf(key); buf.writeUtf(value); });
            buf.writeVarInt(locks.size());
            locks.forEach((key, value) -> {
                buf.writeUtf(key, MaterialLocks.MAX_KEY_LENGTH); buf.writeItem(value);
            });
            preferences.write(buf);
        }
        public static PlanAction read(FriendlyByteBuf buf) {
            int menu = buf.readVarInt(), revision = buf.readVarInt();
            boolean preview = buf.readBoolean();
            long request = buf.readVarLong();
            int count = buf.readVarInt();
            if (count < 0 || count > PlanningPreferences.MAX_RECIPE_CHOICES) {
                throw new IllegalArgumentException("Too many brew recipe choices");
            }
            var forced = new LinkedHashMap<String, String>();
            for (int i = 0; i < count; i++) forced.put(buf.readUtf(), buf.readUtf());
            int lockCount = buf.readVarInt();
            if (lockCount < 0 || lockCount > MaterialLocks.MAX_LOCKS) {
                throw new IllegalArgumentException("Too many brew material choices");
            }
            var locks = new LinkedHashMap<String, ItemStack>();
            for (int i = 0; i < lockCount; i++) {
                locks.put(buf.readUtf(MaterialLocks.MAX_KEY_LENGTH), buf.readItem());
            }
            return new PlanAction(menu, revision, preview, request, forced, locks, PlanningPreferences.read(buf));
        }
    }
    public record Edit(int menuId, int revision, int capacity, List<ResourceLocation> selected, String action) {
        public void write(FriendlyByteBuf buf) {
            buf.writeVarInt(menuId); buf.writeVarInt(revision); buf.writeVarInt(capacity);
            buf.writeVarInt(selected.size()); selected.forEach(buf::writeResourceLocation); buf.writeUtf(action, 24);
        }
        static Edit read(FriendlyByteBuf buf) {
            int menu = buf.readVarInt(), revision = buf.readVarInt(), capacity = buf.readVarInt(), count = buf.readVarInt();
            if (count < 0 || count > 64) throw new IllegalArgumentException("Too many brew selections");
            var selected = new ArrayList<ResourceLocation>();
            for (int i = 0; i < count; i++) selected.add(buf.readResourceLocation());
            return new Edit(menu, revision, capacity, List.copyOf(selected), buf.readUtf(24));
        }
    }
}
