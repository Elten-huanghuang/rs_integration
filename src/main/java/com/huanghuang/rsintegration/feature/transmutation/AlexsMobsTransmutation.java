package com.huanghuang.rsintegration.feature.transmutation;

import com.huanghuang.rsintegration.RSIntegrationMod;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.List;

/** 在 Alex’s Mobs 的嬗变台首次使用时解析原生配置和同步包。 */
public final class AlexsMobsTransmutation {
    private static final String ROOT = "com.github.alexthe666.alexsmobs.";
    private static final Bindings BINDINGS = findBindings();

    private AlexsMobsTransmutation() {}

    public static int experienceCost() {
        if (BINDINGS == null) return -1;
        try {
            return BINDINGS.experienceCost().getInt(null);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("无法读取 Alex’s Mobs 嬗变经验消耗", e);
        }
    }

    public static void syncPossibilities(ServerPlayer player, List<ItemStack> possibilities) {
        if (BINDINGS == null) return;
        try {
            Object message = BINDINGS.updatePacket().newInstance(player.getId(),
                    possibilities.get(0), possibilities.get(1), possibilities.get(2));
            BINDINGS.channel().send(PacketDistributor.PLAYER.with(() -> player), message);
        } catch (ReflectiveOperationException e) {
            RSIntegrationMod.LOGGER.warn("无法同步 Alex’s Mobs 嬗变候选", e);
        }
    }

    private static Bindings findBindings() {
        try {
            Field cost = Class.forName(ROOT + "config.AMConfig").getField("transmutingExperienceCost");
            SimpleChannel channel = (SimpleChannel) Class.forName(ROOT + "AlexsMobs")
                    .getField("NETWORK_WRAPPER").get(null);
            Constructor<?> packet = Class.forName(ROOT + "message.MessageUpdateTransmutablesToDisplay")
                    .getConstructor(int.class, ItemStack.class, ItemStack.class, ItemStack.class);
            return new Bindings(cost, channel, packet);
        } catch (ReflectiveOperationException | LinkageError e) {
            RSIntegrationMod.LOGGER.warn("无法找到 Alex’s Mobs 嬗变台的原生接口", e);
            return null;
        }
    }

    private record Bindings(Field experienceCost, SimpleChannel channel, Constructor<?> updatePacket) {}
}
