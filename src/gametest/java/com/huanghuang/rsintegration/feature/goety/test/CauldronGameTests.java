package com.huanghuang.rsintegration.feature.goety.test;

import com.Polarice3.Goety.common.blocks.BrewCauldronBlock;
import com.Polarice3.Goety.common.blocks.ModBlocks;
import com.Polarice3.Goety.common.blocks.entities.BrewCauldronBlockEntity;
import com.huanghuang.rsintegration.network.gui.*;
import com.huanghuang.rsintegration.feature.goety.brewing.*;
import com.huanghuang.rsintegration.feature.goety.menu.*;
import com.huanghuang.rsintegration.feature.goety.network.BrewNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.gametest.*;
import java.util.*;

@GameTestHolder("rs_integration")
@PrefixGameTestTemplate(false)
public final class CauldronGameTests {
    @GameTest(template = "empty", timeoutTicks = 20)
    public static void publicGuiApiRejectsRegistrationAfterFreeze(GameTestHelper h) {
        boolean accepted = com.huanghuang.rsintegration.api.machine.gui.RSIMachineGuiApi.register(
                net.minecraft.world.level.block.entity.BlockEntityType.BREWING_STAND,
                (player, entity, dim, pos) -> { throw new AssertionError("Late opener was installed"); });
        h.assertTrue(!accepted, "GUI API accepted a registration after load completion");
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void preferredGuiFailureNeverFallsBackToBlockUse(GameTestHelper h) {
        var be = cauldron(h, 0); var player = player(h, be);
        var pos = be.getBlockPos().above();
        h.getLevel().setBlockAndUpdate(pos, Blocks.BREWING_STAND.defaultBlockState());
        h.assertTrue(BlockGuiRegistry.registerPreferred(
                net.minecraft.world.level.block.entity.BlockEntityType.BREWING_STAND,
                (p, entity, dim, location) -> {}), "Fixture opener registration failed");
        h.assertTrue(!BlockGuiRegistry.openGui(player, h.getLevel().dimension(), pos), "Empty handler reported success");
        h.assertTrue(player.containerMenu == player.inventoryMenu, "Failed handler fell back to native block use");
        h.assertTrue(!RemoteGuiAuth.hasActiveAuthorization(player.getUUID()), "Failed menu leaked authorization");
        h.succeed();
    }
    private static BrewCauldronBlockEntity cauldron(GameTestHelper h, int water) {
        var pos = h.absolutePos(new BlockPos(2, 2, 2));
        var state = ModBlocks.BREWING_CAULDRON.get().defaultBlockState().setValue(BrewCauldronBlock.LEVEL, water);
        h.getLevel().setBlock(pos.below(), Blocks.MAGMA_BLOCK.defaultBlockState(), 3);
        h.getLevel().setBlock(pos, state, 3);
        return (BrewCauldronBlockEntity) h.getLevel().getBlockEntity(pos);
    }
    private static net.minecraftforge.common.util.FakePlayer player(GameTestHelper h, BrewCauldronBlockEntity be) {
        var player = FakePlayerFactory.get(h.getLevel(), new com.mojang.authlib.GameProfile(UUID.randomUUID(), "CauldronTest"));
        player.setPos(be.getBlockPos().getX() + .5, be.getBlockPos().getY(), be.getBlockPos().getZ() + .5);
        return player;
    }
    private static void souls(GameTestHelper h, BrewCauldronBlockEntity be) {
        var base = be.getBlockPos().offset(1, -1, 0);
        h.getLevel().setBlockAndUpdate(base, ModBlocks.CURSED_CAGE_BLOCK.get().defaultBlockState());
        var cage = (com.Polarice3.Goety.common.blocks.entities.CursedCageBlockEntity)h.getLevel().getBlockEntity(base);
        var totem = new ItemStack(com.Polarice3.Goety.common.items.ModItems.TOTEM_OF_SOULS.get());
        totem.getOrCreateTag().putInt("Souls", 100000); cage.setItem(totem);
        h.getLevel().setBlockAndUpdate(base.above(), ModBlocks.SOUL_CANDLESTICK.get().defaultBlockState());
        h.assertTrue(NativeCauldronActions.availableSouls(be) > 0, "Native soul fixture is not connected");
        be.heatTime = 100; be.tick();
    }
    private static BrewPlan firstPlan(BrewCauldronBlockEntity be, BrewCatalog catalog) {
        for (var entry : catalog.entries()) if (entry.kind() == BrewCatalog.EFFECT) {
            var plan = BrewPlan.create(be.getLevel(), be.getBlockPos(), be.getBlockState(), catalog, 0, List.of(entry.item()));
            if (plan.valid()) return plan;
        }
        throw new AssertionError("No native catalyst yielded a legal basic brew");
    }
    @GameTest(template = "empty", timeoutTicks = 20)
    public static void previewDoesNotChangeWorldOrCauldron(GameTestHelper h) {
        var be = cauldron(h, 0); var before = be.saveWithFullMetadata(); var block = be.getBlockState();
        var catalog = BrewCatalog.discover(h.getLevel()); var plan = firstPlan(be, catalog);
        h.assertTrue(plan.valid() && !plan.output().isEmpty(), "Native preview must produce a brew");
        h.assertTrue(before.equals(be.saveWithFullMetadata()), "Preview changed real cauldron NBT");
        h.assertTrue(block.equals(h.getLevel().getBlockState(be.getBlockPos())), "Preview changed real water level");
        h.succeed();
    }
    @GameTest(template = "empty", timeoutTicks = 20)
    public static void nativeProjectionMatchesRealInsertion(GameTestHelper h) {
        var be = cauldron(h, 3); var plan = firstPlan(be, BrewCatalog.discover(h.getLevel()));
        for (var stack : plan.sequence()) be.mode = be.insertItem(stack.copy());
        h.assertTrue(be.mode != BrewCauldronBlockEntity.Mode.FAILED, "Real native sequence failed");
        h.assertTrue(ItemStack.matches(plan.output(), be.getBrew()), "Real and simulated brew NBT differ");
        h.assertTrue(plan.soulCost() == be.totalCost, "Soul cost differs"); h.succeed();
    }
    @GameTest(template = "empty", timeoutTicks = 20)
    public static void rsiCanOpenEmptyCauldronWithoutVanillaUse(GameTestHelper h) {
        var be = cauldron(h, 0); var player = player(h, be);
        player.closeContainer();
        player.setPos(be.getBlockPos().getX() + 100, be.getBlockPos().getY(), be.getBlockPos().getZ());
        var before = be.saveWithFullMetadata();
        h.assertTrue(BlockGuiRegistry.openGui(player, h.getLevel().dimension(), be.getBlockPos()), "RSI opener returned false");
        h.assertTrue(player.containerMenu instanceof WitchCauldronMenu, "Dedicated UI was not opened");
        var menu = (WitchCauldronMenu)player.containerMenu;
        h.assertTrue(menu.stillValid(player), "Remote UI closes outside 8 blocks");
        h.assertTrue(before.equals(be.saveWithFullMetadata()), "Opening UI changed the cauldron");
        player.closeContainer(); h.succeed();
    }
    @GameTest(template = "empty", timeoutTicks = 20)
    public static void localMenuClosesAfterReplacement(GameTestHelper h) {
        var be = cauldron(h, 3); var player = player(h, be);
        player.closeContainer(); player.setPos(be.getBlockPos().getX(), be.getBlockPos().getY(), be.getBlockPos().getZ());
        var menu = WitchCauldronMenu.server(7, player.getInventory(), be, false);
        h.assertTrue(menu.stillValid(player), "Nearby local editor is invalid");
        h.getLevel().removeBlock(be.getBlockPos(), false);
        h.getLevel().setBlock(be.getBlockPos(), ModBlocks.BREWING_CAULDRON.get().defaultBlockState(), 3);
        h.assertTrue(!menu.stillValid(player), "Old menu accepted replacement block entity"); h.succeed();
    }
    @GameTest(template = "empty", timeoutTicks = 20)
    public static void realWaterAndBottleContainers(GameTestHelper h) {
        var be = cauldron(h, 0); var player = player(h, be);
        player.closeContainer(); player.getInventory().clearContent();
        var held = new ItemStack(Items.DIAMOND, 3); player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, held);
        var result = NativeCauldronActions.useContainer(player, be, new ItemStack(Items.WATER_BUCKET));
        h.assertTrue(result.is(Items.BUCKET), "Water did not return a bucket");
        h.assertTrue(be.getBlockState().getValue(BrewCauldronBlock.LEVEL) == 3, "Water bucket did not fill the cauldron");
        var water = NativeCauldronActions.useContainer(player, be, new ItemStack(Items.GLASS_BOTTLE));
        h.assertTrue(water.is(Items.POTION), "Glass bottle did not fill with real water");
        h.assertTrue(be.getBlockState().getValue(BrewCauldronBlock.LEVEL) == 2, "Bottling did not decrease water");
        h.assertTrue(player.getMainHandItem() == held && held.getCount() == 3, "Container operation changed player's original hand");
        h.succeed();
    }
    @GameTest(template = "empty", timeoutTicks = 20)
    public static void missingIngredientsDoNotConsumeAnything(GameTestHelper h) {
        var be = cauldron(h, 3); var player = player(h, be);
        player.closeContainer(); player.getInventory().clearContent(); be.heatTime = 100;
        souls(h, be);
        var catalog = BrewCatalog.discover(h.getLevel());
        var effect = catalog.entries().stream().filter(e -> e.kind() == BrewCatalog.EFFECT)
                .filter(e -> BrewPlan.create(h.getLevel(), be.getBlockPos(), be.getBlockState(), catalog, 0, List.of(e.item())).valid()).findFirst().orElseThrow();
        var menu = WitchCauldronMenu.server(9, player.getInventory(), be, false);
        var before = be.saveWithFullMetadata();
        menu.handle(player, new BrewNetwork.Edit(9, 1, 0, List.of(effect.item()), "feed"));
        h.assertTrue(before.equals(be.saveWithFullMetadata()), "Missing ingredients modified cauldron");
        h.assertTrue(player.getInventory().isEmpty(), "Missing ingredients created items"); h.succeed();
    }
    @GameTest(template = "empty", timeoutTicks = 20)
    public static void closingMenuReturnsTemporarySlots(GameTestHelper h) {
        var be = cauldron(h, 0); var player = player(h, be);
        player.closeContainer(); player.getInventory().clearContent();
        var menu = WitchCauldronMenu.server(10, player.getInventory(), be, false);
        menu.slots.get(0).set(new ItemStack(Items.WATER_BUCKET)); menu.slots.get(1).set(new ItemStack(Items.GLASS_BOTTLE, 7));
        menu.removed(player);
        h.assertTrue(WitchCauldronMenu.inventoryCount(player, new ItemStack(Items.WATER_BUCKET)) == 1, "Closing lost water bucket");
        h.assertTrue(WitchCauldronMenu.inventoryCount(player, new ItemStack(Items.GLASS_BOTTLE)) == 7, "Closing lost bottles"); h.succeed();
    }
    @GameTest(template = "empty", timeoutTicks = 40)
    public static void nativeBrewingRoundTrip(GameTestHelper h) {
        var be = cauldron(h, 3); var player = player(h, be); souls(h, be);
        var catalog = BrewCatalog.discover(h.getLevel()); var plan = firstPlan(be, catalog);
        var effect = plan.sequence().get(1); var id = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(effect.getItem());
        var menu = WitchCauldronMenu.server(11, player.getInventory(), be, false);
        for (var stack : plan.sequence()) player.getInventory().add(stack.copy());
        menu.handle(player, new BrewNetwork.Edit(11, 1, 0, List.of(id), "feed"));
        h.assertTrue(be.mode == BrewCauldronBlockEntity.Mode.BREWING, "UI did not feed native cauldron");
        player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, new ItemStack(com.Polarice3.Goety.common.items.ModItems.CAULDRON_LADLE.get()));
        menu.handle(player, new BrewNetwork.Edit(11, 2, 0, List.of(id), "stir"));
        h.assertTrue(be.isBrewing, "UI did not start native brewing");
        for (int i = 0; i < 4000 && be.mode == BrewCauldronBlockEntity.Mode.BREWING; i++) be.tick();
        h.assertTrue(be.mode == BrewCauldronBlockEntity.Mode.COMPLETED, "Native brew never completed");
        menu.slots.get(1).set(new ItemStack(Items.GLASS_BOTTLE, 3));
        menu.handle(player, new BrewNetwork.Edit(11, 3, 0, List.of(id), "bottle"));
        h.assertTrue(ItemStack.matches(plan.output(), menu.slots.get(2).getItem()), "Bottled item differs from native preview");
        h.assertTrue(menu.slots.get(1).getItem().getCount() == 2, "Bottling did not consume exactly one bottle");
        h.assertTrue(be.getBlockState().getValue(BrewCauldronBlock.LEVEL) == 2, "Bottling did not decrease remaining brew");
        h.succeed();
    }
    @GameTest(template = "empty", timeoutTicks = 40)
    public static void allModifierTiersMatchNativeAndShapes(GameTestHelper h) {
        var be = cauldron(h, 3); var catalog = BrewCatalog.discover(h.getLevel()); var basic = firstPlan(be, catalog);
        var effect = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(basic.sequence().get(1).getItem());
        int maximum = catalog.entries().stream().filter(e -> e.kind() == BrewCatalog.CAPACITY).mapToInt(BrewCatalog.Entry::level).max().orElseThrow();
        for (var modifier : catalog.entries()) if (modifier.kind() == BrewCatalog.MODIFIER) {
            var plan = BrewPlan.create(h.getLevel(), be.getBlockPos(), be.getBlockState(), catalog, maximum, List.of(effect, modifier.item()));
            h.assertTrue(plan.valid(), "Modifier rejected: " + modifier.group() + " tier " + modifier.level());
            be.mode = be.reset();
            for (var stack : plan.sequence()) be.mode = be.insertItem(stack.copy());
            h.assertTrue(be.mode != BrewCauldronBlockEntity.Mode.FAILED && ItemStack.matches(plan.output(), be.getBrew()), "Modifier projection mismatch: " + modifier.group());
            var expected = switch (modifier.group()) {
                case "splash" -> com.Polarice3.Goety.common.items.ModItems.SPLASH_BREW.get();
                case "lingering" -> com.Polarice3.Goety.common.items.ModItems.LINGERING_BREW.get();
                case "gas" -> com.Polarice3.Goety.common.items.ModItems.GAS_BREW.get();
                default -> null;
            };
            if (expected != null) h.assertTrue(plan.output().is(expected), "Shape was silently ignored: " + modifier.group());
        }
        h.succeed();
    }
    @GameTest(template = "empty", timeoutTicks = 20)
    public static void duplicateEditCannotConsumeTwice(GameTestHelper h) {
        var be = cauldron(h, 0); var player = player(h, be);
        var menu = WitchCauldronMenu.server(12, player.getInventory(), be, false);
        menu.slots.get(0).set(net.minecraft.world.item.alchemy.PotionUtils.setPotion(new ItemStack(Items.POTION, 2), net.minecraft.world.item.alchemy.Potions.WATER));
        var packet = new BrewNetwork.Edit(12, 1, 0, List.of(), "water");
        menu.handle(player, packet); menu.handle(player, packet);
        h.assertTrue(be.getBlockState().getValue(BrewCauldronBlock.LEVEL) == 1, "Duplicate request added water twice");
        h.assertTrue(menu.slots.get(0).getItem().getCount() == 1, "Duplicate request consumed twice"); h.succeed();
    }
    @GameTest(template = "empty", timeoutTicks = 20)
    public static void creativeContainerExchangeUsesOutputSlot(GameTestHelper h) {
        var be = cauldron(h, 0); var player = player(h, be);
        player.gameMode.changeGameModeForPlayer(net.minecraft.world.level.GameType.CREATIVE);
        h.assertTrue(player.isCreative(), "Fixture is not a creative player");
        var bucket = NativeCauldronActions.useContainer(player, be, new ItemStack(Items.WATER_BUCKET));
        h.assertTrue(bucket.is(Items.BUCKET), "Creative water returned its input");
        var result = NativeCauldronActions.useContainer(player, be, new ItemStack(Items.GLASS_BOTTLE));
        h.assertTrue(result.is(Items.POTION), "Creative bottling returned a glass bottle");
        h.assertTrue(player.getAbilities().instabuild, "Creative ability was not restored"); h.succeed();
    }
    @GameTest(template = "empty", timeoutTicks = 20)
    public static void ordinaryUseRestoredAndTerminalsOpenEditor(GameTestHelper h) {
        var be = cauldron(h, 0); var player = player(h, be); player.closeContainer();
        var hit = new net.minecraft.world.phys.BlockHitResult(net.minecraft.world.phys.Vec3.atCenterOf(be.getBlockPos()), net.minecraft.core.Direction.UP, be.getBlockPos(), false);
        for (var item : List.of(Items.WATER_BUCKET, Items.GLASS_BOTTLE, Items.OAK_SAPLING,
                com.Polarice3.Goety.common.items.ModItems.CAULDRON_LADLE.get())) {
            player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, new ItemStack(item));
            for (var hand : net.minecraft.world.InteractionHand.values()) {
                var event = new net.minecraftforge.event.entity.player.PlayerInteractEvent.RightClickBlock(player, hand, be.getBlockPos(), hit);
                CauldronInteraction.rightClick(event);
                h.assertTrue(!event.isCanceled(), "Native interaction was intercepted: " + item);
            }
        }
        player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, new ItemStack(Items.WATER_BUCKET));
        be.getBlockState().use(h.getLevel(), player, net.minecraft.world.InteractionHand.MAIN_HAND, hit);
        h.assertTrue(be.getBlockState().getValue(BrewCauldronBlock.LEVEL) == 3, "Restored native water interaction failed");
        player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, new ItemStack(com.refinedmods.refinedstorage.RSItems.CREATIVE_WIRELESS_GRID.get()));
        var denied = new net.minecraftforge.event.entity.player.PlayerInteractEvent.RightClickBlock(player, net.minecraft.world.InteractionHand.MAIN_HAND, be.getBlockPos(), hit);
        denied.setUseBlock(net.minecraftforge.eventbus.api.Event.Result.DENY); CauldronInteraction.rightClick(denied);
        h.assertTrue(!(player.containerMenu instanceof WitchCauldronMenu), "Terminal bypassed protection DENY");
        player.setShiftKeyDown(true);
        var binding = new net.minecraftforge.event.entity.player.PlayerInteractEvent.RightClickBlock(player, net.minecraft.world.InteractionHand.MAIN_HAND, be.getBlockPos(), hit);
        CauldronInteraction.rightClick(binding); h.assertTrue(!binding.isCanceled(), "RSI sneak binding was intercepted"); player.setShiftKeyDown(false);
        var before = be.saveWithFullMetadata();
        var terminal = new net.minecraftforge.event.entity.player.PlayerInteractEvent.RightClickBlock(player, net.minecraft.world.InteractionHand.MAIN_HAND, be.getBlockPos(), hit);
        CauldronInteraction.rightClick(terminal);
        h.assertTrue(terminal.isCanceled() && player.containerMenu instanceof WitchCauldronMenu, "Held terminal did not open UI");
        h.assertTrue(before.equals(be.saveWithFullMetadata()), "Terminal UI changed real brew contents");
        player.closeContainer();
        player.setItemInHand(net.minecraft.world.InteractionHand.OFF_HAND, player.getMainHandItem());
        player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        var offhand = new net.minecraftforge.event.entity.player.PlayerInteractEvent.RightClickBlock(player, net.minecraft.world.InteractionHand.MAIN_HAND, be.getBlockPos(), hit);
        CauldronInteraction.rightClick(offhand);
        h.assertTrue(offhand.isCanceled() && player.containerMenu instanceof WitchCauldronMenu, "Offhand terminal did not open editor before empty main-hand use");
        player.closeContainer(); h.succeed();
    }
    @GameTest(template = "empty", timeoutTicks = 20)
    public static void effectAndModifierFamiliesCollapseDuplicateRows(GameTestHelper h) {
        var catalog = BrewCatalog.discover(h.getLevel());
        var effects = BrewCatalog.groups(catalog.entries(), BrewCatalog.EFFECT);
        h.assertTrue(effects.size() < catalog.entries().stream().filter(e -> e.kind() == BrewCatalog.EFFECT).count(), "Effect alternatives still have separate rows");
        h.assertTrue(effects.stream().map(BrewCatalog.Group::key).distinct().count() == effects.size(), "Duplicate effect rows");
        h.assertTrue(effects.stream().anyMatch(g -> g.entries().stream().filter(e -> e.stack().getItem() instanceof BlockItem b
                && b.getBlock() instanceof net.minecraft.world.level.block.SaplingBlock).count() > 1), "Sapling variants are not grouped");
        var modifiers = BrewCatalog.groups(catalog.entries(), BrewCatalog.MODIFIER);
        h.assertTrue(modifiers.stream().anyMatch(g -> g.levels().size() > 1), "Enhancement levels have no shared family");
        h.assertTrue(modifiers.stream().map(BrewCatalog.Group::key).distinct().count() == modifiers.size(), "Duplicate enhancement family rows");
        h.succeed();
    }
    @GameTest(template = "empty", timeoutTicks = 40)
    public static void recommendedCapacityIsMinimalAndNativeValid(GameTestHelper h) {
        var be = cauldron(h, 3); var catalog = BrewCatalog.discover(h.getLevel());
        var basic = firstPlan(be, catalog);
        var effect = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(basic.sequence().get(1).getItem());
        int tested = 0;
        for (var modifier : catalog.entries()) if (modifier.kind() == BrewCatalog.MODIFIER) {
            var selected = List.of(effect, modifier.item());
            var plan = BrewPlan.recommend(h.getLevel(), be.getBlockPos(), be.getBlockState(), catalog, selected, ignored -> 0L);
            h.assertTrue(plan.valid(), "Auto capacity rejected modifier " + modifier.group() + ":" + modifier.level());
            for (int tier = 0; tier < plan.capacityTier(); tier++) h.assertTrue(!BrewPlan.create(h.getLevel(), be.getBlockPos(), be.getBlockState(), catalog, tier, selected).valid(), "Recommendation skipped a valid smaller capacity");
            be.mode = be.reset();
            for (var stack : plan.sequence()) be.mode = be.insertItem(stack.copy());
            h.assertTrue(be.mode != BrewCauldronBlockEntity.Mode.FAILED && ItemStack.matches(plan.output(), be.getBrew()), "Recommended plan differs from native brew");
            tested++;
        }
        h.assertTrue(tested > 0, "No enhancements tested"); h.succeed();
    }
    @GameTest(template = "empty", timeoutTicks = 40)
    public static void groupedCatalystUsesAvailableEquivalentAndFeeds(GameTestHelper h) {
        var be = cauldron(h, 3); var player = player(h, be); player.getInventory().clearContent(); souls(h, be);
        var catalog = BrewCatalog.discover(h.getLevel());
        BrewCatalog.Entry requested = null, alternative = null;
        outer: for (var group : BrewCatalog.groups(catalog.entries(), BrewCatalog.EFFECT)) {
            for (var first : group.entries()) for (var other : group.entries()) {
                if (!first.item().equals(other.item()) && !first.group().equals(other.group()) && first.equivalence().equals(other.equivalence())
                        && first.family().equals("effect.goety.grow_tree")
                        && BrewPlan.recommend(h.getLevel(), be.getBlockPos(), be.getBlockState(), catalog, List.of(first.item()), ignored -> 0L).valid()) {
                    requested = first; alternative = other; break outer;
                }
            }
        }
        h.assertTrue(requested != null, "No equivalent catalyst fixture");
        player.getInventory().add(alternative.stack());
        var menu = WitchCauldronMenu.server(30, player.getInventory(), be, false);
        menu.handle(player, new BrewNetwork.Edit(30, 1, -1, List.of(requested.item()), "preview"));
        var plan = menu.plan();
        h.assertTrue(plan.valid(), "Auto-capacity menu preview invalid");
        final var wanted = alternative;
        h.assertTrue(plan.sequence().stream().anyMatch(s -> s.is(wanted.stack().getItem())), "Grouped effect ignored available alternative");
        for (var stack : menu.requirements()) if (!stack.is(alternative.stack().getItem())) player.getInventory().add(stack.copy());
        menu.handle(player, new BrewNetwork.Edit(30, 2, -1, List.of(requested.item()), "feed"));
        h.assertTrue(be.mode == BrewCauldronBlockEntity.Mode.BREWING && ItemStack.matches(plan.output(), be.getBrew()), "Grouped alternative did not feed a matching native brew");
        h.assertTrue(WitchCauldronMenu.inventoryCount(player, alternative.stack()) == 0, "Alternative catalyst was not consumed");
        h.succeed();
    }
    @GameTest(template = "empty", timeoutTicks = 20)
    public static void clearingWasteCancelsColourRefill(GameTestHelper h) {
        var be = cauldron(h, 3); var player = player(h, be); be.mode = be.fail();
        var menu = WitchCauldronMenu.server(13, player.getInventory(), be, false);
        menu.handle(player, new BrewNetwork.Edit(13, 1, 0, List.of(), "clear"));
        for (int i = 0; i < 6; i++) be.tick();
        h.assertTrue(be.mode == BrewCauldronBlockEntity.Mode.IDLE && be.getBlockState().getValue(BrewCauldronBlock.LEVEL) == 0,
                "Native colour animation refilled cleared waste"); h.succeed();
    }
    @GameTest(template = "empty", timeoutTicks = 200)
    public static void realStorageFeedsAndUsesNamedLadle(GameTestHelper h) {
        var be = cauldron(h, 3); var player = player(h, be); souls(h, be);
        var cp = h.absolutePos(new BlockPos(0, 1, 0));
        h.getLevel().setBlockAndUpdate(cp, com.refinedmods.refinedstorage.RSBlocks.CREATIVE_CONTROLLER
                .get(com.refinedmods.refinedstorage.util.ColorMap.DEFAULT_COLOR).get().defaultBlockState());
        h.getLevel().setBlockAndUpdate(cp.east(), com.refinedmods.refinedstorage.RSBlocks.STORAGE_BLOCKS
                .get(com.refinedmods.refinedstorage.apiimpl.storage.ItemStorageType.ONE_K).get().defaultBlockState());
        h.runAfterDelay(30, () -> {
            var controller = (com.refinedmods.refinedstorage.blockentity.ControllerBlockEntity)h.getLevel().getBlockEntity(cp);
            var network = controller.getNetwork();
            network.getNodeGraph().invalidate(com.refinedmods.refinedstorage.api.util.Action.PERFORM, h.getLevel(), cp);
            var session = new com.huanghuang.rsintegration.storage.rs.RefinedStorageBackend().openSession(network);
            com.huanghuang.rsintegration.network.RSIntegrationNetwork.rememberActiveSidePanelNetwork(player.getUUID(), network);
            try {
                h.assertTrue(com.huanghuang.rsintegration.feature.goety.integration.RsiStorageLink.bind(player, be), "Actual RSI default network could not bind");
                var catalog = BrewCatalog.discover(h.getLevel()); var plan = firstPlan(be, catalog);
                for (var stack : plan.sequence()) h.assertTrue(session.insert(player, stack.copy(), false).complete(), "RS network refused test ingredients");
                var ladle = new ItemStack(com.Polarice3.Goety.common.items.ModItems.CAULDRON_LADLE.get());
                ladle.setHoverName(net.minecraft.network.chat.Component.literal("Named test ladle"));
                h.assertTrue(session.insert(player, ladle, false).complete(), "Network refused named ladle");
                var id = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(plan.sequence().get(1).getItem());
                var menu = WitchCauldronMenu.server(14, player.getInventory(), be, false);
                menu.handle(player, new BrewNetwork.Edit(14, 1, 0, List.of(id), "feed"));
                h.assertTrue(be.mode == BrewCauldronBlockEntity.Mode.BREWING, "Actual network ingredients were not fed");
                menu.handle(player, new BrewNetwork.Edit(14, 2, 0, List.of(id), "stir"));
                h.assertTrue(be.isBrewing && player.getMainHandItem().isEmpty(), "Network ladle did not allow empty-hand stirring");
                h.assertTrue(com.huanghuang.rsintegration.feature.goety.integration.RsiStorageLink.count(player, com.huanghuang.rsintegration.api.storage.RSIStorageServices.get().orElseThrow().defaultStorage(player).orElseThrow(), ladle) == 1, "Network ladle was consumed");
                h.succeed();
            } finally {
                com.huanghuang.rsintegration.network.RSIntegrationNetwork.forgetActiveSidePanelNetwork(player.getUUID());
                com.huanghuang.rsintegration.network.RSIntegrationNetwork.invalidateNetworkResolution(player.getUUID());
            }
        });
    }
    @GameTest(template = "empty", timeoutTicks = 40)
    public static void twoPlayersCannotFeedSameBatchTwice(GameTestHelper h) {
        var be = cauldron(h, 3); var first = player(h, be); var second = player(h, be); souls(h, be);
        var catalog = BrewCatalog.discover(h.getLevel()); var plan = firstPlan(be, catalog);
        var id = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(plan.sequence().get(1).getItem());
        for (var item : plan.sequence()) { first.getInventory().add(item.copy()); second.getInventory().add(item.copy()); }
        var a = WitchCauldronMenu.server(15, first.getInventory(), be, false);
        var b = WitchCauldronMenu.server(16, second.getInventory(), be, false);
        a.handle(first, new BrewNetwork.Edit(15, 1, 0, List.of(id), "feed"));
        var before = be.saveWithFullMetadata();
        b.handle(second, new BrewNetwork.Edit(16, 1, 0, List.of(id), "feed"));
        h.assertTrue(before.equals(be.saveWithFullMetadata()), "Second player changed prepared batch");
        for (var item : plan.sequence()) h.assertTrue(WitchCauldronMenu.inventoryCount(second, item) == 1, "Second player's material was consumed");
        h.succeed();
    }
    @GameTest(template = "empty", timeoutTicks = 600)
    public static void consumerRefundsOfflinePlayerBeforeExecution(GameTestHelper h) {
        var be = cauldron(h, 0); var player = player(h, be);
        var cp = h.absolutePos(new BlockPos(0, 1, 0));
        h.getLevel().setBlockAndUpdate(cp, com.refinedmods.refinedstorage.RSBlocks.CREATIVE_CONTROLLER
                .get(com.refinedmods.refinedstorage.util.ColorMap.DEFAULT_COLOR).get().defaultBlockState());
        h.getLevel().setBlockAndUpdate(cp.east(), com.refinedmods.refinedstorage.RSBlocks.STORAGE_BLOCKS
                .get(com.refinedmods.refinedstorage.apiimpl.storage.ItemStorageType.ONE_K).get().defaultBlockState());
        var sessions = new com.huanghuang.rsintegration.storage.StorageSession[1];
        var orders = new com.huanghuang.rsintegration.feature.goety.integration.MissingMaterialOrders();
        var lastMessage = new String[]{"Not started"};
        h.onEachTick(() -> {
            if (h.getTick() < 60 || h.getTick() % 5 != 0) return;
            if (sessions[0] == null) {
                var controller = (com.refinedmods.refinedstorage.blockentity.ControllerBlockEntity)h.getLevel().getBlockEntity(cp);
                var network = controller.getNetwork();
                network.getNodeGraph().invalidate(com.refinedmods.refinedstorage.api.util.Action.PERFORM, h.getLevel(), cp);
                sessions[0] = new com.huanghuang.rsintegration.storage.rs.RefinedStorageBackend().openSession(network);
                com.huanghuang.rsintegration.network.RSIntegrationNetwork.rememberActiveSidePanelNetwork(player.getUUID(), network);
                h.assertTrue(sessions[0].insert(player, new ItemStack(Items.OAK_LOG, 2), false).complete(), "Network refused logs");
                var message = orders.start(player, List.of(new ItemStack(Items.STICK, 4)), com.huanghuang.rsintegration.api.storage.RSIStorageServices.get().orElseThrow().defaultStorage(player).orElseThrow());
                h.assertTrue(orders.busy(), "Consumer did not start: " + message.getString());
                lastMessage[0] = message.getString(); return;
            }
            orders.tick(player);
            var update = orders.takeMessage(); if (update != null) lastMessage[0] = update.getString();
            if (!orders.busy()) {
                try {
                    // FakePlayer is not in PlayerList: RSI must cancel/refund its accepted graph.
                    h.assertTrue(WitchCauldronMenu.inventoryCount(player, new ItemStack(Items.STICK)) == 0,
                            "Offline player unexpectedly received an output");
                    h.assertTrue(com.huanghuang.rsintegration.feature.goety.integration.RsiStorageLink.count(player, com.huanghuang.rsintegration.api.storage.RSIStorageServices.get().orElseThrow().defaultStorage(player).orElseThrow(), new ItemStack(Items.OAK_LOG)) == 2,
                            "Offline order lost network logs: " + lastMessage[0]);
                    h.succeed();
                } finally {
                    orders.close(player);
                    com.huanghuang.rsintegration.network.RSIntegrationNetwork.forgetActiveSidePanelNetwork(player.getUUID());
                    com.huanghuang.rsintegration.network.RSIntegrationNetwork.invalidateNetworkResolution(player.getUUID());
                }
            }
        });
    }
}
