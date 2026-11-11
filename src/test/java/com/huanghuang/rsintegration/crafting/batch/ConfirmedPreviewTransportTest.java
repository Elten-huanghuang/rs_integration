package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.crafting.OutputDestination;
import com.huanghuang.rsintegration.crafting.CraftingResolver.StackKey;
import com.huanghuang.rsintegration.crafting.graph.CraftPlanGraph;
import com.huanghuang.rsintegration.crafting.plan.PlanResponse;
import com.huanghuang.rsintegration.crafting.plan.PlanResponsePacket;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph;
import com.huanghuang.rsintegration.crafting.planning.PlanCache;
import com.huanghuang.rsintegration.crafting.planning.PlanningSnapshot;
import com.huanghuang.rsintegration.crafting.planning.PlanningStateValidator;
import com.huanghuang.rsintegration.crafting.planning.PureRecipePlanner;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.CapabilityDispatcher;
import net.minecraftforge.common.capabilities.CapabilityProvider;
import net.minecraftforge.common.capabilities.ICapabilitySerializable;
import net.minecraftforge.common.util.LazyOptional;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 使用生产编解码器完整往返预览与确认，覆盖普通 NBT 和服务端能力数据。 */
class ConfirmedPreviewTransportTest extends BootstrapTest {
    @BeforeAll
    static void initialize() {
        GenericCraftPacketTest.loadServerConfig();
    }

    static Stream<Arguments> targetVariants() throws Exception {
        ItemStack emptyTag = new ItemStack(Items.BOOK);
        emptyTag.setTag(new CompoundTag());
        ItemStack rich = new ItemStack(Items.DIAMOND_SWORD);
        rich.setTag(TagParser.parseTag("{Damage:0,Unbreakable:1b,RepairCost:8,"
                + "itemModifier:\"celestial_forge:vicious\",display:{Name:'{\"text\":\"测试剑\"}'}}"));
        ItemStack book = new ItemStack(Items.ENCHANTED_BOOK);
        book.setTag(TagParser.parseTag("{StoredEnchantments:[{id:\"minecraft:sharpness\",lvl:3s}]}"));
        ItemStack potion = new ItemStack(Items.POTION);
        potion.setTag(TagParser.parseTag("{Potion:\"minecraft:strong_healing\"}"));
        return Stream.of(new ItemStack(Items.BOOK), emptyTag, new ItemStack(Items.DIAMOND_SWORD),
                        rich, book, potion)
                .flatMap(stack -> Stream.of(Arguments.of(stack, false), Arguments.of(stack, true)));
    }

    @ParameterizedTest
    @MethodSource("targetVariants")
    void unchangedTargetSurvivesPreviewAndConfirmationPackets(ItemStack target, boolean clicked) {
        try (Fixture fixture = new Fixture(target, clicked)) {
            var confirmation = fixture.roundTrip();
            assertEquals(target.getTag(), confirmation.targetOutput().getTag());
            fixture.assertAccepted(confirmation);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void serverOnlyCapabilitiesDoNotInvalidateTheDisplayedTarget(boolean clicked) throws Exception {
        ItemStack target = new ItemStack(Items.DIAMOND_SWORD);
        attachServerCapability(target);
        try (Fixture fixture = new Fixture(target, clicked)) {
            var confirmation = fixture.roundTrip();
            assertEquals(target.getTag(), confirmation.targetOutput().getTag());
            assertFalse(ItemStack.isSameItemSameTags(target, confirmation.targetOutput()),
                    "普通物品包不传服务端能力，完整物品比较会把正确确认误判为不一致");
            fixture.assertAccepted(confirmation);
        }
    }

    @Test
    void shareTagProjectionDoesNotInvalidateTheDisplayedTarget() {
        ItemStack target = spy(new ItemStack(Items.DIAMOND_SWORD));
        CompoundTag shared = target.getTag().copy();
        shared.putString("client_display", "shared projection");
        doReturn(shared).when(target).getShareTag();
        try (Fixture fixture = new Fixture(target, true)) {
            var confirmation = fixture.roundTrip();
            assertEquals(shared, confirmation.targetOutput().getTag());
            fixture.assertAccepted(confirmation);
        }
    }

    @Test
    void privateServerNbtStaysInTheExecutionTargetWhenItIsHiddenFromTheClient() {
        ItemStack target = spy(new ItemStack(Items.ENCHANTED_BOOK));
        target.getOrCreateTag().putInt("level", 3);
        target.getOrCreateTag().putString("server_private", "original value");
        CompoundTag shared = target.getTag().copy();
        shared.remove("server_private");
        doReturn(shared).when(target).getShareTag();
        try (Fixture fixture = new Fixture(target, true)) {
            var confirmation = fixture.roundTrip();
            assertFalse(confirmation.targetOutput().getTag().contains("server_private"));
            fixture.assertAccepted(confirmation);
            assertEquals("original value", CraftConfirmationTarget.executionTarget(fixture.plan)
                    .getTag().getString("server_private"));
        }
    }

    @Test
    void changingRealTargetNbtStillRejectsConfirmationWithoutConsumingPreview() {
        ItemStack target = new ItemStack(Items.ENCHANTED_BOOK);
        target.getOrCreateTag().putInt("level", 3);
        try (Fixture fixture = new Fixture(target, true)) {
            var confirmation = fixture.roundTrip();
            confirmation.targetOutput().getOrCreateTag().putInt("level", 4);
            assertTrue(fixture.confirm(confirmation).rejected());
            assertNotNull(fixture.cache.peekForRequest(fixture.key, 10));
        }
    }

    static void attachServerCapability(ItemStack stack) throws Exception {
        ICapabilitySerializable<CompoundTag> provider = new ICapabilitySerializable<>() {
            @Override
            public <T> LazyOptional<T> getCapability(Capability<T> capability, Direction side) {
                return LazyOptional.empty();
            }

            @Override
            public CompoundTag serializeNBT() {
                CompoundTag tag = new CompoundTag();
                tag.putString("server_identity", "not included in the item packet");
                return tag;
            }

            @Override
            public void deserializeNBT(CompoundTag tag) {}
        };
        // 纯单测没有 Forge 附加能力事件，手动挂真实 dispatcher 来还原网络边界。
        var capabilities = CapabilityProvider.class.getDeclaredField("capabilities");
        capabilities.setAccessible(true);
        capabilities.set(stack, new CapabilityDispatcher(
                Map.of(new ResourceLocation("test", "server_only"), provider), List.of()));
        var initialized = CapabilityProvider.class.getDeclaredField("initialized");
        initialized.setAccessible(true);
        initialized.setBoolean(stack, true);
    }

    static final class Fixture implements AutoCloseable {
        final ServerPlayer player = mock(ServerPlayer.class);
        final PlanCache cache = CraftPlanningRuntime.planCache();
        final ResourceLocation recipe;
        final PlanCache.Key key;
        final PlanResponse plan;
        final PlanningSnapshot snapshot;

        Fixture(ItemStack target, boolean clicked) {
            this(target, clicked, new ResourceLocation("test", "target"));
        }

        Fixture(ItemStack target, boolean clicked, ResourceLocation recipe) {
            this(target, clicked, recipe, new ImmutableRecipeGraph(Map.of()), Map.of(), null, null);
        }

        Fixture(ItemStack target, boolean clicked, ResourceLocation recipe,
                ImmutableRecipeGraph graph, Map<StackKey, Long> available,
                PureRecipePlanner.Result purePlan, CraftPlanGraph resolvedGraph) {
            this.recipe = recipe;
            when(player.getUUID()).thenReturn(UUID.randomUUID());
            ServerLevel level = mock(ServerLevel.class);
            when(level.dimension()).thenReturn(Level.OVERWORLD);
            when(player.serverLevel()).thenReturn(level);
            when(player.blockPosition()).thenReturn(BlockPos.ZERO);
            key = CraftPlanCacheKeys.key(player.getUUID(), recipe, Map.of(), 1,
                    clicked ? target : null, null);
            snapshot = new PlanningSnapshot(player.getUUID(), 1, key.recipeRevision(), recipe,
                    available, Map.of(), graph, "", "", false);
            plan = new PlanResponse(true, "target", target, List.of(), Map.of(), List.of(),
                    recipe.toString(), null, null, 0, 0, 0, List.of(), 1,
                    null, null, null, 0, false, false, false, null, Set.of(), Map.of(),
                    clicked ? target : null, null);
            cache.put(key, plan, snapshot, purePlan, resolvedGraph, System.nanoTime());
            cache.retainForRequest(player.getUUID(), 10, snapshot);
        }

        GenericCraftPacketCodec.WireData roundTrip() {
            return roundTrip(target -> {});
        }

        GenericCraftPacketCodec.WireData roundTrip(Consumer<ItemStack> clientInspection) {
            FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
            try {
                new PlanResponsePacket(plan, 10).encode(buffer);
                var received = PlanResponsePacket.decode(buffer);
                var displayed = received.plan();
                ItemStack target = displayed.clickedOutput() == null
                        ? displayed.targetResult() : displayed.clickedOutput();
                clientInspection.accept(target);
                var packet = new GenericCraftPacket(recipe, false, Map.of(), null, null, 1,
                        false, null, target.copy(), 20, OutputDestination.RS_NETWORK)
                        .withConfirmedPreview(received.requestId());
                buffer.clear();
                packet.encode(buffer);
                return GenericCraftPacketCodec.read(buffer);
            } finally {
                buffer.release();
            }
        }

        GenericCraftPacket.ExecutionPlanLookup confirm(GenericCraftPacketCodec.WireData confirmation) {
            return GenericCraftPacket.findValidatedExecutionPlan(player, recipe,
                    confirmation.forcedRecipes(), confirmation.repeatCount(), confirmation.targetOutput(),
                    null, null, null, null, confirmation.materialLocks(),
                    confirmation.confirmedPreviewRequestId());
        }

        void assertAccepted(GenericCraftPacketCodec.WireData confirmation) {
            try (var validator = mockStatic(PlanningStateValidator.class)) {
                validator.when(() -> PlanningStateValidator.revalidateForExecution(player,
                        snapshot, plan, Level.OVERWORLD, BlockPos.ZERO, null)).thenReturn(true);
                var result = confirm(confirmation);
                assertFalse(result.rejected(), "未经修改的预览往返网络包后必须能确认");
                assertSame(plan, result.entry().plan());
                validator.verify(() -> PlanningStateValidator.revalidateForExecution(player,
                        snapshot, plan, Level.OVERWORLD, BlockPos.ZERO, null));
            }
        }

        @Override
        public void close() {
            cache.removePlayer(player.getUUID());
        }
    }
}
