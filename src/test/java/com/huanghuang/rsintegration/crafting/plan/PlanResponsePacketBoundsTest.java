package com.huanghuang.rsintegration.crafting.plan;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Hardening tests for {@link PlanResponsePacket#decode}: a malformed count must
 * be clamped, not trusted, so a bogus VarInt cannot drive an OOM preallocation.
 */
class PlanResponsePacketBoundsTest extends BootstrapTest {

    private static FriendlyByteBuf buffer() {
        return new FriendlyByteBuf(Unpooled.buffer());
    }

    /**
     * Hand-craft the packet prefix up to (and including) the step-count field,
     * writing a maliciously huge step count. decode() must clamp the count and
     * fail cleanly on truncated data rather than attempting to allocate a
     * multi-billion-element list.
     */
    @Test
    void decodeClampsMaliciousStepCountInsteadOfOom() {
        FriendlyByteBuf buf = buffer();
        buf.writeBoolean(true);          // success
        buf.writeUtf("test:recipe");     // recipeId
        buf.writeUtf("Target");          // targetName
        buf.writeItem(ItemStack.EMPTY);  // targetResult
        buf.writeVarInt(Integer.MAX_VALUE); // steps count — malicious

        // decode clamps the count to MAX_DECODE_COUNT, then runs out of buffer
        // reading step bodies; that surfaces as a decode exception, NOT an
        // OutOfMemoryError from a giant preallocation.
        Throwable t = assertThrows(Throwable.class, () -> PlanResponsePacket.decode(buf));
        assertFalse(t instanceof OutOfMemoryError,
                "clamped count must not OOM; got " + t.getClass().getSimpleName());
    }
}
