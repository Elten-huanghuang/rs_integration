package com.huanghuang.rsintegration.mods.farmersdelight;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.core.NonNullList;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraftforge.items.wrapper.RecipeWrapper;
import net.minecraftforge.items.ItemStackHandler;
import vectorwing.farmersdelight.common.crafting.CuttingBoardRecipe;
import vectorwing.farmersdelight.common.crafting.ingredient.ChanceResult;
import vectorwing.farmersdelight.common.Configuration;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CuttingBoardCompatibilityTest extends BootstrapTest {
    private static final String RECIPE =
            "vectorwing/farmersdelight/common/crafting/CuttingBoardRecipe.class";
    private static final String BLOCK_ENTITY =
            "vectorwing/farmersdelight/common/block/entity/CuttingBoardBlockEntity.class";

    @Test
    void nativeMissMayReturnNoItemsThroughEitherVersionAdapter() throws ReflectiveOperationException {
        CommentedConfig config = CommentedConfig.inMemory();
        Configuration.COMMON_CONFIG.correct(config);
        Configuration.COMMON_CONFIG.setConfig(config);
        CuttingBoardRecipe recipe = new CuttingBoardRecipe(
                new ResourceLocation("farmersdelight", "cutting/test_zero"), "",
                Ingredient.of(Items.PAPER), Ingredient.of(Items.IRON_SWORD),
                NonNullList.of(ChanceResult.EMPTY,
                        new ChanceResult(new ItemStack(Items.STICK, 4), 0.0F)), "");
        Method find = CuttingBoardBatchDelegate.class.getDeclaredMethod("findRollResultsMethod", Class.class);
        find.setAccessible(true);
        Method nativeRoll = (Method) find.invoke(null, recipe.getClass());
        Method roll = CuttingBoardBatchDelegate.class.getDeclaredMethod("rollResults",
                Recipe.class, Method.class, RandomSource.class, int.class, ItemStackHandler.class);
        roll.setAccessible(true);
        ItemStackHandler inventory = new ItemStackHandler(1);
        inventory.setStackInSlot(0, new ItemStack(Items.PAPER));

        assertEquals(List.of(), roll.invoke(null, recipe, nativeRoll,
                RandomSource.create(1L), 0, inventory));
    }

    @Test
    void repeatedBatchRequiresTheNewRecipeWrapperContract() {
        assertFalse(CuttingBoardBatchDelegate.supportsRepeatedRolls(OldRollFixture.class));
        assertTrue(CuttingBoardBatchDelegate.supportsRepeatedRolls(NewRollFixture.class));
    }

    @Test
    void cuttingBoardUsesItsDedicatedRecipeHandler() {
        CuttingBoardRecipe recipe = new CuttingBoardRecipe(
                new ResourceLocation("farmersdelight", "cutting/test_branch"),
                "",
                Ingredient.of(Items.OAK_LOG),
                Ingredient.of(Items.IRON_SWORD),
                NonNullList.of(ChanceResult.EMPTY,
                        new ChanceResult(new ItemStack(Items.STICK), 1.0F)),
                "");

        assertTrue(new CuttingBoardRecipeHandler().canHandle(recipe));
        assertFalse(new FarmersDelightRecipeHandler().canHandle(recipe));
    }

    @Test
    void cuttingBoardFailureCanRefundStagedInputs() {
        assertTrue(new CuttingBoardBatchDelegate().isFailureRefundSafe());
    }

    public static final class OldRollFixture {
        public List<ItemStack> rollResults(RandomSource random, int fortune) { return List.of(); }
    }

    public static final class NewRollFixture {
        public List<ItemStack> rollResults(RandomSource random, int fortune,
                                           RecipeWrapper wrapper) { return List.of(); }
    }

    @Test
    void supportsBothFarmersDelightRollResultContracts() throws IOException {
        Path oldJar = Path.of("libs", "FarmersDelight-1.20.1-1.2.8.jar");
        Path newJar = Path.of("libs", "FarmersDelight-1.20.1-1.3.3.jar");
        assertTrue(Files.isRegularFile(oldJar), () -> "Missing compatibility fixture: " + oldJar);
        assertTrue(Files.isRegularFile(newJar), () -> "Missing compatibility fixture: " + newJar);

        String prefix = "rollResults(Lnet/minecraft/util/RandomSource;I";
        assertTrue(methods(oldJar, RECIPE).contains(prefix + ")Ljava/util/List;"));
        assertTrue(methods(newJar, RECIPE).contains(prefix
                + "Lnet/minecraftforge/items/wrapper/RecipeWrapper;)Ljava/util/List;"));
    }

    @Test
    void supportedReleasesExposeCuttingBoardDisplayResults() throws IOException {
        Path oldJar = Path.of("libs", "FarmersDelight-1.20.1-1.2.8.jar");
        Path newJar = Path.of("libs", "FarmersDelight-1.20.1-1.3.3.jar");
        String descriptor = "getResults()Ljava/util/List;";

        assertTrue(methods(oldJar, RECIPE).contains(descriptor));
        assertTrue(methods(newJar, RECIPE).contains(descriptor));
    }

    @Test
    void supportedReleasesExposeTheSamePerOutputProbabilityContract() throws IOException {
        String chanceResult =
                "vectorwing/farmersdelight/common/crafting/ingredient/ChanceResult.class";
        for (String version : List.of("1.2.8", "1.3.3")) {
            Path jar = Path.of("libs", "FarmersDelight-1.20.1-" + version + ".jar");
            assertTrue(methods(jar, RECIPE).contains(
                    "getRollableResults()Lnet/minecraft/core/NonNullList;"), version);
            Set<String> resultMethods = methods(jar, chanceResult);
            assertTrue(resultMethods.contains(
                    "getStack()Lnet/minecraft/world/item/ItemStack;"), version);
            assertTrue(resultMethods.contains("getChance()F"), version);
        }
    }

    @Test
    void acceptsBothCuttingBoardAddItemReturnTypesWithoutLinkingToEither() throws IOException {
        Path oldJar = Path.of("libs", "FarmersDelight-1.20.1-1.2.8.jar");
        Path newJar = Path.of("libs", "FarmersDelight-1.20.1-1.3.3.jar");
        String prefix = "addItem(Lnet/minecraft/world/item/ItemStack;)";

        assertTrue(methods(oldJar, BLOCK_ENTITY).contains(prefix + "Z"));
        assertTrue(methods(newJar, BLOCK_ENTITY).contains(prefix
                + "Lnet/minecraft/world/item/ItemStack;"));
    }

    @Test
    void toolCanBeReusedUntilItsFinalDurabilityPoint() {
        ItemStack tool = new ItemStack(Items.IRON_SWORD);
        tool.setDamageValue(tool.getMaxDamage() - 2);

        assertTrue(CuttingBoardToolSemantics.canPerformOperations(tool, 2));
        assertFalse(CuttingBoardToolSemantics.canPerformOperations(tool, 3));

        RandomSource random = RandomSource.create(1L);
        CuttingBoardToolSemantics.damageOnce(tool, random, null);
        assertFalse(tool.isEmpty());
        assertEquals(tool.getMaxDamage() - 1, tool.getDamageValue());
        CuttingBoardToolSemantics.damageOnce(tool, random, null);
        assertTrue(tool.isEmpty());
    }

    @Test
    void unbreakableToolNeverTakesDamage() {
        ItemStack tool = new ItemStack(Items.IRON_SWORD);
        tool.getOrCreateTag().putBoolean("Unbreakable", true);

        RandomSource random = RandomSource.create(2L);
        for (int operation = 0; operation < 100; operation++) {
            CuttingBoardToolSemantics.damageOnce(tool, random, null);
        }

        assertFalse(tool.isEmpty());
        assertEquals(0, tool.getDamageValue());
        assertEquals(Integer.MAX_VALUE, CuttingBoardToolSemantics.remainingDurability(tool));
    }

    @Test
    void unbreakingUsesVanillaRandomDurabilityRolls() {
        ItemStack tool = new ItemStack(Items.IRON_SWORD);
        tool.enchant(Enchantments.UNBREAKING, 4);

        RandomSource random = RandomSource.create(3L);
        int operations = 100;
        for (int operation = 0; operation < operations; operation++) {
            CuttingBoardToolSemantics.damageOnce(tool, random, null);
        }

        assertFalse(tool.isEmpty());
        assertTrue(tool.getDamageValue() > 0);
        assertTrue(tool.getDamageValue() < operations,
                "Unbreaking IV should let vanilla ignore some durability losses");
    }

    private static Set<String> methods(Path jar, String entryName) throws IOException {
        Set<String> result = new HashSet<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            var entry = zip.getEntry(entryName);
            assertTrue(entry != null, () -> jar + " is missing " + entryName);
            try (var input = zip.getInputStream(entry)) {
                new ClassReader(input).accept(new ClassVisitor(Opcodes.ASM9) {
                    @Override
                    public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                     String signature, String[] exceptions) {
                        result.add(name + descriptor);
                        return null;
                    }
                }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            }
        }
        return result;
    }
}
