package com.huanghuang.rsintegration.crafting.plan;

import com.huanghuang.rsintegration.crafting.planning.PlanningProgressTracker;
import com.huanghuang.rsintegration.crafting.planning.PlanningProgressSnapshot;
import com.huanghuang.rsintegration.crafting.planning.PlanningTasksScreen;
import com.huanghuang.rsintegration.client.recipe.JeiNetworkItemCache;
import com.huanghuang.rsintegration.storage.StorageBackendId;
import com.huanghuang.rsintegration.storage.StorageReference;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlanBackgroundReuseTest extends BootstrapTest {
    private static final ResourceLocation RECIPE = new ResourceLocation("test", "same_recipe");
    private static final StorageReference NETWORK = new StorageReference(new StorageBackendId("rs"), "first");

    @BeforeEach @AfterEach
    void clear() {
        PlanBackgroundClient.clear();
        PlanningProgressTracker.clear();
        JeiNetworkItemCache.INSTANCE.clear();
    }

    @Test
    void repeatedPlusSelectsLatestExistingPlanAndKeepsItsQuantityAndListEntry() {
        PlanResponse first = plan(null, null, NETWORK);
        PlanResponse edited = plan(null, null, NETWORK);
        when(edited.repeatCount()).thenReturn(64);
        PlanBackgroundClient.remember(10L, first);
        PlanBackgroundClient.remember(20L, edited);
        assertEquals(20L, PlanBackgroundClient.findExisting(RECIPE, null, null, NETWORK));
        assertSame(edited, PlanBackgroundClient.completedPlans().get(20L));
        assertEquals(64, edited.repeatCount());
        assertEquals(2, PlanningTasksScreen.taskCount());

        // 确认或删除后不再复用该条目，允许再次建立新计划。
        assertTrue(PlanBackgroundClient.dismiss(20L));
        assertEquals(10L, PlanBackgroundClient.findExisting(RECIPE, null, null, NETWORK));
        assertTrue(PlanBackgroundClient.dismiss(10L));
        assertEquals(0L, PlanBackgroundClient.findExisting(RECIPE, null, null, NETWORK));
    }

    @Test
    void sameRecipeWithDifferentBaseOrOutputNbtOrNetworkCannotReuse() {
        ItemStack base = new ItemStack(Items.IRON_SWORD);
        base.getOrCreateTag().putInt("Damage", 7);
        ItemStack output = new ItemStack(Items.ENCHANTED_BOOK);
        output.getOrCreateTag().putInt("level", 2);
        PlanBackgroundClient.remember(30L, plan(base, output, NETWORK));
        assertEquals(30L, PlanBackgroundClient.findExisting(RECIPE, base.copy(), output.copy(), NETWORK));
        ItemStack anotherOutput = output.copy();
        anotherOutput.getOrCreateTag().putInt("level", 3);
        ItemStack anotherBase = base.copy();
        anotherBase.getOrCreateTag().putInt("Damage", 8);
        assertEquals(0L, PlanBackgroundClient.findExisting(RECIPE, base, anotherOutput, NETWORK));
        assertEquals(0L, PlanBackgroundClient.findExisting(RECIPE, anotherBase, output, NETWORK));
        assertEquals(0L, PlanBackgroundClient.findExisting(RECIPE, null, output, NETWORK));
        assertEquals(0L, PlanBackgroundClient.findExisting(RECIPE, base, output,
                new StorageReference(NETWORK.backendId(), "second")));
        assertEquals(0L, PlanBackgroundClient.findExisting(new ResourceLocation("test", "other"), base, output, NETWORK));
    }

    @Test
    void missingMaterialsPlanCanBeReopenedAndEmptyStacksMatchNull() {
        PlanResponse missing = plan(ItemStack.EMPTY, ItemStack.EMPTY, NETWORK);
        when(missing.success()).thenReturn(false);
        PlanBackgroundClient.remember(40L, missing);
        assertEquals(40L, PlanBackgroundClient.findExisting(RECIPE, null, null, NETWORK));
        assertEquals(40L, PlanBackgroundClient.findExisting(RECIPE, null, null, null));
        assertEquals(1, PlanningTasksScreen.taskCount());
    }

    @Test
    void repeatedPlusFindsTheRunningRequestWithoutMergingOtherOutputVariants() {
        ItemStack output = new ItemStack(Items.ENCHANTED_BOOK);
        output.getOrCreateTag().putInt("level", 2);
        PlanBackgroundClient.startPreview(50L, RECIPE, null, output);
        ItemStack original = output.copy();
        output.getOrCreateTag().putInt("level", 3);
        assertEquals(50L, PlanBackgroundClient.findPending(RECIPE, null, original, null));
        assertEquals(0L, PlanBackgroundClient.findPending(RECIPE, null, output, null));
        assertEquals(1, PlanningTasksScreen.taskCount());

        // 成功进度可能先于分页结果到达，此时继续复用原任务。
        PlanningProgressTracker.update(new PlanningProgressSnapshot(50L, 1L, RECIPE,
                PlanningProgressSnapshot.State.SUCCEEDED, PlanningProgressSnapshot.Phase.COMPLETE,
                100L, Component.empty()));
        assertEquals(50L, PlanBackgroundClient.findPending(RECIPE, null, original, null));
        PlanningProgressTracker.responseReceived(50L);
        PlanBackgroundClient.remember(50L, plan(null, original, null));
        assertEquals(0L, PlanBackgroundClient.findPending(RECIPE, null, original, null));
        assertEquals(50L, PlanBackgroundClient.findExisting(RECIPE, null, original, null));
        assertEquals(1, PlanningTasksScreen.taskCount());
    }

    @Test
    void cancelledOrFailedRequestCanBeRetried() {
        PlanBackgroundClient.startPreview(60L, RECIPE, null, null);
        PlanningProgressTracker.cancel(60L);
        assertEquals(0L, PlanBackgroundClient.findPending(RECIPE, null, null, null));
        PlanBackgroundClient.startPreview(61L, RECIPE, null, null);
        PlanningProgressTracker.update(new PlanningProgressSnapshot(61L, 1L, RECIPE,
                PlanningProgressSnapshot.State.FAILED, PlanningProgressSnapshot.Phase.SEARCH,
                100L, Component.empty()));
        assertEquals(0L, PlanBackgroundClient.findPending(RECIPE, null, null, null));
        PlanBackgroundClient.startPreview(62L, RECIPE, null, null);
        assertEquals(62L, PlanBackgroundClient.findPending(RECIPE, null, null, null));
    }

    private static PlanResponse plan(ItemStack base, ItemStack output, StorageReference network) {
        PlanResponse plan = mock(PlanResponse.class);
        when(plan.recipeId()).thenReturn(RECIPE.toString());
        when(plan.baseItem()).thenReturn(base);
        when(plan.clickedOutput()).thenReturn(output);
        when(plan.storageReference()).thenReturn(network);
        return plan;
    }
}
