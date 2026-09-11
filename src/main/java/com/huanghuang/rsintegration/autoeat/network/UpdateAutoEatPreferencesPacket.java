package com.huanghuang.rsintegration.autoeat.network;

import com.huanghuang.rsintegration.autoeat.AutoEatMode;
import com.huanghuang.rsintegration.autoeat.AutoEatPreferences;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/** Persists the client's current auto-eat mode and stack-food selection. */
public record UpdateAutoEatPreferencesPacket(
        AutoEatMode mode, List<ResourceLocation> selectedItems) {

    public UpdateAutoEatPreferencesPacket {
        Objects.requireNonNull(mode, "mode");
        selectedItems = AutoEatSelectionCodec.copy(selectedItems);
    }

    public UpdateAutoEatPreferencesPacket(AutoEatMode mode,
                                          Collection<ResourceLocation> selectedItems) {
        this(mode, AutoEatSelectionCodec.copy(selectedItems));
    }

    public static void encode(UpdateAutoEatPreferencesPacket packet, FriendlyByteBuf buffer) {
        buffer.writeEnum(packet.mode);
        AutoEatSelectionCodec.write(buffer, packet.selectedItems);
    }

    public static UpdateAutoEatPreferencesPacket decode(FriendlyByteBuf buffer) {
        AutoEatMode mode = buffer.readEnum(AutoEatMode.class);
        return new UpdateAutoEatPreferencesPacket(mode, AutoEatSelectionCodec.read(buffer));
    }

    public static void handle(UpdateAutoEatPreferencesPacket packet,
                              Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            var sender = context.getSender();
            if (sender != null && !(sender instanceof FakePlayer)) {
                AutoEatPreferences.save(sender, packet.mode, packet.selectedItems);
            }
        });
        context.setPacketHandled(true);
    }
}
