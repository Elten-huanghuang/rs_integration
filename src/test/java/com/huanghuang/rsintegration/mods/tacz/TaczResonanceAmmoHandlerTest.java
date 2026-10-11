package com.huanghuang.rsintegration.mods.tacz;

import com.huanghuang.rsintegration.resonance.api.ResonanceStorageView;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.resonance.bridge.ClientDiskData;
import com.huanghuang.rsintegration.resonance.bridge.ResonanceInventoryBridge;
import com.electronwill.nightconfig.core.CommentedConfig;
import com.huanghuang.rsintegration.resonance.disk.ResonanceDiskWrapper;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import com.refinedmods.refinedstorage.api.IRSAPI;
import com.refinedmods.refinedstorage.apiimpl.API;
import com.refinedmods.refinedstorage.apiimpl.storage.disk.ItemStorageDisk;
import com.refinedmods.refinedstorage.apiimpl.util.Comparer;
import com.tacz.guns.api.item.gun.AbstractGunItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.CapabilityManager;
import net.minecraftforge.common.capabilities.CapabilityToken;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemStackHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.UUID;

import static com.huanghuang.rsintegration.testutil.TaczAmmoTestItems.ammo;
import static com.huanghuang.rsintegration.testutil.TaczAmmoTestItems.gun;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TaczResonanceAmmoHandlerTest extends BootstrapTest {
    private MockedStatic<API> apiMock;
    private ResonanceDiskWrapper disk;

    @BeforeAll
    static void initializeCapabilityBoundary() throws ClassNotFoundException {
        // 单测没有 Forge 转换器，仅替代能力令牌注册；后续盘内读取与扣弹使用真实实现。
        try (MockedStatic<CapabilityManager> manager = mockStatic(CapabilityManager.class)) {
            manager.when(() -> CapabilityManager.get(any(CapabilityToken.class)))
                    .thenAnswer(call -> mock(Capability.class));
            Class.forName(ForgeCapabilities.class.getName());
        }
    }

    @BeforeEach
    void setup() {
        IRSAPI api = mock(IRSAPI.class);
        when(api.getComparer()).thenReturn(new Comparer());
        apiMock = mockStatic(API.class);
        apiMock.when(API::instance).thenReturn(api);
        disk = spy(new ResonanceDiskWrapper(new ItemStorageDisk(null, 2304, UUID.randomUUID())));
        CommentedConfig config = CommentedConfig.inMemory();
        RSIntegrationConfig.COMMON_SPEC.correct(config);
        RSIntegrationConfig.COMMON_SPEC.setConfig(config);
    }

    @AfterEach
    void closeApi() {
        apiMock.close();
        RSIntegrationConfig.COMMON_SPEC.setConfig(null);
        ClientDiskData.clear();
    }

    @Test
    void nativeTaczExtractionUsesInventoryFirstAndOnlyConsumesMatchingDiskAmmo() {
        ItemStackHandler inventory = new ItemStackHandler(2);
        inventory.setStackInSlot(0, ammo("tacz:9mm", 5));
        inventory.setStackInSlot(1, new ItemStack(Items.DIAMOND, 3));
        disk.insertView(4, ammo("tacz:9mm", 12), 12, false);
        disk.insertView(5, ammo("tacz:556x45", 20), 20, false);
        disk.insertView(6, new ItemStack(Items.DIAMOND), 1, false);
        TaczResonanceAmmoHandler handler = new TaczResonanceAmmoHandler(inventory, List.of(disk));
        AbstractGunItem nativeGun = mock(AbstractGunItem.class, CALLS_REAL_METHODS);

        assertEquals(15, nativeGun.findAndExtractInventoryAmmos(handler, gun("tacz:9mm"), 15));

        assertTrue(inventory.getStackInSlot(0).isEmpty());
        assertEquals(3, inventory.getStackInSlot(1).getCount());
        assertEquals(2, disk.countItems(stack -> stack.hasTag()
                && "tacz:9mm".equals(stack.getTag().getString("AmmoId"))));
        assertEquals(20, disk.countItems(stack -> stack.hasTag()
                && "tacz:556x45".equals(stack.getTag().getString("AmmoId"))));
        assertEquals(23, disk.getStored());
        assertEquals(4, handler.getSlots());
    }

    @Test
    void nativeReloadSupportsPartialAmmoAcrossSeveralSlotsAndBackends() {
        disk.insertView(2, ammo("tacz:9mm", 4), 4, false);
        disk.insertView(3, ammo("tacz:9mm", 6), 6, false);
        ResonanceStorageView secondBackend = mock(ResonanceStorageView.class);
        ItemStack additional = ammo("tacz:9mm", 3);
        when(secondBackend.storedStacks()).thenReturn(List.of(new ResonanceStorageView.StoredStack(8, additional)));
        when(secondBackend.extractExactView(eq(8), any(), eq(3), eq(false))).thenReturn(additional);
        TaczResonanceAmmoHandler handler = new TaczResonanceAmmoHandler(
                new ItemStackHandler(0), List.of(disk, secondBackend));
        AbstractGunItem nativeGun = mock(AbstractGunItem.class, CALLS_REAL_METHODS);

        assertEquals(13, nativeGun.findAndExtractInventoryAmmos(handler, gun("tacz:9mm"), 30));
        assertEquals(0, disk.getStored());
        assertEquals(0, nativeGun.findAndExtractInventoryAmmos(handler, gun("tacz:9mm"), 30));
        verify(secondBackend, times(1)).extractExactView(eq(8), any(), eq(3), eq(false));
    }

    @Test
    void simulatedExtractionAndDisplayCopiesNeverChangeTheDisk() {
        disk.insertView(2, ammo("tacz:9mm", 10), 10, false);
        long revision = disk.contentRevision();
        TaczResonanceAmmoHandler handler = new TaczResonanceAmmoHandler(new ItemStackHandler(0), List.of(disk));

        handler.getStackInSlot(0).setCount(0);
        assertEquals(7, handler.extractItem(0, 7, true).getCount());
        assertEquals(10, handler.getStackInSlot(0).getCount());
        assertEquals(10, disk.getStored());
        assertEquals(revision, disk.contentRevision());
        assertEquals(10, handler.extractItem(0, 30, false).getCount());
        assertTrue(handler.extractItem(0, 1, false).isEmpty());
        assertEquals(0, disk.getStored());
    }

    @Test
    void rejectedExtractionCannotProduceFreeAmmo() {
        disk.insertView(2, ammo("tacz:9mm", 10), 10, false);
        TaczResonanceAmmoHandler handler = new TaczResonanceAmmoHandler(new ItemStackHandler(0), List.of(disk));
        doReturn(ItemStack.EMPTY).when(disk).extractExactView(anyInt(), any(), anyInt(), eq(false));
        AbstractGunItem nativeGun = mock(AbstractGunItem.class, CALLS_REAL_METHODS);

        assertEquals(0, nativeGun.findAndExtractInventoryAmmos(handler, gun("tacz:9mm"), 7));
        assertEquals(10, handler.getStackInSlot(0).getCount());
        assertEquals(10, disk.getStored());
    }

    @Test
    void clientPreflightSeesServerAmmoWithoutAllowingClientExtraction() {
        Player localPlayer = mock(Player.class);
        when(localPlayer.isLocalPlayer()).thenReturn(true);
        ClientDiskData.apply(0, 0, 0, 1, List.of(ammo("tacz:9mm", 10)));

        IItemHandler handler = TaczResonanceAmmoHandler.includeResonanceAmmo(
                localPlayer, ForgeCapabilities.ITEM_HANDLER, LazyOptional.empty())
                .orElseThrow(IllegalStateException::new);

        assertEquals(1, handler.getSlots());
        assertEquals("tacz:9mm", handler.getStackInSlot(0).getTag().getString("AmmoId"));
        assertEquals(10, handler.getStackInSlot(0).getCount());
        assertTrue(handler.extractItem(0, 10, false).isEmpty());
        assertEquals(10, ClientDiskData.getTaczAmmo().get(0).getCount());
    }

    @Test
    void serverOnlyAddsAuthorizedViewsAndRespectsCompatibilityToggle() {
        ServerPlayer player = mock(ServerPlayer.class);
        LazyOptional<IItemHandler> original = LazyOptional.of(() -> new ItemStackHandler(1));
        disk.insertView(2, ammo("tacz:9mm", 10), 10, false);
        try (MockedStatic<ResonanceInventoryBridge> bridge = mockStatic(ResonanceInventoryBridge.class)) {
            bridge.when(() -> ResonanceInventoryBridge.getViews(player)).thenReturn(List.of(disk));
            assertEquals(2, TaczResonanceAmmoHandler.includeResonanceAmmo(
                    player, ForgeCapabilities.ITEM_HANDLER, original)
                    .orElseThrow(IllegalStateException::new).getSlots());
            RSIntegrationConfig.ENABLE_TACZ.set(false);
            assertSame(original, TaczResonanceAmmoHandler.includeResonanceAmmo(
                    player, ForgeCapabilities.ITEM_HANDLER, original));
            RSIntegrationConfig.ENABLE_TACZ.set(true);
            bridge.when(() -> ResonanceInventoryBridge.getViews(player)).thenReturn(List.of());
            assertSame(original, TaczResonanceAmmoHandler.includeResonanceAmmo(
                    player, ForgeCapabilities.ITEM_HANDLER, original));
        }
    }
}
