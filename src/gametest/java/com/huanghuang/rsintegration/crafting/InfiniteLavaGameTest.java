package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.ModItems;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.feature.unifiedgrid.UnifiedGridFluidTransfer;
import com.refinedmods.refinedstorage.RSBlocks;
import com.refinedmods.refinedstorage.api.util.Action;
import com.refinedmods.refinedstorage.blockentity.DiskDriveBlockEntity;
import com.refinedmods.refinedstorage.blockentity.FluidInterfaceBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler.FluidAction;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(RSIntegrationMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class InfiniteLavaGameTest {
    @GameTest(template = "infinite_water", timeoutTicks = 150)
    public static void lavaDiskSuppliesOnlyLava(GameTestHelper test) {
        BlockPos controllerPos = new BlockPos(1, 1, 1);
        BlockPos drivePos = new BlockPos(2, 1, 1);
        BlockPos interfacePos = new BlockPos(3, 1, 1);
        test.setBlock(controllerPos, RSBlocks.CREATIVE_CONTROLLER.get(DyeColor.WHITE).get());
        test.setBlock(drivePos, RSBlocks.DISK_DRIVE.get());
        test.setBlock(interfacePos, RSBlocks.FLUID_INTERFACE.get());

        var drive = (DiskDriveBlockEntity) test.getBlockEntity(drivePos);
        var fluidInterface = (FluidInterfaceBlockEntity) test.getBlockEntity(interfacePos);
        var disks = drive.getNode().getDisks();
        var tank = fluidInterface.getCapability(ForgeCapabilities.FLUID_HANDLER, Direction.UP)
                .orElseThrow(() -> new IllegalStateException("流体接口未暴露管道能力"));
        var lava = new FluidStack(Fluids.LAVA, 1000);
        var water = new FluidStack(Fluids.WATER, 1000);
        test.assertTrue(disks.insertItem(0, new ItemStack(ModItems.INFINITY_LAVA_DISK.get()), false).isEmpty(),
                "岩浆盘未装入磁盘仓");
        fluidInterface.getNode().getOut().setFluid(0, lava.copy());

        test.runAfterDelay(60, () -> {
            var network = drive.getNode().getNetwork();
            test.assertTrue(network != null && network.canRun(), "磁盘仓未上线");
            test.assertTrue(network.extractFluid(water, 1000, Action.SIMULATE).isEmpty(), "岩浆盘不应供水");
            test.assertTrue(tank.drain(1000, FluidAction.EXECUTE).isFluidStackIdentical(lava),
                    "管道未能抽取一桶岩浆");
            var filled = UnifiedGridFluidTransfer.fill(network, new ItemStack(Items.BUCKET), lava);
            test.assertTrue(filled.cursor().is(Items.LAVA_BUCKET), "混合终端未装满岩浆桶");
            test.assertTrue(disks.insertItem(1, new ItemStack(ModItems.INFINITY_WATER_DISK.get()), false).isEmpty(),
                    "水盘未装入磁盘仓");
            test.assertTrue(network.extractFluid(water, 1000, Action.PERFORM).isFluidStackIdentical(water),
                    "水盘与岩浆盘同仓时无法供水");

            test.runAfterDelay(25, () -> {
                test.assertTrue(tank.drain(1000, FluidAction.EXECUTE).isFluidStackIdentical(lava),
                        "管道再次抽取时接口未补满岩浆");
                disks.extractItem(0, 1, false);
                test.assertTrue(network.extractFluid(lava, 1000, Action.SIMULATE).isEmpty(), "拔出岩浆盘后仍能取岩浆");
                test.assertTrue(network.extractFluid(water, 1000, Action.PERFORM).isFluidStackIdentical(water),
                        "拔出岩浆盘影响了水盘供水");
                test.succeed();
            });
        });
    }
}
