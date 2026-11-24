package com.huanghuang.rsintegration.crafting.plan;

import com.huanghuang.rsintegration.crafting.planning.PlanningProgressTracker;
import com.huanghuang.rsintegration.crafting.planning.PlanningProgressSnapshot;
import com.huanghuang.rsintegration.crafting.planning.PlanningTasksScreen;
import com.huanghuang.rsintegration.client.recipe.JeiNetworkItemCache;
import com.huanghuang.rsintegration.storage.StorageBackendId;
import com.huanghuang.rsintegration.storage.StorageReference;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

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
    void serverSelectedBaseDoesNotChangeTheOriginalPreviewIdentity() {
        PlanBackgroundClient.startPreview(41L, RECIPE, null, null);
        PlanningProgressTracker.responseReceived(41L);
        PlanBackgroundClient.remember(41L,
                plan(new ItemStack(Items.IRON_SWORD), null, NETWORK));

        assertEquals(41L, PlanBackgroundClient.findExisting(RECIPE, null, null, NETWORK));
        assertEquals(0L, PlanBackgroundClient.findExisting(RECIPE,
                new ItemStack(Items.DIAMOND_SWORD), null, NETWORK));
        assertEquals(1, PlanningTasksScreen.taskCount());
    }

    @Test
    void partialRestocksKeepTheOriginalPlanIdentityUntilExecutionAcceptsIt() {
        PlanResponse independent = plan(null, null, NETWORK);
        PlanBackgroundClient.remember(1L, independent);
        PlanBackgroundClient.startPreview(42L, RECIPE, null, null);
        PlanningProgressTracker.responseReceived(42L);
        PlanBackgroundClient.remember(42L, plan(null, null, NETWORK));

        for (long request = 43L; request <= 45L; request++) {
            PlanBackgroundClient.replaceRevision(request - 1L, request);
            PlanningProgressTracker.start(request, RECIPE);
            assertEquals(request, PlanBackgroundClient.findPending(RECIPE, null, null, NETWORK));
            assertEquals(2, PlanningTasksScreen.taskCount());
            assertFalse(PlanningProgressTracker.acceptsResponse(request - 1L));

            PlanResponse refreshed = plan(null, new ItemStack(Items.DIAMOND), NETWORK);
            when(refreshed.success()).thenReturn(request == 45L);
            when(refreshed.repeatCount()).thenReturn(64);
            PlanningProgressTracker.responseReceived(request);
            PlanBackgroundClient.remember(request, refreshed);
            // 后台回包先登记、再捕获，不能在第二次保存时丢掉首次请求身份。
            PlanBackgroundClient.detach(request);
            assertTrue(PlanBackgroundClient.capture(request, refreshed));

            assertEquals(request, PlanBackgroundClient.findExisting(RECIPE, null, null, NETWORK));
            assertSame(refreshed, PlanBackgroundClient.completedPlans().get(request));
            assertEquals(64, PlanBackgroundClient.completedPlans().get(request).repeatCount());
            assertEquals(2, PlanningTasksScreen.taskCount());
        }

        try (var game = mockStatic(Minecraft.class)) {
            game.when(Minecraft::getInstance).thenReturn(mock(Minecraft.class));
            PlanBackgroundClient.accepted(45L, UUID.randomUUID());
        }
        assertEquals(1, PlanningTasksScreen.taskCount());
        assertSame(independent, PlanBackgroundClient.completedPlans().get(1L));
        assertEquals(1L, PlanBackgroundClient.findExisting(RECIPE, null, null, NETWORK));
    }

    @Test
    void originalOutputVariantSurvivesRevisionsWithoutMatchingOtherNbtOrNetworks() {
        ItemStack output = new ItemStack(Items.ENCHANTED_BOOK);
        output.getOrCreateTag().putInt("level", 2);
        ItemStack original = output.copy();
        PlanBackgroundClient.startPreview(46L, RECIPE, null, output);
        output.getOrCreateTag().putInt("level", 3);
        PlanningProgressTracker.responseReceived(46L);
        PlanBackgroundClient.remember(46L, plan(null, original, NETWORK));
        PlanBackgroundClient.replaceRevision(46L, 47L);
        PlanningProgressTracker.start(47L, RECIPE);
        assertEquals(47L, PlanBackgroundClient.findPending(RECIPE, null, original, NETWORK));
        assertEquals(0L, PlanBackgroundClient.findPending(RECIPE, null, output, NETWORK));
        PlanningProgressTracker.responseReceived(47L);
        PlanBackgroundClient.remember(47L, plan(null, original, NETWORK));

        assertEquals(47L, PlanBackgroundClient.findExisting(RECIPE, null, original, NETWORK));
        assertEquals(0L, PlanBackgroundClient.findExisting(RECIPE, null, output, NETWORK));
        assertEquals(0L, PlanBackgroundClient.findExisting(RECIPE, null, original,
                new StorageReference(NETWORK.backendId(), "second")));
        assertTrue(PlanBackgroundClient.dismiss(47L));
        assertEquals(0L, PlanBackgroundClient.findExisting(RECIPE, null, original, NETWORK));
    }

    @Test
    void requoteReplacesOnlyItsOriginalPreviewAfterAnotherRequestStarts() {
        PlanBackgroundClient.startPreview(100L, RECIPE, null, null);
        PlanningProgressTracker.responseReceived(100L);
        PlanBackgroundClient.remember(100L, plan(new ItemStack(Items.IRON_SWORD), null, NETWORK));
        PlanBackgroundClient.expectRequote(101L, 100L);
        assertNull(PlanningProgressTracker.get(101L));
        ResourceLocation other = new ResourceLocation("test", "other");
        PlanBackgroundClient.startPreview(102L, other, null, null);

        var refreshed = plan(null, null, NETWORK);
        when(refreshed.success()).thenReturn(true);
        when(refreshed.targetName()).thenReturn("target");
        when(refreshed.targetResult()).thenReturn(ItemStack.EMPTY);
        try (var game = mockStatic(Minecraft.class)) {
            var client = mock(Minecraft.class);
            game.when(Minecraft::getInstance).thenReturn(client);
            PlanResponseClientPacketHandler.handle(refreshed, 101L);

            assertFalse(PlanBackgroundClient.contains(100L));
            assertEquals(101L, PlanBackgroundClient.findExisting(RECIPE, null, null, NETWORK));
            assertEquals(102L, PlanBackgroundClient.findPending(other, null, null, null));
            assertTrue(PlanningProgressTracker.isSelected(102L));
            var retained = PlanBackgroundClient.completedPlans().get(101L);
            assertNotNull(retained);
            PlanResponseClientPacketHandler.handle(refreshed, 101L);
            assertSame(retained, PlanBackgroundClient.completedPlans().get(101L));
            verifyNoInteractions(client);
        }
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
