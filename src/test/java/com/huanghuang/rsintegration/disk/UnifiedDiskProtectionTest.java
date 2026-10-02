package com.huanghuang.rsintegration.disk;

import com.huanghuang.rsintegration.disk.core.UnifiedDiskCore;
import com.huanghuang.rsintegration.disk.rs.UnifiedDiskItem;
import com.huanghuang.rsintegration.disk.rs.UnifiedDiskManager;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.core.Holder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageSources;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UnifiedDiskProtectionTest extends BootstrapTest {
    private final UnifiedDiskItem item = mock(UnifiedDiskItem.class, CALLS_REAL_METHODS);

    private ItemStack disk() {
        ItemStack stack = spy(new ItemStack(Items.STONE));
        doReturn(item).when(stack).getItem();
        return stack;
    }

    private Level level() {
        Level level = mock(Level.class);
        when(level.dimension()).thenReturn(Level.OVERWORLD);
        when(level.getMinBuildHeight()).thenReturn(-64);
        when(level.getMaxBuildHeight()).thenReturn(320);
        return level;
    }

    private ItemEntity entity(Level level, ItemStack stack, double y) {
        // 运行原版 age、拾取延迟和受伤逻辑；坐标及世界交互用 mock，测试不启动服务器。
        ItemEntity entity = mock(ItemEntity.class, CALLS_REAL_METHODS);
        doReturn(level).when(entity).level(); doReturn(stack).when(entity).getItem();
        doReturn(8.0).when(entity).getX(); doReturn(y).when(entity).getY(); doReturn(16.0).when(entity).getZ();
        doNothing().when(entity).setPos(anyDouble(), anyDouble(), anyDouble());
        doNothing().when(entity).setDeltaMovement(any(Vec3.class));
        doNothing().when(entity).setNoGravity(anyBoolean());
        return entity;
    }

    @ParameterizedTest @ValueSource(strings = {"explosion", "lightningBolt", "inFire", "lava", "cactus", "arrow", "wither", "fallingBlock"})
    void vanillaItemDamageRejectsAllSourcesWithoutRemovingDisk(String name) {
        ItemStack stack = disk();
        UUID id = UUID.randomUUID(), world = UUID.randomUUID(); item.setIdentity(stack, id, world);
        CompoundTag original = stack.getTag().copy();
        ItemEntity entity = entity(level(), stack, 80);
        doReturn(false).when(entity).isInvulnerableTo(any(DamageSource.class));
        DamageSource source = new DamageSource(Holder.direct(new DamageType(name, 0)));
        assertFalse(entity.hurt(source, Float.MAX_VALUE));
        verify(entity, never()).discard();
        assertEquals(original, stack.getTag()); assertEquals(1, stack.getCount());
    }

    @Test void oldDroppedDiskGetsUnlimitedLifetimeWithoutChangingPickupDelayOrIdentity() {
        ItemStack stack = disk();
        item.setIdentity(stack, UUID.randomUUID(), UUID.randomUUID());
        CompoundTag original = stack.getTag().copy();
        ItemEntity entity = entity(level(), stack, 80);
        CompoundTag old = new ItemStack(Items.STONE).save(new CompoundTag());
        CompoundTag data = new CompoundTag(); data.put("Item", old); data.putShort("Age", (short) 5999);
        data.putShort("PickupDelay", (short) 7); data.putInt("Lifespan", 6000);
        doNothing().when(entity).setItem(any(ItemStack.class));
        entity.readAdditionalSaveData(data);
        assertEquals(5999, entity.getAge());
        assertFalse(item.onEntityItemUpdate(stack, entity));
        assertEquals(-32768, entity.getAge()); assertTrue(entity.hasPickUpDelay());
        assertEquals(Integer.MAX_VALUE, entity.lifespan);
        verify(entity, never()).setNoPickUpDelay(); verify(entity, never()).setNeverPickUp();
        assertEquals(original, stack.getTag());
    }

    @Test void actualVanillaLightningHitKeepsDiskAndNextUpdateClearsFire() {
        Level level = level(); ItemStack stack = disk();
        ItemEntity entity = entity(level, stack, 80);
        DamageSources sources = mock(DamageSources.class);
        when(level.damageSources()).thenReturn(sources);
        DamageSource lightning = new DamageSource(Holder.direct(new DamageType("lightningBolt", 0)));
        when(sources.lightningBolt()).thenReturn(lightning);
        LightningBolt bolt = mock(LightningBolt.class); when(bolt.getDamage()).thenReturn(5.0F);
        doReturn(false).when(entity).isInvulnerableTo(any(DamageSource.class));
        entity.thunderHit(mock(ServerLevel.class), bolt);
        verify(entity).hurt(lightning, 5.0F); verify(entity, never()).discard();
        item.onEntityItemUpdate(stack, entity);
        assertEquals(0, entity.getRemainingFireTicks()); assertEquals(1, stack.getCount());
    }

    @Test void voidRescueReturnsSameEntityToSavedLocationAndFloatsIt() {
        Level level = level(); ItemStack stack = disk();
        ItemEntity entity = entity(level, stack, 80);
        entity.setNoPickUpDelay();
        item.onEntityItemUpdate(stack, entity);
        CompoundTag saved = entity.getPersistentData().copy();
        // 模拟掉落物实体存档恢复后坠入虚空。
        ItemEntity restored = entity(level, stack, -150);
        doReturn(saved).when(restored).getPersistentData();
        assertFalse(item.onEntityItemUpdate(stack, restored));
        verify(restored).setPos(8, 80, 16); verify(restored).setNoGravity(true);
        verify(restored).setDeltaMovement(Vec3.ZERO); verify(restored, never()).discard();
        verify(level, never()).addFreshEntity(any());
        assertFalse(restored.hasPickUpDelay());
    }

    @Test void missingOrOtherDimensionAnchorFallsBackAboveCurrentWorldBottom() {
        for (boolean previousDimension : new boolean[] {false, true}) {
            Level level = level(); ItemEntity entity = entity(level, disk(), -150);
            if (previousDimension) {
                doReturn(80.0).when(entity).getY(); UnifiedDiskDropProtection.update(entity);
                doReturn(Level.END).when(level).dimension(); doReturn(-150.0).when(entity).getY();
            }
            UnifiedDiskDropProtection.update(entity);
            verify(entity).setPos(8, -62, 16); verify(entity).setNoGravity(true);
        }
    }

    @Test void fakeDisplayAndClientEntitiesRetainNormalLifecycle() throws Exception {
        Level level = level(); ItemEntity entity = entity(level, disk(), -150);
        entity.makeFakeItem();
        item.onEntityItemUpdate(entity.getItem(), entity);
        verify(entity, never()).setUnlimitedLifetime(); verify(entity, never()).setPos(anyDouble(), anyDouble(), anyDouble());
        Level client = level();
        var clientFlag = Level.class.getDeclaredField("isClientSide");
        clientFlag.setAccessible(true); clientFlag.set(client, true);
        ItemEntity clientEntity = entity(client, disk(), -150);
        item.onEntityItemUpdate(clientEntity.getItem(), clientEntity);
        verify(clientEntity, never()).setUnlimitedLifetime(); verify(clientEntity, never()).setNoGravity(anyBoolean());
    }

    @Test void recoveryRestoresOriginalIdentityAndDoesNotCreateOrResetInventory() throws Exception {
        UnifiedDiskCore core = UnifiedDiskCoreTest.core(8);
        var key = UnifiedDiskCoreTest.variant(1); core.insert(key, 123456, true);
        UnifiedDiskManager manager = mock(UnifiedDiskManager.class, CALLS_REAL_METHODS);
        UnifiedDiskManager.Entry entry = mock(UnifiedDiskManager.Entry.class); entry.core = core;
        doReturn(true).when(manager).enabled(); doReturn(core.worldId).when(manager).worldId();
        doReturn(entry).when(manager).entry(core.diskId);
        ItemStack replacement = disk(); manager.restoreIdentity(core.diskId, replacement);
        assertEquals(core.diskId, item.getId(replacement)); assertEquals(core.worldId, item.worldId(replacement));
        assertEquals(1, replacement.getTag().getInt("Format")); assertEquals(123456, core.items.amount(key));
        verify(manager, never()).create(any()); verify(manager, never()).flush();
        assertThrows(IOException.class, () -> manager.restoreIdentity(core.diskId, replacement));
    }

    @Test void recoveryRefusesMissingWrongWorldAndDisabledDisksWithoutWritingIdentity() {
        UnifiedDiskCore core = UnifiedDiskCoreTest.core(8);
        UnifiedDiskManager manager = mock(UnifiedDiskManager.class, CALLS_REAL_METHODS);
        UnifiedDiskManager.Entry entry = mock(UnifiedDiskManager.Entry.class);
        doReturn(true).when(manager).enabled(); doReturn(core.worldId).when(manager).worldId();
        doReturn(entry).when(manager).entry(core.diskId);
        ItemStack replacement = disk();
        assertThrows(IOException.class, () -> manager.restoreIdentity(core.diskId, replacement));
        entry.core = core; doReturn(UUID.randomUUID()).when(manager).worldId();
        assertThrows(IOException.class, () -> manager.restoreIdentity(core.diskId, replacement));
        doReturn(core.worldId).when(manager).worldId(); doReturn(false).when(manager).enabled();
        assertThrows(IOException.class, () -> manager.restoreIdentity(core.diskId, replacement));
        assertFalse(replacement.hasTag());
    }
}
