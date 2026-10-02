package com.huanghuang.rsintegration.mods.ironsspellbooks;

import com.huanghuang.rsintegration.crafting.CraftProgressSnapshot;
import com.huanghuang.rsintegration.crafting.CraftProgressSnapshot.NodeProgress;
import com.huanghuang.rsintegration.crafting.CraftProgressSnapshot.NodeState;
import com.huanghuang.rsintegration.crafting.batch.CraftProgressDeltaPacket;
import com.huanghuang.rsintegration.crafting.batch.CraftProgressPacket;
import com.huanghuang.rsintegration.crafting.batch.CraftStartedPacket;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.fluids.FluidStack;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class InkFluidProgressPacketTest extends BootstrapTest {
    @ParameterizedTest
    @ValueSource(ints = {250, 1000, 2300})
    void startedPacketRetainsInkTargetAndAmount(int amount) {
        ItemStack target = ink(amount);
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            new CraftStartedPacket(UUID.randomUUID(), 1, false, target).encode(buffer);
            assertStack(target, CraftStartedPacket.decode(buffer).target());
            assertEquals(0, buffer.readableBytes());
            assertFalse(target.getTag().contains("RSIFluidAmount"));
        } finally {
            buffer.release();
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {250, 1000, 2300})
    void fullAndDeltaProgressRetainInkAndOrdinaryOutputs(int amount) {
        ItemStack target = ink(amount);
        ItemStack bottle = new ItemStack(Items.GLASS_BOTTLE, 3);
        List<NodeProgress> nodes = List.of(node(0, target), node(1, bottle));
        CraftProgressSnapshot snapshot = new CraftProgressSnapshot(UUID.randomUUID(), 2,
                CraftProgressSnapshot.Result.RUNNING, CraftProgressSnapshot.Reason.NONE,
                0, 2, 1, "", nodes);
        FriendlyByteBuf full = new FriendlyByteBuf(Unpooled.buffer());
        FriendlyByteBuf delta = new FriendlyByteBuf(Unpooled.buffer());
        try {
            new CraftProgressPacket(snapshot).encode(full);
            List<NodeProgress> decoded = CraftProgressPacket.decode(full).snapshot().nodes();
            assertStack(target, decoded.get(0).displayOutput());
            assertStack(bottle, decoded.get(1).displayOutput());
            assertEquals(0, full.readableBytes());

            new CraftProgressDeltaPacket(snapshot.craftId(), 1, snapshot, nodes).encode(delta);
            List<NodeProgress> changed = CraftProgressDeltaPacket.decode(delta).changedNodes();
            assertStack(target, changed.get(0).displayOutput());
            assertStack(bottle, changed.get(1).displayOutput());
            assertEquals(0, delta.readableBytes());
        } finally {
            full.release();
            delta.release();
        }
    }

    private static ItemStack ink(int amount) {
        FluidStack fluid = InkFluidTestFixtures.ink("legendary_ink", amount);
        fluid.getOrCreateTag().putString("variant", "display-test");
        return InkFluidSupport.token(InkFluidTestFixtures.tokenItem(), fluid);
    }

    private static NodeProgress node(int id, ItemStack output) {
        return new NodeProgress(id, NodeState.RUNNING, "test:recipe", "generic", output,
                0, 1, 0, "", CraftProgressSnapshot.Reason.NONE, "", false);
    }

    private static void assertStack(ItemStack expected, ItemStack actual) {
        assertFalse(actual.isEmpty());
        assertTrue(ItemStack.isSameItemSameTags(expected, actual));
        assertEquals(expected.getCount(), actual.getCount());
    }
}
