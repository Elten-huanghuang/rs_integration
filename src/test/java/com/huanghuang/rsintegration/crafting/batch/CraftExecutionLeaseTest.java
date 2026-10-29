package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.crafting.graph.CraftPlanGraph;
import com.huanghuang.rsintegration.crafting.plan.PlanResponse;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph;
import com.huanghuang.rsintegration.crafting.planning.PlanCache;
import com.huanghuang.rsintegration.crafting.planning.PlanningSnapshot;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CraftExecutionLeaseTest extends BootstrapTest {
    private final UUID playerId = UUID.randomUUID();
    private final ResourceLocation recipeId = new ResourceLocation("aetherworks", "metal_former/glass_aetherium");
    private final ModType type = metalFormerType();

    private static ModType metalFormerType() {
        ModType type = mock(ModType.class);
        when(type.id()).thenReturn("aetherworks_metal_former");
        return type;
    }

    @Test
    void firstSingleOrderReusesThePreviewWhenConfirmationAddsTheTarget() {
        ItemStack output = new ItemStack(Items.GLASS);
        PlanCache cache = new PlanCache(100);
        PlanCache.Key previewKey = CraftPlanCacheKeys.key(
                playerId, recipeId, Map.of(), 1, null, type);
        CraftPlanGraph graph = new CraftPlanGraph(CraftPlanGraph.CURRENT_VERSION,
                List.of(), List.of(), List.of(), List.of(), List.of());
        PlanCache.Entry entry = entry(output, null, Map.of());
        cache.put(previewKey, entry.plan(), entry.snapshot(), null, graph, 1);

        // 首次预览不带点击产物，确认界面会使用 targetResult 补全该参数。
        PlanCache.Key confirmationKey = CraftPlanCacheKeys.key(
                playerId, recipeId, Map.of(), 1, output, type);
        assertNull(cache.peekForExecution(confirmationKey));
        PlanCache.Entry latest = cache.peekLatestForExecution(playerId);
        assertTrue(matches(latest, output));
        assertSame(graph, latest.resolvedGraph());
        assertTrue(cache.consumeLatestForExecution(playerId, latest));
        assertNull(cache.peekLatestForExecution(playerId));
    }

    @Test
    void emptyForcedSelectionsMatchEvenWhenClickedOutputWasProvided() {
        ItemStack output = new ItemStack(Items.GLASS);
        assertTrue(matches(entry(output, output, Map.of()), output));
    }

    @Test
    void missingClickedOutputStillRequiresTheExactResultVariant() {
        ItemStack output = new ItemStack(Items.GLASS);
        output.getOrCreateTag().putString("variant", "preview");
        PlanCache.Entry entry = entry(output, null, Map.of());

        assertTrue(matches(entry, output.copyWithCount(64)));
        assertFalse(matches(entry, new ItemStack(Items.GLASS)));
        assertFalse(matches(entry, new ItemStack(Items.DIAMOND)));
    }

    @Test
    void explicitClickedVariantTakesPrecedenceOverTheRecipeResult() {
        ItemStack clicked = new ItemStack(Items.ENCHANTED_BOOK);
        clicked.getOrCreateTag().putInt("level", 3);
        PlanCache.Entry entry = entry(new ItemStack(Items.ENCHANTED_BOOK), clicked, Map.of());

        assertTrue(matches(entry, clicked.copy()));
        assertFalse(matches(entry, new ItemStack(Items.ENCHANTED_BOOK)));
    }

    @Test
    void differentOrdersCannotReuseTheDisplayedLease() {
        ItemStack output = new ItemStack(Items.GLASS);
        PlanCache.Entry entry = entry(output, null, Map.of());
        ServerPlayer player = player();

        assertFalse(GenericCraftPacket.matchesExecutionLease(
                entry, player, recipeId, Map.of(), 2, output, type));
        assertFalse(GenericCraftPacket.matchesExecutionLease(
                entry, player, recipeId, Map.of(), 1, output, ModType.GENERIC));
        assertFalse(GenericCraftPacket.matchesExecutionLease(
                entry, player, new ResourceLocation("test", "other"), Map.of(), 1, output, type));
        when(player.getUUID()).thenReturn(UUID.randomUUID());
        assertFalse(GenericCraftPacket.matchesExecutionLease(
                entry, player, recipeId, Map.of(), 1, output, type));
    }

    @Test
    void removedOrInvalidForcedSelectionsDoNotMatchThePreview() {
        ItemStack output = new ItemStack(Items.GLASS);
        PlanCache.Entry forced = entry(output, null, Map.of(
                new ResourceLocation("minecraft", "glass"), new ResourceLocation("test", "chosen")));
        assertFalse(matches(forced, output));
        assertFalse(GenericCraftPacket.matchesExecutionLease(
                entry(output, null, Map.of()), player(), recipeId,
                Map.of("invalid key", "test:chosen"), 1, output, type));
    }

    private boolean matches(PlanCache.Entry entry, ItemStack target) {
        return GenericCraftPacket.matchesExecutionLease(
                entry, player(), recipeId, Map.of(), 1, target, type);
    }

    private ServerPlayer player() {
        ServerPlayer player = mock(ServerPlayer.class);
        when(player.getUUID()).thenReturn(playerId);
        return player;
    }

    private PlanCache.Entry entry(ItemStack result, ItemStack clicked,
                                  Map<ResourceLocation, ResourceLocation> forced) {
        PlanResponse plan = new PlanResponse(true, "", result, List.of(), Map.of(), List.of(),
                recipeId.toString(), type.id(), null, 0, 0, 0, List.of(), 1,
                null, null, null, 0L, false, false, false, null, Set.of(), Map.of(), clicked, null);
        PlanningSnapshot snapshot = new PlanningSnapshot(playerId, 1, 1, recipeId, Map.of(),
                forced, new ImmutableRecipeGraph(Map.of()), "", "", false);
        return new PlanCache.Entry(plan, snapshot, null, null, 1);
    }
}
