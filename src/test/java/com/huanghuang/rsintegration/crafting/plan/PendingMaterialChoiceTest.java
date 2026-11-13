package com.huanghuang.rsintegration.crafting.plan;

import com.huanghuang.rsintegration.crafting.OutputDestination;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PendingMaterialChoiceTest extends BootstrapTest {
    @TempDir Path directory;

    @Test
    void failedPreviewRestoresOnlyItsLockAndDoesNotOverwriteStoredPreferences() {
        Path path = directory.resolve("preferences.json");
        CraftingPlanPreferences.saveIngredientChoice(path, "planks", new ItemStack(Items.OAK_PLANKS));
        Map<String, ItemStack> locks = new LinkedHashMap<>();
        locks.put("planks", new ItemStack(Items.OAK_PLANKS));
        locks.put("other", new ItemStack(Items.DIAMOND));
        PendingMaterialChoice change = new PendingMaterialChoice(locks, "planks", new ItemStack(Items.BIRCH_PLANKS, 64));
        assertEquals(Items.BIRCH_PLANKS, locks.get("planks").getItem());
        assertEquals(1, locks.get("planks").getCount());
        assertEquals(Items.OAK_PLANKS, CraftingPlanPreferences.load(path).ingredientChoices().get("planks").getItem());
        change.rollback(locks);
        assertEquals(Items.OAK_PLANKS, locks.get("planks").getItem());
        assertEquals(Items.DIAMOND, locks.get("other").getItem());
    }

    @Test
    void automaticSelectionOverridesStoredPreferenceDuringPreviewAndCommitsAfterSuccess() {
        Path path = directory.resolve("preferences.json");
        CraftingPlanPreferences.saveOutputDestination(path, OutputDestination.PLAYER_INVENTORY);
        CraftingPlanPreferences.saveIngredientChoice(path, "planks", new ItemStack(Items.OAK_PLANKS));
        Map<String, ItemStack> locks = new LinkedHashMap<>();
        PendingMaterialChoice change = new PendingMaterialChoice(locks, "planks", ItemStack.EMPTY);
        var preview = change.preview(CraftingPlanPreferences.load(path));
        assertFalse(preview.ingredientChoices().containsKey("planks"));
        assertEquals(OutputDestination.PLAYER_INVENTORY, preview.outputDestination());
        assertTrue(CraftingPlanPreferences.load(path).ingredientChoices().containsKey("planks"));
        change.commit(path);
        assertFalse(CraftingPlanPreferences.load(path).ingredientChoices().containsKey("planks"));
    }
}
