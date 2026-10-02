package com.huanghuang.rsintegration.mods.ironsspellbooks;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import com.refinedmods.refinedstorage.api.network.INetwork;
import com.refinedmods.refinedstorage.api.util.Action;
import io.netty.handler.codec.DecoderException;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.fluids.FluidStack;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class InkFluidSupportTest extends BootstrapTest {
    private static InkFluidItem TOKEN;

    @BeforeAll
    static void createTokenItem() { TOKEN = InkFluidTestFixtures.tokenItem(); }

    @Test
    void roundTripRetainsAmountAndFluidNbtWithoutChangingIdentity() {
        FluidStack water = new FluidStack(Fluids.WATER, 250);
        water.getOrCreateTag().putString("variant", "test");
        ItemStack token = InkFluidSupport.token(TOKEN, water);
        assertTrue(InkFluidSupport.isToken(token));
        assertEquals(250, token.getCount());
        assertEquals(1, token.getTag().getInt("Amount"));
        assertTrue(water.isFluidStackIdentical(InkFluidSupport.fluid(token)));

        FriendlyByteBuf buffer = mock(FriendlyByteBuf.class);
        InkFluidSupport.writePacket(buffer, token);
        ArgumentCaptor<ItemStack> encoded = ArgumentCaptor.forClass(ItemStack.class);
        verify(buffer).writeItem(encoded.capture());
        assertEquals(1, encoded.getValue().getCount());
        assertEquals(250, encoded.getValue().getTag().getInt("RSIFluidAmount"));
        when(buffer.readItem()).thenReturn(encoded.getValue());
        ItemStack decoded = InkFluidSupport.readPacket(buffer);
        assertEquals(250, decoded.getCount());
        assertEquals(token.getTag(), decoded.getTag());
        assertFalse(token.getTag().contains("RSIFluidAmount"));
    }

    @Test
    void rejectsNonpositivePacketAmount() {
        FriendlyByteBuf buffer = mock(FriendlyByteBuf.class);
        ItemStack encoded = InkFluidSupport.token(TOKEN, new FluidStack(Fluids.WATER, 1));
        encoded.getOrCreateTag().putInt("RSIFluidAmount", -1);
        when(buffer.readItem()).thenReturn(encoded);
        assertThrows(DecoderException.class, () -> InkFluidSupport.readPacket(buffer));
    }

    @Test
    void realPacketRetainsAmountsAboveTheVanillaByteLimit() {
        for (int amount : new int[]{1, 250, 1000, Integer.MAX_VALUE}) {
            ItemStack token = InkFluidSupport.token(TOKEN, new FluidStack(Fluids.WATER, amount));
            FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
            try {
                InkFluidSupport.writePacket(buffer, token);
                ItemStack decoded = InkFluidSupport.readPacket(buffer);
                assertEquals(amount, decoded.getCount());
                assertEquals(token.getTag(), decoded.getTag());
                assertEquals(0, buffer.readableBytes());
            } finally {
                buffer.release();
            }
        }
    }

    @Test
    void insertsAndExtractsThroughRsFluidApiOnly() {
        INetwork network = mock(INetwork.class);
        ItemStack input = InkFluidSupport.token(TOKEN, new FluidStack(Fluids.WATER, 250));
        when(network.insertFluid(any(FluidStack.class), eq(250), eq(Action.SIMULATE)))
                .thenReturn(new FluidStack(Fluids.WATER, 100));
        when(network.insertFluid(any(FluidStack.class), eq(250), eq(Action.PERFORM)))
                .thenReturn(FluidStack.EMPTY);
        when(network.extractFluid(any(FluidStack.class), eq(250), eq(Action.PERFORM)))
                .thenReturn(new FluidStack(Fluids.WATER, 250));
        assertEquals(100, InkFluidSupport.insert(network, input, true).getCount());
        assertTrue(InkFluidSupport.insert(network, input, false).isEmpty());
        assertEquals(250, InkFluidSupport.extract(network, input, 250, false).getCount());
        verify(network, never()).insertItem(any(), anyInt(), any());
        verify(network, never()).extractItem(any(), anyInt(), any());
    }

    @Test
    void doesNotDisguiseAnIncorrectNativeFluidIdentity() {
        INetwork network = mock(INetwork.class);
        ItemStack template = InkFluidSupport.token(TOKEN, new FluidStack(Fluids.WATER, 1));
        when(network.extractFluid(any(FluidStack.class), eq(250), eq(Action.PERFORM)))
                .thenReturn(new FluidStack(Fluids.LAVA, 250));
        ItemStack extracted = InkFluidSupport.extract(network, template, 250, false);
        assertEquals(Fluids.LAVA, InkFluidSupport.fluid(extracted).getFluid());
        assertFalse(ItemStack.isSameItemSameTags(template, extracted));
    }

    @Test
    void rejectsNullNativeInsertResponse() {
        INetwork network = mock(INetwork.class);
        ItemStack template = InkFluidSupport.token(TOKEN, new FluidStack(Fluids.WATER, 250));
        assertThrows(IllegalStateException.class, () -> InkFluidSupport.insert(network, template, true));
    }
}
