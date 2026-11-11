package com.huanghuang.rsintegration.mods.vanilla.brewing;

import com.huanghuang.rsintegration.crafting.batch.IBatchDelegate.CraftPhase;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.mixin.minecraft.BrewingStandAccessor;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BrewingStandBlockEntity;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

class BrewingObservationTest extends BootstrapTest {
    @Test
    void skipsRepeatedBottleValidationButDetectsCompletionOnTheSameTick() throws Exception {
        var delegate = new BrewingStandBatchDelegate();
        var level = mock(ServerLevel.class);
        var stand = mock(BrewingStandBlockEntity.class, withSettings().extraInterfaces(BrewingStandAccessor.class));
        var clock = (BrewingStandAccessor) stand;
        set(delegate, "placed", true);
        set(delegate, "recipe", new VanillaBrewingRecipeDefinition(new ResourceLocation("test:brew"),
                new ItemStack(Items.APPLE), new ItemStack(Items.SUGAR), new ItemStack(Items.GOLDEN_APPLE)));
        when(stand.getItem(anyInt())).thenReturn(new ItemStack(Items.APPLE));
        when(clock.rsi$getBrewTime()).thenReturn(100);
        when(level.getGameTime()).thenReturn(10L);
        assertEquals(CraftPhase.WORKING, delegate.observeMachineCraft(level, stand).phase());
        int interval = RSIntegrationConfig.DEFAULT_PASSIVE_OBSERVATION_TICKS;
        for (int tick = 1; tick < interval; tick++) {
            when(level.getGameTime()).thenReturn(10L + tick);
            when(clock.rsi$getBrewTime()).thenReturn(100 - tick);
            assertEquals(tick, delegate.observeMachineCraft(level, stand).completedWork());
        }
        verify(stand, times(3)).getItem(anyInt());
        // 倒计时在下一次完整轮询前归零，必须当刻收集完成，不能再等间隔。
        when(clock.rsi$getBrewTime()).thenReturn(0);
        when(stand.getItem(anyInt())).thenReturn(new ItemStack(Items.GOLDEN_APPLE));
        assertEquals(CraftPhase.DONE, delegate.observeMachineCraft(level, stand).phase());
        verify(stand, times(6)).getItem(anyInt());
    }

    @Test
    void unsupportedAccessorRetainsFullObservationEveryTick() throws Exception {
        var delegate = new BrewingStandBatchDelegate();
        var level = mock(ServerLevel.class);
        var stand = mock(BrewingStandBlockEntity.class);
        set(delegate, "placed", true);
        set(delegate, "recipe", new VanillaBrewingRecipeDefinition(new ResourceLocation("test:brew"),
                new ItemStack(Items.APPLE), new ItemStack(Items.SUGAR), new ItemStack(Items.GOLDEN_APPLE)));
        when(stand.getItem(anyInt())).thenReturn(new ItemStack(Items.APPLE));
        delegate.observeMachineCraft(level, stand);
        when(stand.getItem(0)).thenReturn(ItemStack.EMPTY);
        assertEquals(CraftPhase.FAILED, delegate.observeMachineCraft(level, stand).phase());
    }

    private static void set(Object target, String field, Object value) throws Exception {
        Field member = target.getClass().getDeclaredField(field);
        member.setAccessible(true);
        member.set(target, value);
    }
}
