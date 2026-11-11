package com.huanghuang.rsintegration.crafting.batch;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.google.gson.JsonParser;
import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.CraftingResolver.StackKey;
import com.huanghuang.rsintegration.crafting.ScaledIngredientSpec;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.MaterialRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.RecipeNode;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraphProjector;
import com.huanghuang.rsintegration.crafting.planning.PurePlanGraphAdapter;
import com.huanghuang.rsintegration.crafting.planning.PureRecipePlanner;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.common.crafting.CraftingHelper;
import net.minecraftforge.common.crafting.VanillaIngredientSerializer;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.ForgeRegistry;
import net.minecraftforge.registries.IForgeRegistry;
import net.minecraftforge.registries.RegistryObject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 可选真实模组兼容测试：运行时显式提供已映射的 SMC 1.2.1，不修改或模拟武器实现。 */
@EnabledIfSystemProperty(named = "rsintegration.smc.compatibility", matches = "true")
class SmcConfirmationCompatibilityTest extends BootstrapTest {
    private static final ResourceLocation RECIPE = new ResourceLocation("smc", "devour_sword");
    private static Item sword;
    private static Class<?> registryClass;

    @BeforeAll
    @SuppressWarnings("unchecked")
    static void initializeSmc() throws Exception {
        GenericCraftPacketTest.loadServerConfig();
        if (CraftingHelper.getID(VanillaIngredientSerializer.INSTANCE) == null) {
            CraftingHelper.register(new ResourceLocation("minecraft", "item"), VanillaIngredientSerializer.INSTANCE);
        }
        // 纯测试没有 FML 模组目录；只屏蔽可选联动分支，物品工厂仍来自真实 jar。
        try (var modList = mockStatic(ModList.class)) {
            ModList loaded = mock(ModList.class);
            when(loaded.isLoaded("curios")).thenReturn(true);
            modList.when(ModList::get).thenReturn(loaded);
            registryClass = Class.forName("com.starmeow.smc.init.ItemRegistry");
        }
        for (String field : new String[]{"DEVOUR_SWORD", "DEVOUR_KNIFE", "EXCALIBUR", "ZENISH",
                "FROSTIUM_SWORD", "ZINC_SWORD", "KNIFE", "FROSTIUM_INGOT", "ZINC_INGOT"}) {
            registerRealItem(field);
        }
        sword = ((RegistryObject<Item>) registryClass.getField("DEVOUR_SWORD").get(null)).get();
        Class<?> configClass = Class.forName("com.starmeow.smc.config.Config");
        ForgeConfigSpec spec = (ForgeConfigSpec) configClass.getField("COMMON_CONFIG").get(null);
        CommentedConfig config = CommentedConfig.inMemory();
        spec.correct(config);
        spec.setConfig(config);
        assertEquals("com.starmeow.smc.items.DevourSword", sword.getClass().getName());
        assertEquals(8888, new ItemStack(sword).getMaxDamage());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void realRecipeResultSurvivesTooltipAttributesAndConfirmation(boolean clicked) throws Exception {
        CraftingRecipe recipe = readRecipe("devour_sword");
        assertEquals(9, recipe.getIngredients().size());
        ItemStack target = recipe.getResultItem(RegistryAccess.EMPTY).copy();
        assertSame(sword, target.getItem());
        assertRoundTrip(target, clicked, false);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void realSubrecipesPlanRecursivelyAndKeepTheSameGraphAtConfirmation(boolean clicked) throws Exception {
        CraftingRecipe terminal = readRecipe("devour_sword");
        ItemStack target = terminal.getResultItem(RegistryAccess.EMPTY).copy();
        RecipeNode root = ImmutableRecipeGraphProjector.projectCraftingRecipe(terminal, target);
        assertNotNull(root);
        Map<MaterialRef, List<RecipeNode>> producers = new LinkedHashMap<>();
        Map<StackKey, Long> available = new LinkedHashMap<>();
        for (String name : new String[]{"frostium_sword", "zinc_sword"}) {
            CraftingRecipe subrecipe = readRecipe(name);
            RecipeNode projected = ImmutableRecipeGraphProjector.projectCraftingRecipe(subrecipe,
                    subrecipe.getResultItem(RegistryAccess.EMPTY));
            assertNotNull(projected);
            producers.put(projected.output(), List.of(projected));
        }
        for (var ingredient : terminal.getIngredients()) {
            ItemStack input = ingredient.getItems()[0];
            String path = BuiltInRegistries.ITEM.getKey(input.getItem()).getPath();
            if (!path.equals("frostium_sword") && !path.equals("zinc_sword")) {
                available.merge(StackKey.of(input, true), 1L, Long::sum);
            }
        }
        for (String name : new String[]{"frostium_ingot", "zinc_ingot"}) {
            available.put(StackKey.of(new ItemStack(ForgeRegistries.ITEMS.getValue(
                    new ResourceLocation("smc", name))), true), 2L);
        }
        available.put(StackKey.of(new ItemStack(Items.STICK), true), 2L);
        Map<MaterialRef, Long> stock = new LinkedHashMap<>();
        available.forEach((key, count) -> stock.put(
                ImmutableRecipeGraphProjector.material(key.toStack(), true), count));
        ImmutableRecipeGraph graph = new ImmutableRecipeGraph(producers);
        PureRecipePlanner.Result result = PureRecipePlanner.resolve(graph, stock, root.inputs(), 20);
        assertTrue(result.feasible(), result.toString());
        assertEquals(2, result.steps().size(), "霜铸剑和锌剑必须从原料递归生产");
        var terminalInputs = CraftPacketUtils.extractCraftingIngredientSpecs(terminal).stream()
                .map(spec -> ScaledIngredientSpec.of(spec, spec.count())).toList();
        var resolved = PurePlanGraphAdapter.toInputGraph(result, available, terminalInputs);
        assertTrue(resolved.unresolvedDemands().isEmpty());
        try (var fixture = new ConfirmedPreviewTransportTest.Fixture(target, clicked, RECIPE,
                graph, available, result, resolved)) {
            assertSame(resolved, fixture.cache.peekForRequest(fixture.key, 10).resolvedGraph());
            fixture.assertAccepted(fixture.roundTrip(SmcConfirmationCompatibilityTest::inspectClientItem));
        }
        System.out.println("SMC真实递归配方：clicked=" + clicked + ", steps=" + result.steps()
                + ", feasible=" + result.feasible() + ", unresolved=" + resolved.unresolvedDemands().size());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void storedWeaponsSkinAndEnchantmentsSurviveConfirmation(boolean clicked) throws Exception {
        ItemStack target = new ItemStack(sword);
        target.setTag(TagParser.parseTag("{Damage:0,SMCWeaponStored:[\"smc:excalibur\",\"smc:zenish\"],"
                + "SMCWeaponSkin:\"minecraft:diamond_sword\",RepairCost:8,"
                + "Enchantments:[{id:\"minecraft:sharpness\",lvl:5s}],"
                + "display:{Name:'{\"text\":\"测试千刃锻锋\"}'}}"));
        assertRoundTrip(target, clicked, false);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void realSwordWithServerOnlyCapabilitySurvivesConfirmation(boolean clicked) throws Exception {
        ItemStack target = new ItemStack(sword);
        ConfirmedPreviewTransportTest.attachServerCapability(target);
        assertRoundTrip(target, clicked, true);
    }

    @Test
    void changingWeaponNbtStillRejectsConfirmation() {
        ItemStack target = new ItemStack(sword);
        try (var fixture = new ConfirmedPreviewTransportTest.Fixture(target, true, RECIPE)) {
            var confirmation = fixture.roundTrip(SmcConfirmationCompatibilityTest::inspectClientItem);
            confirmation.targetOutput().getOrCreateTag().putString("SMCWeaponSkin", "smc:zenish");
            assertTrue(fixture.confirm(confirmation).rejected());
            assertNotNull(fixture.cache.peekForRequest(fixture.key, 10));
        }
    }

    private static void assertRoundTrip(ItemStack target, boolean clicked, boolean serverCapability) {
        CompoundTag originalTag = target.getTag() == null ? null : target.getTag().copy();
        try (var fixture = new ConfirmedPreviewTransportTest.Fixture(target, clicked, RECIPE)) {
            var confirmation = fixture.roundTrip(SmcConfirmationCompatibilityTest::inspectClientItem);
            assertEquals(originalTag, confirmation.targetOutput().getTag(), "真实界面读取不应更改武器 NBT");
            assertEquals(!serverCapability, ItemStack.isSameItemSameTags(target, confirmation.targetOutput()),
                    "对照原确认条件，区分 SMC 自身 NBT 与额外挂载能力");
            fixture.assertAccepted(confirmation);
            System.out.println("SMC真实物品：clicked=" + clicked + ", serverCapability=" + serverCapability
                    + ", before=" + originalTag + ", after=" + confirmation.targetOutput().getTag());
        }
    }

    private static void inspectClientItem(ItemStack stack) {
        assertSame(sword, stack.getItem());
        assertFalse(stack.getTooltipLines(null, TooltipFlag.Default.ADVANCED).isEmpty());
        assertFalse(stack.getAttributeModifiers(EquipmentSlot.MAINHAND).isEmpty());
        stack.getHoverName();
    }

    private static CraftingRecipe readRecipe(String name) throws Exception {
        try (var input = registryClass.getResourceAsStream("/data/smc/recipes/" + name + ".json")) {
            assertNotNull(input);
            var json = JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            ResourceLocation id = new ResourceLocation("smc", name);
            return json.get("type").getAsString().equals("minecraft:crafting_shapeless")
                    ? new ShapelessRecipe.Serializer().fromJson(id, json)
                    : new ShapedRecipe.Serializer().fromJson(id, json);
        }
    }

    @SuppressWarnings("unchecked")
    private static void registerRealItem(String fieldName) throws Exception {
        RegistryObject<Item> object = (RegistryObject<Item>) registryClass.getField(fieldName).get(null);
        DeferredRegister<Item> deferred = (DeferredRegister<Item>) registryClass.getField("ITEMS").get(null);
        var entriesField = DeferredRegister.class.getDeclaredField("entries");
        entriesField.setAccessible(true);
        Map<RegistryObject<Item>, Supplier<? extends Item>> entries =
                (Map<RegistryObject<Item>, Supplier<? extends Item>>) entriesField.get(deferred);
        Supplier<? extends Item> factory = entries.get(object);
        assertNotNull(factory);
        ForgeRegistry<Item> registry = (ForgeRegistry<Item>) ForgeRegistries.ITEMS;
        boolean locked = registry.isLocked();
        registry.unfreeze();
        try {
            Method unfreeze = BuiltInRegistries.ITEM.getClass().getMethod("unfreeze");
            unfreeze.setAccessible(true);
            unfreeze.invoke(BuiltInRegistries.ITEM);
            registry.register(object.getId(), factory.get());
            Method update = RegistryObject.class.getDeclaredMethod("updateReference", IForgeRegistry.class);
            update.setAccessible(true);
            update.invoke(object, registry);
        } finally {
            BuiltInRegistries.ITEM.freeze();
            if (locked) registry.freeze();
        }
    }
}
