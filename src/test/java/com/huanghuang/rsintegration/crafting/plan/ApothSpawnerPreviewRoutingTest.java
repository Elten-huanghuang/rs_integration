package com.huanghuang.rsintegration.crafting.plan;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.crafting.tree.JeiSubtreeBuilder;
import com.huanghuang.rsintegration.crafting.planning.PlanningProgressTracker;
import com.huanghuang.rsintegration.mods.apotheosis.ApothSpawnerPlanTarget;
import com.huanghuang.rsintegration.mods.apotheosis.network.ApothSpawnerExecutePacket;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.fml.ModList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class ApothSpawnerPreviewRoutingTest extends BootstrapTest {
    @TempDir Path directory;

    @Test
    void planRefreshUsesTheSpawnerChannelAndAcceptsItsPreviewResponse() throws Exception {
        ResourceLocation dimension = new ResourceLocation("minecraft", "overworld");
        BlockPos pos = new BlockPos(12, 64, 34);
        PlanResponse plan = new PlanResponse(true, "spawner", new ItemStack(Items.SPAWNER),
                List.of(), Map.of(), List.of(), ApothSpawnerPlanTarget.ID.toString(), null,
                dimension.toString(), pos.getX(), pos.getY(), pos.getZ(), List.of(), 1,
                null, null, null, 0L, false, false, false, null, Set.of(), Map.of(), null, null);
        Minecraft game = mock(Minecraft.class);
        var font = Minecraft.class.getDeclaredField("font");
        font.setAccessible(true);
        font.set(game, mock(Font.class));
        RSIntegrationConfig.SERVER_SPEC.setConfig(CommentedConfig.inMemory());
        RSIntegrationConfig.CLIENT_SPEC.setConfig(CommentedConfig.inMemory());
        RSIntegrationConfig.COMMON_SPEC.setConfig(CommentedConfig.inMemory());
        PlanningProgressTracker.clear();
        try (var games = mockStatic(Minecraft.class);
             var mods = mockStatic(ModList.class);
             var paths = mockStatic(ClientPlanningPreferences.class);
             var enrichment = mockStatic(JeiSubtreeBuilder.class);
             var packets = mockStatic(ApothSpawnerExecutePacket.class)) {
            games.when(Minecraft::getInstance).thenReturn(game);
            mods.when(ModList::get).thenReturn(mock(ModList.class));
            paths.when(ClientPlanningPreferences::preferencesPath)
                    .thenReturn(directory.resolve("preferences.json"));
            CraftingPlanScreen screen = new CraftingPlanScreen(plan, 0L);
            var send = CraftingPlanScreen.class.getDeclaredMethod("sendCraftPacket",
                    ResourceLocation.class, boolean.class, Map.class, int.class, boolean.class);
            send.setAccessible(true);
            send.invoke(screen, ApothSpawnerPlanTarget.ID, true, Map.of(), 1, false);

            packets.verify(() -> ApothSpawnerExecutePacket.refreshPreview(dimension, pos));
            assertEquals(0L, screen.activeRequestId());
            assertTrue(PlanningProgressTracker.tasks().isEmpty(), "专用预览不能启动普通合成任务的等待条");
            var stateField = CraftingPlanScreen.class.getDeclaredField("responseRequests");
            stateField.setAccessible(true);
            var state = (PlanResponseRequestState) stateField.get(screen);
            assertTrue(state.pending());
            assertTrue(state.accept(0L, plan.recipeId()), "专用预览返回后必须解除计划页等待");
        } finally {
            PlanningProgressTracker.clear();
        }
    }
}
