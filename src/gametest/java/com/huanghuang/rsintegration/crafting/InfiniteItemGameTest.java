package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.storage.disk.rs.InfiniteDisk;
import com.huanghuang.rsintegration.storage.disk.rs.InfiniteDiskItem;
import com.refinedmods.refinedstorage.RSBlocks;
import com.refinedmods.refinedstorage.api.storage.StorageType;
import com.refinedmods.refinedstorage.api.util.Action;
import com.refinedmods.refinedstorage.api.util.IComparer;
import com.refinedmods.refinedstorage.blockentity.ControllerBlockEntity;
import com.refinedmods.refinedstorage.blockentity.DiskDriveBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.registries.RegisterEvent;

@Mod.EventBusSubscriber(modid = RSIntegrationMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
@GameTestHolder(RSIntegrationMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class InfiniteItemGameTest {
    private static InfiniteDiskItem disk;

    @SubscribeEvent
    public static void registerTestDisk(RegisterEvent event) {
        event.register(Registries.ITEM, new ResourceLocation(RSIntegrationMod.MOD_ID, "test_infinite_item_disk"),
                () -> disk = new InfiniteDiskItem(StorageType.ITEM, new ResourceLocation("minecraft", "cobblestone")));
    }

    @GameTest(template = "infinite_water", timeoutTicks = 200)
    public static void suppliesItemsThroughTheSameDisk(GameTestHelper test) {
        var controllerPos = new BlockPos(1, 1, 1);
        var drivePos = new BlockPos(2, 1, 1);
        test.setBlock(controllerPos, RSBlocks.CREATIVE_CONTROLLER.get(DyeColor.WHITE).get());
        test.setBlock(drivePos, RSBlocks.DISK_DRIVE.get());
        var controller = (ControllerBlockEntity) test.getBlockEntity(controllerPos);
        var drive = (DiskDriveBlockEntity) test.getBlockEntity(drivePos);
        var disks = drive.getNode().getDisks();
        test.assertTrue(disks.insertItem(0, new ItemStack(disk), false).isEmpty(), "物品盘无法装入磁盘仓");

        test.runAfterDelay(60, () -> {
            var network = controller.getNetwork();
            var cobble = new ItemStack(Items.COBBLESTONE);
            var record = disks.getStackInSlot(0).getTagElement("InfiniteResource");
            test.assertTrue(record != null && record.getString("type").equals("item")
                    && record.getString("id").equals("minecraft:cobblestone"), "物品盘未写入通用资源记录");
            test.assertTrue(drive.getNode().getItemDisks()[0] instanceof InfiniteDisk<?>
                    && drive.getNode().getFluidDisks()[0] == null, "物品盘挂载到了错误通道");
            test.assertTrue(InfiniteDisk.supplies(network, cobble), "网络未识别在线的无限物品供给");
            test.assertTrue(network.extractItem(cobble, 64, Action.SIMULATE).getCount() == 64, "物品盘不能模拟提取");
            test.assertTrue(network.extractItem(cobble, 64, Action.PERFORM).getCount() == 64, "物品盘不能实际提取");
            var tagged = cobble.copy();
            tagged.getOrCreateTag().putString("TestVariant", "different");
            test.assertTrue(network.extractItem(tagged, 64, Action.PERFORM).isEmpty(), "物品盘生成了不匹配的 NBT 变体");
            test.assertTrue(network.insertItem(cobble, 64, Action.PERFORM).getCount() == 64, "物品盘应该拒收全部物品");
            var cache = network.getItemStorageCache();
            test.assertTrue(cache.getList().getCount(cobble, IComparer.COMPARE_NBT) == Integer.MAX_VALUE,
                    "提取后物品盘的可用量降低");

            var extracted = disks.extractItem(0, 1, false);
            test.assertTrue(extracted.is(disk) && record.equals(extracted.getTagElement("InfiniteResource")), "拔盘后物品或记录丢失");
            test.assertTrue(network.extractItem(cobble, 64, Action.PERFORM).isEmpty()
                    && cache.getList().getCount(cobble, IComparer.COMPARE_NBT) == 0, "拔盘后仍有虚拟物品库存");
            test.succeed();
        });
    }
}
