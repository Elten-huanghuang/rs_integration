package com.huanghuang.rsintegration.magic;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用 Botania 自己的 {@code ManaTabletItem$ManaItemImpl} 验证「物品堆 → 能力 → 魔力读写」
 * 这条链路，不启动游戏、也不注册 Botania。
 *
 * <p>为什么需要这层测试：石板这类容器不是靠物品类实现 {@code ManaItem}，能力是 Botania 在
 * {@code AttachCapabilitiesEvent} 里挂到物品堆上的；只测反射壳子测不出「能力拿得到、
 * 加魔力调得动、堆叠语义对得上」。</p>
 */
class BotaniaManaItemBridgeTest extends BootstrapTest {

    private static final String MANA_ITEM_IMPL = "vazkii.botania.common.item.ManaTabletItem$ManaItemImpl";
    private static final int TABLET_CAPACITY = 500_000;

    @Test
    void tabletImplementationExposesTheManaItemContract() throws Exception {
        Class<?> contract = Class.forName("vazkii.botania.api.mana.ManaItem");
        Class<?> implementation = Class.forName(MANA_ITEM_IMPL);
        assertTrue(contract.isAssignableFrom(implementation),
                "石板的能力实体应当实现 ManaItem");

        ItemStack tablet = new ItemStack(Items.STONE);
        Object capability = implementation.getConstructor(ItemStack.class).newInstance(tablet);

        // 这些名字必须和 MagicGridItemTransfer 里查的一致，否则真机上会读不到。
        Method getMana = contract.getMethod("getMana");
        Method getMaxMana = contract.getMethod("getMaxMana");
        Method addMana = contract.getMethod("addMana", int.class);

        assertEquals(0, (Integer) getMana.invoke(capability));
        assertEquals(TABLET_CAPACITY, (Integer) getMaxMana.invoke(capability));

        addMana.invoke(capability, 1_234);
        assertEquals(1_234, (Integer) getMana.invoke(capability));
        // 石板把魔力存在物品 NBT 上，所以同一个物品堆再读一次结果一致。
        assertEquals(1_234, (Integer) getMana.invoke(capability));
    }

    @Test
    void negativeDeltaDrainsTheTabletAndNeverIncreasesIt() throws Exception {
        Class<?> contract = Class.forName("vazkii.botania.api.mana.ManaItem");
        ItemStack tablet = new ItemStack(Items.STONE);
        Object capability = Class.forName(MANA_ITEM_IMPL)
                .getConstructor(ItemStack.class).newInstance(tablet);
        Method getMana = contract.getMethod("getMana");
        Method addMana = contract.getMethod("addMana", int.class);

        addMana.invoke(capability, 5_000);
        // MagicGridItemTransfer 充入时传的就是负数，这里确认它走的是扣减分支。
        addMana.invoke(capability, -2_000);
        assertEquals(3_000, (Integer) getMana.invoke(capability));

        // 扣超额也不能变成负的余额。
        addMana.invoke(capability, -10_000);
        assertTrue((Integer) getMana.invoke(capability) >= 0, "扣减超额后余额不应为负");
    }

    @Test
    void tabletCapacityScalesWithStackCount() throws Exception {
        Class<?> contract = Class.forName("vazkii.botania.api.mana.ManaItem");
        ItemStack tablets = new ItemStack(Items.STONE, 2);
        Object capability = Class.forName(MANA_ITEM_IMPL)
                .getConstructor(ItemStack.class).newInstance(tablets);
        Method getMaxMana = contract.getMethod("getMaxMana");
        assertNotNull(getMaxMana);
        assertEquals(TABLET_CAPACITY * 2, (Integer) getMaxMana.invoke(capability),
                "容量按堆叠数量放大，所以统一盘按单堆记账时要注意这一点");
    }
}
