package com.huanghuang.rsintegration.mods.tacz;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaczWorkbenchCompatibilityTest extends BootstrapTest {

    private static final ResourceLocation Z750 = new ResourceLocation("pgp", "gun/z750");

    @Test
    void readsRootBlockId() {
        ItemStack stack = new ItemStack(Items.STONE);
        stack.getOrCreateTag().putString("BlockId", "ocle:fzj_table");

        assertEquals(new ResourceLocation("ocle", "fzj_table"),
                TaczWorkbenchCompatibility.blockId(stack));
    }

    @Test
    void readsBlockEntityTagBlockId() {
        ItemStack stack = new ItemStack(Items.STONE);
        CompoundTag blockEntityTag = new CompoundTag();
        blockEntityTag.putString("BlockId", "pgp:printer");
        stack.getOrCreateTag().put("BlockEntityTag", blockEntityTag);

        assertEquals(new ResourceLocation("pgp", "printer"),
                TaczWorkbenchCompatibility.blockId(stack));
    }

    @Test
    void missingOrMalformedBlockIdIsRejected() {
        ItemStack missing = new ItemStack(Items.STONE);
        ItemStack malformed = new ItemStack(Items.STONE);
        malformed.getOrCreateTag().putString("BlockId", "not a resource id");

        assertFalse(TaczWorkbenchCompatibility.accepts(missing, Z750, (block, recipe) -> true));
        assertFalse(TaczWorkbenchCompatibility.accepts(malformed, Z750, (block, recipe) -> true));
    }

    @Test
    void compatibilityUsesBothWorkbenchAndFullRecipeId() {
        ItemStack stack = new ItemStack(Items.STONE);
        stack.getOrCreateTag().putString("BlockId", "ocle:fzj_table");
        AtomicReference<ResourceLocation> observedBlock = new AtomicReference<>();
        AtomicReference<ResourceLocation> observedRecipe = new AtomicReference<>();

        boolean accepted = TaczWorkbenchCompatibility.accepts(stack, Z750, (block, recipe) -> {
            observedBlock.set(block);
            observedRecipe.set(recipe);
            return block.equals(new ResourceLocation("ocle", "fzj_table")) && recipe.equals(Z750);
        });

        assertTrue(accepted);
        assertEquals(new ResourceLocation("ocle", "fzj_table"), observedBlock.get());
        assertEquals(Z750, observedRecipe.get());
    }
}
