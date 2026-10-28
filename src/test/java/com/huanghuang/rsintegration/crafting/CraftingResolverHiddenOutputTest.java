package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.mods.farmingforblockheads.MarketRecipeWrapper;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 隐藏产物反射扫描的边界：只允许把「与可见产物同物品」的带 NBT 字段当成真产物。
 */
class CraftingResolverHiddenOutputTest extends BootstrapTest {

    /** 模拟市场交易：真正的产物无 NBT，支付物（护手一类）带 {Damage:0} 之类的 NBT。 */
    @Test
    void marketExchangeDoesNotTreatPaymentAsHiddenOutput() {
        ItemStack compass = new ItemStack(Items.COMPASS);
        ItemStack payment = taggedSword();
        MarketRecipeWrapper recipe = new MarketRecipeWrapper(
                UUID.nameUUIDFromBytes("market-payment".getBytes()), compass, payment);

        ItemStack declared = CraftingResolver.resolveDeclaredOutput(recipe, compass.copy(), null);

        assertEquals(Items.COMPASS, declared.getItem(),
                "市场配方的产物必须是条目里写死的可见结果，不能是支付物");
        assertFalse(declared.hasTag(), "指南针本身没有 NBT，声明里也不应出现支付物的 NBT");
    }

    /** 非交换配方的材料字段同样带 NBT 时，物品不同就不该被当成产物。 */
    @Test
    void taggedCatalystFieldIsIgnoredWhenItemDiffers() {
        ItemStack compass = new ItemStack(Items.COMPASS);
        FakeRecipe recipe = new FakeRecipe(
                new ResourceLocation("test", "hidden_output/catalyst"), compass, taggedSword());

        ItemStack declared = CraftingResolver.resolveDeclaredOutput(recipe, compass.copy(), null);

        assertEquals(Items.COMPASS, declared.getItem());
        assertTrue(CraftingResolver.extractHiddenOutput(recipe, compass.copy()).isEmpty(),
                "物品与可见产物不同，隐藏扫描必须放弃这个字段");
    }

    /** 缓存过的错误值在物品不一致时不能复用。 */
    @Test
    void cachedHiddenOutputIsIgnoredWhenItemDiffers() {
        ItemStack compass = new ItemStack(Items.COMPASS);
        FakeRecipe recipe = new FakeRecipe(
                new ResourceLocation("test", "hidden_output/cache"), compass, taggedSword());

        // 先在没有可见产物身份的情况下扫描一次，把支付物写进缓存（旧行为）。
        assertEquals(Items.DIAMOND_SWORD, CraftingResolver.extractHiddenOutput(recipe).getItem());

        assertTrue(CraftingResolver.extractHiddenOutput(recipe, compass.copy()).isEmpty(),
                "缓存里的护手与可见产物不是同物品，必须重新判断而不是直接返回");
    }

    /** 同一物品的带 NBT 字段仍然是真产物（TACZ / Applied Armorer 这类配方的既有行为）。 */
    @Test
    void sameItemTaggedResultIsStillFound() {
        ItemStack plain = new ItemStack(Items.COMPASS);
        ItemStack tagged = new ItemStack(Items.COMPASS);
        tagged.getOrCreateTag().putInt("CustomModelData", 7);
        FakeRecipe recipe = new FakeRecipe(
                new ResourceLocation("test", "hidden_output/same_item"), plain, tagged);

        ItemStack declared = CraftingResolver.resolveDeclaredOutput(recipe, plain.copy(), null);

        assertEquals(Items.COMPASS, declared.getItem());
        assertTrue(declared.hasTag(), "同物品的隐藏栈才是真产物");
        assertEquals(7, declared.getTag().getInt("CustomModelData"));
    }

    private static ItemStack taggedSword() {
        ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
        sword.getOrCreateTag().putInt("Damage", 0);
        return sword;
    }

    /** 带一个可见产物字段和一个「材料/支付物」字段的最小配方。 */
    private static final class FakeRecipe implements Recipe<Container> {
        private final ResourceLocation id;
        private final ItemStack result;
        private final ItemStack extra;

        FakeRecipe(ResourceLocation id, ItemStack result, ItemStack extra) {
            this.id = id;
            this.result = result;
            this.extra = extra;
        }

        @Override
        public boolean matches(Container container, Level level) {
            return false;
        }

        @Override
        public ItemStack assemble(Container container, RegistryAccess access) {
            return result.copy();
        }

        @Override
        public boolean canCraftInDimensions(int width, int height) {
            return true;
        }

        @Override
        public ItemStack getResultItem(RegistryAccess access) {
            return result.copy();
        }

        @Override
        public ResourceLocation getId() {
            return id;
        }

        @Override
        public RecipeSerializer<?> getSerializer() {
            return null;
        }

        @Override
        public RecipeType<?> getType() {
            return null;
        }

        @Override
        public NonNullList<Ingredient> getIngredients() {
            return NonNullList.withSize(1, Ingredient.EMPTY);
        }
    }
}
