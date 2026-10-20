package com.huanghuang.rsintegration.magic;

import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.ForgeRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntUnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * 魔力石板这类容器不是靠物品类实现 {@code ManaItem}，而是 Botania 在
 * {@code AttachCapabilitiesEvent} 里把能力挂到物品堆上。单测没有 Botania，
 * 这里用接口代理模拟 {@code ManaItem}，验证识别、读数和写入都走能力这条路。
 */
class MagicGridItemTransferTest extends BootstrapTest {

    private static final String MANA_ITEM = "vazkii.botania.api.mana.ManaItem";
    private static final int MAX_MANA = 500_000;
    private MockedStatic<ModList> modListLoading;
    private ForgeConfigSpec.BooleanValue previousBotaniaConfig;
    private ForgeConfigSpec.BooleanValue previousArsConfig;

    @BeforeEach
    void mockBotaniaAsInstalled() {
        previousBotaniaConfig = RSIntegrationConfig.ENABLE_BOTANIA;
        previousArsConfig = RSIntegrationConfig.ENABLE_ARS_NOUVEAU;
        RSIntegrationConfig.ENABLE_BOTANIA = null;
        RSIntegrationConfig.ENABLE_ARS_NOUVEAU = null;
        ModList mods = mock(ModList.class);
        when(mods.isLoaded("botania")).thenReturn(true);
        when(mods.isLoaded("ars_nouveau")).thenReturn(true);
        modListLoading = mockStatic(ModList.class);
        modListLoading.when(ModList::get).thenReturn(mods);
    }

    @AfterEach
    void clearOverride() {
        MagicGridItemTransfer.setManaItemCapabilityForTest(null);
        RSIntegrationConfig.ENABLE_BOTANIA = previousBotaniaConfig;
        RSIntegrationConfig.ENABLE_ARS_NOUVEAU = previousArsConfig;
        modListLoading.close();
    }

    @Test
    void manaContainerIsRecognisedThroughTheItemStackCapability() throws Exception {
        ItemStack tablet = manaStack(new AtomicInteger(1_000));
        assertEquals(MagicResourceKind.MANA, MagicGridItemTransfer.kindOf(tablet));
        assertEquals(1_000, MagicGridItemTransfer.read(MagicResourceKind.MANA, tablet));
        assertEquals(MAX_MANA - 1_000, MagicGridItemTransfer.space(MagicResourceKind.MANA, tablet));
        assertTrue(MagicGridItemTransfer.accepts(MagicResourceKind.MANA, tablet));
    }

    @Test
    void plainItemsAreNotManaContainers() {
        assertNull(MagicGridItemTransfer.kindOf(new ItemStack(Items.STONE)));
        assertEquals(-1, MagicGridItemTransfer.read(MagicResourceKind.MANA, new ItemStack(Items.STONE)));
        assertEquals(0, MagicGridItemTransfer.space(MagicResourceKind.MANA, new ItemStack(Items.STONE)));
    }

    @Test
    void creativeContainersMapToTheirMagicKinds() {
        assertEquals(MagicResourceKind.SOURCE, MagicGridItemTransfer.creativeKind(
                new ResourceLocation("ars_nouveau", "creative_source_jar")));
        assertEquals(MagicResourceKind.MANA, MagicGridItemTransfer.creativeKind(
                new ResourceLocation("botania", "creative_pool")));
        assertNull(MagicGridItemTransfer.creativeKind(
                new ResourceLocation("ars_nouveau", "source_jar")));
        assertEquals(RSIntegrationConfig.DEFAULT_CREATIVE_MAGIC_DEPOSIT_AMOUNT,
                MagicGridItemTransfer.creativeDepositAmount());
    }

    @Test
    void creativeSourceJarDepositsConfiguredAmountWithoutBeingConsumed() {
        ItemStack sourceJar = new ItemStack(
                creativeItem(new ResourceLocation("ars_nouveau", "creative_source_jar")));
        assertEquals(MagicResourceKind.SOURCE, MagicGridItemTransfer.kindOf(sourceJar));
        ServerPlayer player = mock(ServerPlayer.class);
        MinecraftServer server = mock(MinecraftServer.class);
        Inventory inventory = mock(Inventory.class);
        player.containerMenu = mock(AbstractContainerMenu.class);
        when(player.getServer()).thenReturn(server);
        when(player.getInventory()).thenReturn(inventory);

        long amount = MagicGridItemTransfer.creativeDepositAmount();
        try (MockedStatic<MagicResourceEndpoint> endpoint = mockStatic(MagicResourceEndpoint.class)) {
            endpoint.when(() -> MagicResourceEndpoint.insert(
                            server, MagicResourceKind.SOURCE, amount, false))
                    .thenReturn(amount);
            endpoint.when(() -> MagicResourceEndpoint.insert(
                            server, MagicResourceKind.SOURCE, amount, true))
                    .thenReturn(amount);

            assertEquals(amount, MagicGridItemTransfer.deposit(
                    player, MagicResourceKind.SOURCE, sourceJar));
        }
        assertEquals(1, sourceJar.getCount());
    }

    @Test
    void writingTheSameBalanceDoesNotTouchTheContainer() throws Exception {
        AtomicInteger mana = new AtomicInteger(1_000);
        ItemStack tablet = manaStack(mana);
        assertEquals(0, MagicGridItemTransfer.write(MagicResourceKind.MANA, tablet, 1_000));
        assertEquals(1_000, mana.get());
    }

    @Test
    void writingManaUsesTheNativeDeltaAndReportsTheActualChange() throws Exception {
        AtomicInteger mana = new AtomicInteger(1_000);
        ItemStack tablet = manaStack(mana);

        assertEquals(250, MagicGridItemTransfer.write(MagicResourceKind.MANA, tablet, 1_250));
        assertEquals(1_250, mana.get());
        assertEquals(-1_250, MagicGridItemTransfer.write(MagicResourceKind.MANA, tablet, 0));
        assertEquals(0, mana.get());
    }

    @Test
    void writingManaReportsTheClampedNativeChange() throws Exception {
        AtomicInteger mana = new AtomicInteger(MAX_MANA - 100);
        ItemStack tablet = manaStack(mana);

        assertEquals(100, MagicGridItemTransfer.write(MagicResourceKind.MANA, tablet, MAX_MANA + 500));
        assertEquals(MAX_MANA, mana.get());
    }

    @Test
    void depositDebitsOnlyWhatTheStorageAccepts() throws Exception {
        AtomicInteger mana = new AtomicInteger(500);
        ItemStack tablet = manaStack(mana);
        ServerPlayer player = mock(ServerPlayer.class);
        MinecraftServer server = mock(MinecraftServer.class);
        Inventory inventory = mock(Inventory.class);
        player.containerMenu = mock(AbstractContainerMenu.class);
        when(player.getServer()).thenReturn(server);
        when(player.getInventory()).thenReturn(inventory);

        try (MockedStatic<MagicResourceEndpoint> endpoint = mockStatic(MagicResourceEndpoint.class)) {
            endpoint.when(() -> MagicResourceEndpoint.insert(server, MagicResourceKind.MANA, 500, false))
                    .thenReturn(200L);
            endpoint.when(() -> MagicResourceEndpoint.insert(server, MagicResourceKind.MANA, 200, true))
                    .thenReturn(200L);

            assertEquals(200, MagicGridItemTransfer.deposit(player, MagicResourceKind.MANA, tablet));
            assertEquals(300, mana.get());
            endpoint.verify(() -> MagicResourceEndpoint.insert(server, MagicResourceKind.MANA, 500, false));
            endpoint.verify(() -> MagicResourceEndpoint.insert(server, MagicResourceKind.MANA, 200, true));
        }
    }

    @Test
    void depositRestoresManaOverdrawnByTheNativeCapability() throws Exception {
        AtomicInteger mana = new AtomicInteger(500);
        ItemStack tablet = manaStack(mana, delta -> delta < 0 ? delta - 50 : delta);
        ServerPlayer player = mock(ServerPlayer.class);
        MinecraftServer server = mock(MinecraftServer.class);
        player.containerMenu = mock(AbstractContainerMenu.class);
        when(player.getServer()).thenReturn(server);
        when(player.getInventory()).thenReturn(mock(Inventory.class));

        try (MockedStatic<MagicResourceEndpoint> endpoint = mockStatic(MagicResourceEndpoint.class)) {
            endpoint.when(() -> MagicResourceEndpoint.insert(server, MagicResourceKind.MANA, 500, false))
                    .thenReturn(200L);
            endpoint.when(() -> MagicResourceEndpoint.insert(server, MagicResourceKind.MANA, 200, true))
                    .thenReturn(200L);

            assertEquals(200, MagicGridItemTransfer.deposit(player, MagicResourceKind.MANA, tablet));
            assertEquals(300, mana.get(), "只应扣掉库存确认接收的 200 mana");
            endpoint.verify(() -> MagicResourceEndpoint.insert(server, MagicResourceKind.MANA, 200, true));
        }
    }

    /** 造一个挂了魔力能力的物品堆：能力常量用代理顶上，Botania 缺席也能走通流程。 */
    private ItemStack manaStack(AtomicInteger mana) throws Exception {
        return manaStack(mana, IntUnaryOperator.identity());
    }

    private ItemStack manaStack(AtomicInteger mana, IntUnaryOperator deltaTransform) throws Exception {
        Class<?> manaItem = Class.forName(MANA_ITEM);
        Capability<?> capability = mock(Capability.class);
        Object container = Proxy.newProxyInstance(manaItem.getClassLoader(), new Class<?>[]{manaItem},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getMana" -> mana.get();
                    case "getMaxMana" -> MAX_MANA;
                    case "addMana" -> {
                        mana.updateAndGet(current -> Math.max(0, Math.min(MAX_MANA,
                                current + deltaTransform.applyAsInt((Integer) args[0]))));
                        yield null;
                    }
                    default -> null;
                });
        ItemStack stack = mock(ItemStack.class);
        when(stack.isEmpty()).thenReturn(false);
        when(stack.getCapability((Capability) capability, null))
                .thenReturn((LazyOptional) LazyOptional.of(() -> container));
        MagicGridItemTransfer.setManaItemCapabilityForTest(capability);

        assertTrue(MagicResourceKind.MANA.available());
        assertTrue(RSIntegrationConfig.ENABLE_BOTANIA == null
                || RSIntegrationConfig.ENABLE_BOTANIA.get());
        return stack;
    }

    @SuppressWarnings("unchecked")
    private static Item creativeItem(ResourceLocation id) {
        if (ForgeRegistries.ITEMS.containsKey(id)) return ForgeRegistries.ITEMS.getValue(id);
        ForgeRegistry<Item> registry = (ForgeRegistry<Item>) ForgeRegistries.ITEMS;
        boolean locked = registry.isLocked();
        registry.unfreeze();
        try {
            Method unfreeze = BuiltInRegistries.ITEM.getClass().getMethod("unfreeze");
            unfreeze.setAccessible(true);
            unfreeze.invoke(BuiltInRegistries.ITEM);
            Item item = new Item(new Item.Properties());
            registry.register(id, item);
            return item;
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("无法注册创造魔法测试物品", failure);
        } finally {
            BuiltInRegistries.ITEM.freeze();
            if (locked) registry.freeze();
        }
    }
}
