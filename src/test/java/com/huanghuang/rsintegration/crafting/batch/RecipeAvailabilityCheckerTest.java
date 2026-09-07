package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.crafting.CraftingResolver.StackKey;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.availability.MaterialAvailability;
import com.huanghuang.rsintegration.crafting.availability.RecipeAvailabilityKey;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraftforge.common.crafting.StrictNBTIngredient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RecipeAvailabilityCheckerTest extends BootstrapTest {
    @Test void repeatedSlotsAndQuantitiesCannotReuseOneItem() {
        var specs = List.of(new IngredientSpec(Ingredient.of(Items.DIAMOND), 2),
                new IngredientSpec(Ingredient.of(Items.DIAMOND), 1));
        var key = new StackKey(Items.DIAMOND, null);
        assertEquals(MaterialAvailability.MISSING, RecipeAvailabilityChecker.evaluate(specs, Map.of(key, 2)));
        assertEquals(MaterialAvailability.READY, RecipeAvailabilityChecker.evaluate(specs, Map.of(key, 3)));
    }

    @Test void alternativesDoNotStealSpecificInputs() {
        var specs = List.of(new IngredientSpec(Ingredient.of(Items.IRON_INGOT, Items.GOLD_INGOT), 1),
                new IngredientSpec(Ingredient.of(Items.IRON_INGOT), 1));
        assertEquals(MaterialAvailability.READY, RecipeAvailabilityChecker.evaluate(specs,
                Map.of(new StackKey(Items.IRON_INGOT, null), 1, new StackKey(Items.GOLD_INGOT, null), 1)));
    }

    @Test void preservesNbtAndUnknownInputs() {
        ItemStack required = new ItemStack(Items.ENCHANTED_BOOK);
        required.getOrCreateTag().putInt("level", 2);
        var specs = List.of(new IngredientSpec(StrictNBTIngredient.of(required), 1));
        assertEquals(MaterialAvailability.MISSING, RecipeAvailabilityChecker.evaluate(specs,
                Map.of(new StackKey(Items.ENCHANTED_BOOK, null), 1)));
        assertEquals(MaterialAvailability.UNKNOWN, RecipeAvailabilityChecker.evaluate(null, Map.of()));
        assertEquals(MaterialAvailability.UNKNOWN, RecipeAvailabilityChecker.evaluate(List.of(), Map.of()));
    }

    @Test void packetsPreserveVariantMachineAndRequestTicket() {
        ItemStack output = new ItemStack(Items.ENCHANTED_BOOK);
        output.getOrCreateTag().putInt("level", 2);
        var key = RecipeAvailabilityKey.of(new ResourceLocation("test", "recipe"),
                new ResourceLocation("minecraft", "overworld"), new BlockPos(1, 2, 3),
                new ItemStack(Items.BOOK), output);
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            var request = new RecipeAvailabilityRequestPacket(key, 123);
            request.encode(buf);
            assertEquals(request, RecipeAvailabilityRequestPacket.decode(buf));
            buf.clear();
            var response = new RecipeAvailabilityResultPacket(key, 123, MaterialAvailability.READY);
            response.encode(buf);
            assertEquals(response, RecipeAvailabilityResultPacket.decode(buf));
        } finally { buf.release(); }
    }
}
