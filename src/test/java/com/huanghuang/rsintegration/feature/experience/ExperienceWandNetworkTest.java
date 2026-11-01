package com.huanghuang.rsintegration.feature.experience;

import com.huanghuang.rsintegration.feature.experience.ExperienceWandNetwork.Action;
import com.huanghuang.rsintegration.feature.experience.ExperienceWandNetwork.ControlPacket;
import com.huanghuang.rsintegration.feature.experience.ExperienceWandNetwork.StatePacket;
import com.huanghuang.rsintegration.feature.experience.ExperienceWandSettings.Mode;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ExperienceWandNetworkTest extends BootstrapTest {
    @Test
    void manualTransfersDisableAutomaticLevelControlAndPreserveOtherSettings() {
        for (Action action : new Action[]{Action.STORE_LEVELS, Action.TAKE_LEVELS,
                Action.STORE_ALL, Action.TAKE_ALL}) {
            for (Mode mode : Mode.values()) {
                ExperienceWandSettings settings = new ExperienceWandSettings(
                        mode, ExperiencePoints.MAX_LEVEL, true, 100_000_000);
                ControlPacket packet = new ControlPacket(action, InteractionHand.MAIN_HAND,
                        UUID.randomUUID(), settings);

                assertEquals(new ExperienceWandSettings(Mode.OFF,
                        ExperiencePoints.MAX_LEVEL, true, 100_000_000), packet.settingsForAction());
                ItemStack wand = new ItemStack(Items.STICK);
                packet.settingsForAction().save(wand);
                assertEquals(packet.settingsForAction(), ExperienceWandSettings.fromStack(wand));
            }
        }
    }

    @Test
    void savingRefreshingAndStoppingPreserveTheSelectedMode() {
        for (Action action : new Action[]{Action.SAVE, Action.REFRESH, Action.STOP}) {
            for (Mode mode : Mode.values()) {
                ExperienceWandSettings settings = new ExperienceWandSettings(mode, 30, false, 1);
                ControlPacket packet = new ControlPacket(action, InteractionHand.OFF_HAND,
                        UUID.randomUUID(), settings);

                assertEquals(settings, packet.settingsForAction());
            }
        }
    }

    @Test
    void controlPacketPreservesLargeLevelSettingsAndSanitizesManualTransfersAfterDecoding() {
        ControlPacket packet = new ControlPacket(Action.TAKE_ALL, InteractionHand.OFF_HAND,
                UUID.randomUUID(), new ExperienceWandSettings(
                Mode.KEEP, ExperiencePoints.MAX_LEVEL, true, ExperiencePoints.MAX_LEVEL));
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            ControlPacket.encode(packet, buffer);
            ControlPacket decoded = ControlPacket.decode(buffer);

            assertEquals(packet, decoded);
            assertEquals(Mode.OFF, decoded.settingsForAction().mode());
            assertEquals(ExperiencePoints.MAX_LEVEL, decoded.settingsForAction().targetLevel());
        } finally {
            buffer.release();
        }
    }

    @Test
    void statePacketPreservesLongFluidAmountsWithoutTruncation() {
        for (long amount : new long[]{0L, Integer.MAX_VALUE, (long) Integer.MAX_VALUE + 1L,
                99_845_381_177_416L, Long.MAX_VALUE}) {
            StatePacket packet = new StatePacket(false, InteractionHand.MAIN_HAND, UUID.randomUUID(),
                    new ExperienceWandSettings(Mode.OFF, 30, true, 1),
                    ExperiencePoints.MAX_LEVEL, amount, Long.MAX_VALUE, amount, true, true, true);
            FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
            try {
                StatePacket.encode(packet, buffer);

                assertEquals(packet, StatePacket.decode(buffer));
            } finally {
                buffer.release();
            }
        }
    }

    @Test
    void disconnectedStateRetainsItsUnknownAmountSentinel() {
        StatePacket packet = new StatePacket(true, InteractionHand.OFF_HAND, UUID.randomUUID(),
                new ExperienceWandSettings(Mode.KEEP, 30, false, 1),
                30, -1L, 0L, 0L, false, false, true);
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            StatePacket.encode(packet, buffer);

            assertEquals(packet, StatePacket.decode(buffer));
        } finally {
            buffer.release();
        }
    }
}
