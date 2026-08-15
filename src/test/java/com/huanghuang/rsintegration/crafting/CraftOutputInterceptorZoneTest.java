package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CraftOutputInterceptorZoneTest extends BootstrapTest {

    @Test
    void collectorScanDetectsOnlyOverlappingCaptureZones() {
        CraftOutputInterceptor.CaptureHandle handle = CraftOutputInterceptor.arm(
                Level.OVERWORLD, new AABB(10, 64, 10, 12, 66, 12),
                new ItemStack(Items.DIAMOND));
        try {
            assertTrue(CraftOutputInterceptor.intersectsActiveZone(
                    Level.OVERWORLD, new AABB(11, 63, 11, 13, 65, 13)));
            assertFalse(CraftOutputInterceptor.intersectsActiveZone(
                    Level.OVERWORLD, new AABB(30, 64, 30, 31, 65, 31)));
            assertFalse(CraftOutputInterceptor.intersectsActiveZone(
                    Level.NETHER, new AABB(11, 63, 11, 13, 65, 13)));
        } finally {
            if (handle != null) handle.drainAndClose();
        }
    }
}
