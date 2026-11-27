package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.ModItems;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.endpoint.SessionCraftStorageEndpoint;
import com.huanghuang.rsintegration.crafting.graph.MaterialKey;
import com.huanghuang.rsintegration.feature.unifiedgrid.UnifiedGridFluidTransfer;
import com.huanghuang.rsintegration.mods.ironsspellbooks.InkFluidSupport;
import com.huanghuang.rsintegration.storage.StorageFluidKey;
import com.huanghuang.rsintegration.storage.StorageItemChangeListener;
import com.huanghuang.rsintegration.storage.rs.RefinedStorageBackend;
import com.mojang.authlib.GameProfile;
import com.refinedmods.refinedstorage.RSBlocks;
import com.refinedmods.refinedstorage.api.network.INetwork;
import com.refinedmods.refinedstorage.api.util.Action;
import com.refinedmods.refinedstorage.api.util.IComparer;
import com.refinedmods.refinedstorage.apiimpl.API;
import com.refinedmods.refinedstorage.apiimpl.network.NetworkNodeManager;
import com.refinedmods.refinedstorage.apiimpl.network.node.DiskState;
import com.refinedmods.refinedstorage.apiimpl.network.node.diskdrive.DiskDriveNetworkNode;
import com.refinedmods.refinedstorage.blockentity.ControllerBlockEntity;
import com.refinedmods.refinedstorage.blockentity.DiskDriveBlockEntity;
import com.refinedmods.refinedstorage.blockentity.FluidInterfaceBlockEntity;
import com.refinedmods.refinedstorage.blockentity.config.RedstoneMode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler.FluidAction;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.items.IItemHandlerModifiable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@GameTestHolder(RSIntegrationMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class InfiniteWaterGameTest {
    private static final BlockPos CONTROLLER = new BlockPos(1, 1, 1);
    private static final BlockPos DRIVE = new BlockPos(2, 1, 1);
    private static final BlockPos INTERFACE = new BlockPos(3, 1, 1);

    @GameTest(template = "infinite_water", timeoutTicks = 260)
    public static void suppliesPipePlayerAndCraftingAccount(GameTestHelper test) {
        test.setBlock(CONTROLLER, RSBlocks.CREATIVE_CONTROLLER.get(DyeColor.WHITE).get());
        test.setBlock(DRIVE, RSBlocks.DISK_DRIVE.get());
        test.setBlock(INTERFACE, RSBlocks.FLUID_INTERFACE.get());

        var controller = (ControllerBlockEntity) test.getBlockEntity(CONTROLLER);
        var drive = (DiskDriveBlockEntity) test.getBlockEntity(DRIVE);
        var fluidInterface = (FluidInterfaceBlockEntity) test.getBlockEntity(INTERFACE);
        var disks = (IItemHandlerModifiable) drive.getNode().getDisks();
        var waterDisk = new ItemStack(ModItems.INFINITY_WATER_DISK.get());
        var tank = fluidInterface.getCapability(ForgeCapabilities.FLUID_HANDLER, Direction.UP)
                .orElseThrow(() -> new IllegalStateException("流体接口未暴露管道能力"));
        disks.insertItem(0, waterDisk, false);
        disks.insertItem(1, waterDisk.copy(), false);
        fluidInterface.getNode().getOut().setFluid(0, water(1000));

        test.runAfterDelay(60, () -> {
            INetwork network = controller.getNetwork();
            test.assertTrue(network.canRun() && drive.getNode().getNetwork() == network, "磁盘仓未上线");
            test.assertTrue(tank.drain(1000, FluidAction.SIMULATE).getAmount() == 1000, "管道无法模拟抽水");
            test.assertTrue(tank.drain(1000, FluidAction.EXECUTE).getAmount() == 1000, "管道无法实际抽水");
            assertWaterRecord(test, disks.getStackInSlot(0));
            var player = new FakePlayer(test.getLevel(), new GameProfile(UUID.randomUUID(), "RSIWater"));
            player.getInventory().setItem(0, new ItemStack(Items.BUCKET));
            var entry = network.getFluidStorageCache().getList().getEntry(water(1), IComparer.COMPARE_NBT);
            network.getFluidGridHandler().onExtract(player, entry.getId(), false);
            test.assertTrue(player.containerMenu.getCarried().is(Items.WATER_BUCKET), "原生流体终端未装满水桶");
            var filled = UnifiedGridFluidTransfer.fill(network, new ItemStack(Items.BUCKET), water(1));
            test.assertTrue(filled.cursor().is(Items.WATER_BUCKET), "混合终端未装满水桶");
            var rejected = UnifiedGridFluidTransfer.empty(network, new ItemStack(Items.WATER_BUCKET));
            test.assertTrue(rejected.transferred() == 0 && rejected.cursor().is(Items.WATER_BUCKET), "无普通库存时倒水应保留满桶");

            var session = new RefinedStorageBackend().openSession(network);
            var endpoint = new SessionCraftStorageEndpoint(session);
            var key = StorageFluidKey.fromStack(session.reference().backendId(), water(1));
            var snapshot = session.snapshotFluids(player).snapshotValue().orElseThrow();
            test.assertTrue(snapshot.infiniteFluids().contains(key) && snapshot.countExact(key) == 0, "无限供给不应计作实际库存");
            long large = (long) Integer.MAX_VALUE + 500;
            test.assertTrue(session.extractFluidExact(player, key, large, true).transferredAmountExact().orElseThrow() == large,
                    "大额模拟取料不应被 RS 展示上限截断");

            var admitted = new RuntimeMaterialAccount(UUID.randomUUID(), player.getUUID(), player.server, endpoint);
            var pending = new RuntimeMaterialAccount(UUID.randomUUID(), player.getUUID(), player.server, endpoint);
            var material = MaterialKey.of(InkFluidSupport.token(water(1)));
            var order = Map.of(material, 2500L);
            try {
                test.assertTrue(admitted.ensure(order, List.of(), player) && admitted.count(material) == 2500,
                        "下单时未把实际取出的水记入任务账户");

                disks.extractItem(0, 1, false);
                var ordinary = BuiltInRegistries.ITEM.get(new ResourceLocation("refinedstorage", "64k_fluid_storage_disk"));
                var finiteDisk = new ItemStack(ordinary);
                ordinary.inventoryTick(finiteDisk, test.getLevel(), player, 0, false);
                test.assertTrue(disks.insertItem(0, finiteDisk, false).isEmpty(), "普通盘未装入磁盘仓");
                test.assertTrue(network.insertFluid(water(3000), 3000, Action.PERFORM).isEmpty(), "普通盘无法存水");
                test.assertTrue(count(network) == Integer.MAX_VALUE, "取水或拔掉一张盘导致无限供给消失");
                test.assertTrue(network.extractFluid(water(1), 3500, Action.PERFORM).getAmount() == 3500, "普通盘与无限盘无法合计供水");
                test.assertTrue(network.insertFluid(water(700), 700, Action.PERFORM).isEmpty(), "普通盘无法再次存水");
                var removed = disks.extractItem(1, 1, false);
                test.assertTrue(count(network) == 700, "最后一张无限盘拔出后，普通库存不正确");
                test.assertFalse(pending.ensure(order, List.of(), player), "拔盘后不应准入水量不足的任务");
                test.assertTrue(count(network) == 700, "未准入任务不应扣掉普通盘里的水");
                var access = TaskFluidAccess.capture(admitted, player, endpoint);
                var ownedWater = StorageFluidKey.fromStack(access.snapshot().backendId(), water(1));
                test.assertTrue(access.extract(ownedWater, 2500, false).transferredAmountExact().orElseThrow() == 2500,
                        "拔盘后，已准入的任务仍应能消耗自己的水");
                test.assertTrue(admitted.count(material) == 0, "取料后任务不应重复持有已消耗的水");

                disks.extractItem(0, 1, false);
                disks.insertItem(1, removed, false);
                test.assertTrue(count(network) == Integer.MAX_VALUE, "重新插盘后未恢复供水");
            } finally {
                admitted.close();
                pending.close();
            }
            test.runAfterDelay(25, () -> {
                test.assertTrue(tank.drain(1000, FluidAction.EXECUTE).getAmount() == 1000, "管道再次抽水时接口未补满");
                controller.setRedstoneMode(RedstoneMode.HIGH);
                test.runAfterDelay(30, () -> {
                    test.assertTrue(!network.canRun() && tank.drain(1000, FluidAction.EXECUTE).isEmpty(), "控制器离线后接口仍在补水");
                    test.assertTrue(disks.getStackInSlot(1).is(ModItems.INFINITY_WATER_DISK.get()), "断电后盘从槽位消失");
                    controller.setRedstoneMode(RedstoneMode.IGNORE);
                    test.succeedWhen(() -> test.assertTrue(tank.drain(1000, FluidAction.SIMULATE).getAmount() == 1000,
                            "重新供电后接口未恢复补水"));
                });
            });
        });
    }

    @GameTest(template = "infinite_water", timeoutTicks = 240)
    public static void restoresWaterDiskFromSavedNode(GameTestHelper test) {
        test.setBlock(CONTROLLER, RSBlocks.CREATIVE_CONTROLLER.get(DyeColor.WHITE).get());
        test.setBlock(DRIVE, RSBlocks.DISK_DRIVE.get());
        var drive = (DiskDriveBlockEntity) test.getBlockEntity(DRIVE);
        var original = drive.getNode();
        var item = new ItemStack(ModItems.INFINITY_WATER_DISK.get());
        item.getOrCreateTag().putString("TestMarker", "keep-me");
        original.getDisks().insertItem(3, item, false);
        var legacy = new ItemStack(ModItems.INFINITY_WATER_DISK.get());
        legacy.getOrCreateTag().putString("Fluid", "minecraft:water");
        original.getDisks().insertItem(5, legacy, false);
        test.runAfterDelay(60, () -> {
            INetwork oldNetwork = original.getNetwork();
            test.assertTrue(oldNetwork != null && count(oldNetwork) == Integer.MAX_VALUE, "存档前未挂载水盘");
            test.setBlock(CONTROLLER, Blocks.AIR);
            var level = test.getLevel();
            var pos = test.absolutePos(DRIVE);
            var saved = new NetworkNodeManager(level);
            saved.setNode(pos, original);
            var restored = new NetworkNodeManager(level);
            restored.load(saved.save(new CompoundTag()));
            var reloaded = (DiskDriveNetworkNode) restored.getNode(pos);
            test.assertTrue(reloaded != null && reloaded != original, "未从存档创建新磁盘仓节点");
            for (int slot : new int[]{3, 5}) {
                var stack = reloaded.getDisks().getStackInSlot(slot);
                var expected = slot == 3 ? item : legacy;
                test.assertTrue(stack.is(expected.getItem()) && stack.getCount() == 1 && expected.getTag().equals(stack.getTag()),
                        "读档后水盘丢失、变更槽位或丢失 NBT：" + slot);
                assertWaterRecord(test, stack);
            }
            test.assertTrue(oldNetwork.extractFluid(water(1), 1000, Action.SIMULATE).isEmpty(), "旧网络仍能从已断开的磁盘仓取水");
            API.instance().getNetworkNodeManager(level).setNode(pos, reloaded);
            test.setBlock(CONTROLLER, RSBlocks.CREATIVE_CONTROLLER.get(DyeColor.WHITE).get());
            test.succeedWhen(() -> {
                var network = reloaded.getNetwork();
                test.assertTrue(network != null && network.canRun() && count(network) == Integer.MAX_VALUE,
                        "读档后水盘在槽位里，但没有自动恢复供水");
                var tagged = water(1);
                tagged.getOrCreateTag().putInt("Purity", 3);
                test.assertTrue(network.extractFluid(tagged, 1000, Action.SIMULATE).isEmpty(), "不应生成带 NBT 的水");
                test.assertTrue(network.extractFluid(new FluidStack(Fluids.LAVA, 1), 1000, Action.SIMULATE).isEmpty(), "水盘不应供给岩浆");
                test.assertTrue(network.extractFluid(water(1), 1000, Action.PERFORM).getAmount() == 1000,
                        "读档后不能实际取水");
            });
        });
    }

    @GameTest(template = "infinite_water", timeoutTicks = 150)
    public static void extractionKeepsInfiniteWaterInLiveInventory(GameTestHelper test) {
        test.setBlock(CONTROLLER, RSBlocks.CREATIVE_CONTROLLER.get(DyeColor.WHITE).get());
        test.setBlock(DRIVE, RSBlocks.DISK_DRIVE.get());
        var controller = (ControllerBlockEntity) test.getBlockEntity(CONTROLLER);
        var drive = (DiskDriveBlockEntity) test.getBlockEntity(DRIVE);
        var disks = drive.getNode().getDisks();
        disks.insertItem(0, new ItemStack(ModItems.INFINITY_WATER_DISK.get()), false);

        test.runAfterDelay(60, () -> {
            var network = controller.getNetwork();
            var session = new RefinedStorageBackend().openSession(network);
            var player = new FakePlayer(test.getLevel(), new GameProfile(UUID.randomUUID(), "RSILiveWater"));
            var key = session.itemKey(InkFluidSupport.token(water(1)));
            test.assertTrue(session.snapshotItems(player).snapshot().orElseThrow().countExact(key) == Long.MAX_VALUE,
                    "初始快照没有无限水");
            List<Long> amounts = new ArrayList<>();
            try (var subscription = session.subscribeItemChanges(new StorageItemChangeListener() {
                @Override public void onChanged(ItemStack stack, long amount) {}

                @Override public void onFluidChanged(FluidStack stack, long amount) {
                    if (stack.getFluid() == Fluids.WATER) amounts.add(amount);
                }
            }).orElseThrow()) {
                test.assertTrue(network.extractFluid(water(1), 1000, Action.PERFORM).getAmount() == 1000,
                        "网络未实际取出水");
                test.assertTrue(amounts.equals(List.of(Long.MAX_VALUE)), "抽水后库存通知丢失无限供给：" + amounts);

                var ordinary = BuiltInRegistries.ITEM.get(new ResourceLocation("refinedstorage", "64k_fluid_storage_disk"));
                var finiteDisk = new ItemStack(ordinary);
                ordinary.inventoryTick(finiteDisk, test.getLevel(), player, 0, false);
                test.assertTrue(disks.insertItem(1, finiteDisk, false).isEmpty(), "普通盘未装入磁盘仓");
                amounts.clear();
                test.assertTrue(network.insertFluid(water(700), 700, Action.PERFORM).isEmpty(), "普通盘无法存水");
                test.assertTrue(amounts.equals(List.of(Long.MAX_VALUE)), "普通盘存水后覆盖了无限供给：" + amounts);

                disks.extractItem(0, 1, false);
                test.assertTrue(session.snapshotItems(player).snapshot().orElseThrow().countExact(key) == 700,
                        "拔盘后快照未恢复普通库存");
                amounts.clear();
                test.assertTrue(network.extractFluid(water(1), 200, Action.PERFORM).getAmount() == 200,
                        "拔掉无限盘后未能取出普通库存里的水");
                test.assertTrue(amounts.equals(List.of(500L)), "拔盘后库存通知未恢复普通库存：" + amounts);
            }
            test.succeed();
        });
    }

    @GameTest(template = "infinite_water", timeoutTicks = 150)
    public static void supplyDoesNotChangeReportedDriveCapacity(GameTestHelper test) {
        test.setBlock(CONTROLLER, RSBlocks.CREATIVE_CONTROLLER.get(DyeColor.WHITE).get());
        test.setBlock(DRIVE, RSBlocks.DISK_DRIVE.get());
        var drive = (DiskDriveBlockEntity) test.getBlockEntity(DRIVE);
        var disks = drive.getNode().getDisks();
        var capacity = DiskDriveBlockEntity.CAPACITY.getValueProducer();
        disks.insertItem(0, new ItemStack(ModItems.INFINITY_WATER_DISK.get()), false);

        test.runAfterDelay(60, () -> {
            test.assertTrue(drive.getNode().getNetwork().canRun(), "磁盘仓未上线");
            test.assertTrue(capacity.apply(drive) == 0L, "仅装无限水盘时磁盘仓显示了存储容量：" + capacity.apply(drive));
            test.assertTrue(drive.getNode().getDiskState()[0] == DiskState.NORMAL, "在线供给盘不应亮满盘灯");

            var ordinary = BuiltInRegistries.ITEM.get(new ResourceLocation("refinedstorage", "64k_fluid_storage_disk"));
            var finiteDisk = new ItemStack(ordinary);
            var player = new FakePlayer(test.getLevel(), new GameProfile(UUID.randomUUID(), "RSIDriveCapacity"));
            ordinary.inventoryTick(finiteDisk, test.getLevel(), player, 0, false);
            test.assertTrue(disks.insertItem(1, finiteDisk, false).isEmpty(), "普通盘未装入磁盘仓");
            long ordinaryCapacity = drive.getNode().getFluidDisks()[1].getCapacity();
            test.assertTrue(ordinaryCapacity > 0 && capacity.apply(drive) == ordinaryCapacity,
                    "无限供给盘改变了普通盘的存储容量：" + capacity.apply(drive));
            test.succeed();
        });
    }

    private static FluidStack water(int amount) { return new FluidStack(Fluids.WATER, amount); }

    private static void assertWaterRecord(GameTestHelper test, ItemStack disk) {
        var record = disk.getTagElement("InfiniteResource");
        test.assertTrue(record != null && record.getString("type").equals("fluid")
                && record.getString("id").equals("minecraft:water"), "水盘未保存通用资源记录");
    }

    private static int count(INetwork network) {
        return network.getFluidStorageCache().getList().getCount(water(1), IComparer.COMPARE_NBT);
    }
}
