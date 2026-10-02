package com.huanghuang.rsintegration.mods.common;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.crafting.CraftStorageEndpoint;
import com.huanghuang.rsintegration.mods.ironsspellbooks.InkFluidSupport;
import com.huanghuang.rsintegration.storage.StorageBackendId;
import com.huanghuang.rsintegration.storage.StorageOperationMode;
import com.huanghuang.rsintegration.storage.StorageOperationResult;
import com.huanghuang.rsintegration.storage.StorageReference;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.templates.FluidTank;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MachineWaterSupplyEndpointTest extends BootstrapTest {
    private MockedStatic<InkFluidSupport> tokens;
    private CraftStorageEndpoint endpoint;
    private ServerPlayer player;

    @BeforeEach
    void configurePaidWaterAndTokenBoundary() {
        CommentedConfig config = CommentedConfig.inMemory();
        RSIntegrationConfig.SERVER_SPEC.correct(config);
        config.set("autoCrafting.freeWaterMachines", List.of());
        RSIntegrationConfig.SERVER_SPEC.setConfig(config);
        tokens = mockStatic(InkFluidSupport.class);
        tokens.when(() -> InkFluidSupport.token(any(FluidStack.class)))
                .thenAnswer(invocation -> token(invocation.getArgument(0)));
        tokens.when(() -> InkFluidSupport.fluid(any(ItemStack.class)))
                .thenAnswer(invocation -> fluid(invocation.getArgument(0)));
        endpoint = mock(CraftStorageEndpoint.class, RETURNS_DEEP_STUBS);
        when(endpoint.session().reference()).thenReturn(new StorageReference(new StorageBackendId("refinedstorage"), "test"));
        player = mock(ServerPlayer.class);
    }

    @AfterEach
    void resetConfigAndTokenBoundary() {
        tokens.close();
        RSIntegrationConfig.SERVER_SPEC.setConfig(null);
    }

    @Test
    void paidWaterExtractsOnlyTheExactDeficitFromTheSelectedEndpoint() {
        extracted(250, new FluidStack(Fluids.WATER, 250), true);
        extracted(250, new FluidStack(Fluids.WATER, 250), false);
        FluidTank tank = tankWithWater(750);
        assertEquals(250, MachineWaterSupply.fill("eidolon_crucible", tank, 250, endpoint, player));
        assertEquals(1000, tank.getFluidAmount());
        verify(endpoint).extractExact(eq(player), any(ItemStack.class), eq(250L), eq(true));
        verify(endpoint).extractExact(eq(player), any(ItemStack.class), eq(250L), eq(false));
        verify(endpoint, never()).insert(eq(player), any(ItemStack.class), eq(false));
    }

    @Test
    void insufficientPreflightDoesNotPerformAnExtraction() {
        extracted(250, new FluidStack(Fluids.WATER, 100), true);
        FluidTank tank = tankWithWater(750);
        assertEquals(0, MachineWaterSupply.fill("eidolon_crucible", tank, 250, endpoint, player));
        assertEquals(750, tank.getFluidAmount());
        verify(endpoint, never()).extractExact(eq(player), any(ItemStack.class), eq(250L), eq(false));
    }

    @Test
    void storageRaceRefundsPartialExtractionWithoutAddingWater() {
        extracted(250, new FluidStack(Fluids.WATER, 250), true);
        extracted(250, new FluidStack(Fluids.WATER, 100), false);
        acceptRefund();
        FluidTank tank = tankWithWater(750);
        assertEquals(0, MachineWaterSupply.fill("eidolon_crucible", tank, 250, endpoint, player));
        assertEquals(750, tank.getFluidAmount());
        assertEquals(100, refund().getCount());
    }

    @Test
    void incorrectNativeFluidIsRefundedAsItselfRatherThanConvertedIntoWater() {
        extracted(250, new FluidStack(Fluids.WATER, 250), true);
        extracted(250, new FluidStack(Fluids.LAVA, 250), false);
        acceptRefund();
        FluidTank tank = tankWithWater(750);
        assertEquals(0, MachineWaterSupply.fill("eidolon_crucible", tank, 250, endpoint, player));
        assertEquals(750, tank.getFluidAmount());
        assertEquals(Fluids.LAVA, fluid(refund()).getFluid());
    }

    @Test
    void aNonRsEndpointCannotBeSilentlyReplacedByRsForPaidWater() {
        when(endpoint.session().reference()).thenReturn(new StorageReference(new StorageBackendId("beyonddimensions"), "test"));
        FluidTank tank = tankWithWater(750);
        assertFalse(MachineWaterSupply.canFill("eidolon_crucible", tank, 250, endpoint, player));
        assertEquals(0, MachineWaterSupply.fill("eidolon_crucible", tank, 250, endpoint, player));
        assertEquals(750, tank.getFluidAmount());
        verify(endpoint, never()).extractExact(eq(player), any(ItemStack.class), eq(250L), eq(false));
        tokens.verifyNoInteractions();
    }

    private void extracted(int amount, FluidStack extracted, boolean simulate) {
        when(endpoint.extractExact(eq(player), any(ItemStack.class), eq((long) amount), eq(simulate)))
                .thenReturn(StorageOperationResult.extracted(simulate ? StorageOperationMode.SIMULATE : StorageOperationMode.PERFORM,
                        amount, List.of(token(extracted))));
    }

    private void acceptRefund() {
        when(endpoint.insert(eq(player), any(ItemStack.class), eq(false)))
                .thenAnswer(invocation -> StorageOperationResult.inserted(StorageOperationMode.PERFORM,
                        invocation.getArgument(1), ItemStack.EMPTY));
    }

    private ItemStack refund() {
        ArgumentCaptor<ItemStack> refunded = ArgumentCaptor.forClass(ItemStack.class);
        verify(endpoint).insert(eq(player), refunded.capture(), eq(false));
        return refunded.getValue();
    }

    private static FluidTank tankWithWater(int amount) {
        FluidTank tank = new FluidTank(1000);
        tank.setFluid(new FluidStack(Fluids.WATER, amount));
        return tank;
    }

    private static ItemStack token(FluidStack fluid) {
        ItemStack token = new ItemStack(Items.PAPER, fluid.getAmount());
        FluidStack identity = fluid.copy();
        identity.setAmount(1);
        token.setTag(identity.writeToNBT(new CompoundTag()));
        return token;
    }

    private static FluidStack fluid(ItemStack token) {
        FluidStack result = FluidStack.loadFluidStackFromNBT(token.getTag());
        result.setAmount(token.getCount());
        return result;
    }
}
