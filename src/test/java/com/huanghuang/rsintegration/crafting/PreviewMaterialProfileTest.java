package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.world.item.Items;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.Ingredient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PreviewMaterialProfileTest extends BootstrapTest {
    @Test
    void chargesOnlyActiveTimeToTheInnermostMaterial() {
        var clock = new AtomicLong();
        var profile = new PreviewMaterialProfile(clock::get);
        profile.startSlice();
        var root = profile.enter(Ingredient.of(Items.IRON_INGOT));
        profile.scanned();
        profile.scored();
        profile.stage(PreviewMaterialProfile.Stage.FIND);
        clock.addAndGet(2_000_000);
        var parent = profile.enter(Ingredient.of(Items.GOLD_INGOT));
        profile.attempted();
        profile.rolledBack(ResourceLocation.parse("test:gold"), "no-net-gain");
        profile.stage(PreviewMaterialProfile.Stage.LIVE_STOCK);
        clock.addAndGet(3_000_000);
        profile.endSlice();
        clock.addAndGet(999_000_000);
        profile.startSlice();
        clock.addAndGet(1_000_000);
        profile.switchTo(parent);
        profile.fallback();
        clock.addAndGet(1_000_000);
        profile.switchTo(root);
        profile.endSlice();
        assertEquals(List.of(
                "minecraft:gold_ingot{selfMs=4.00,calls=1,scanned=0,scored=0,tried=1,rollback=1,fallback=0,stageMs=[LIVE_STOCK:4.00],failed=[test:gold/no-net-gain=1]}",
                "minecraft:iron_ingot{selfMs=3.00,calls=1,scanned=1,scored=1,tried=0,rollback=0,fallback=1,stageMs=[FIND:3.00],failed=[]}"
        ), profile.hottest());
        assertTrue(new PreviewMaterialProfile(clock::get).hottest().isEmpty());
    }

    @Test
    void recursiveAttemptsForTheSameMaterialKeepSeparateCauses() {
        var profile = new PreviewMaterialProfile(() -> 0L);
        var material = Ingredient.of(Items.EMERALD);
        profile.enter(material);
        profile.attempted();
        profile.failedSlot(3, Ingredient.of(Items.EMERALD_BLOCK));
        var parent = profile.enter(material);
        profile.attempted();
        profile.rolledBack(ResourceLocation.parse("test:child"), "depth-limit");
        profile.switchTo(parent);
        profile.rolledBack(ResourceLocation.parse("test:parent"), "craft-failed");
        profile.attempted();
        profile.rolledBack(ResourceLocation.parse("test:parent"), "output-not-allocatable");
        String report = profile.hottest().get(0);
        assertTrue(report.contains("test:parent/input[3]:minecraft:emerald_block=1"), report);
        assertTrue(report.contains("test:child/depth-limit=1"), report);
        assertTrue(report.contains("test:parent/output-not-allocatable=1"), report);
    }
}
