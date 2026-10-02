package com.huanghuang.rsintegration.crafting.plan;

import com.huanghuang.rsintegration.crafting.plan.RecipeCandidatePickerModel.Candidate;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RecipeCandidatePickerModelTest {
    @Test
    void thousandsOfCandidatesRemainReachableIncludingTheLastSelectedRecipe() {
        List<Candidate> candidates = new ArrayList<>();
        for (int i = 0; i < 2347; i++) candidates.add(candidate(i));
        var model = new RecipeCandidatePickerModel(candidates, id(2346));
        model.setVisibleRows(3);
        assertEquals(2347, model.filtered().size());
        assertEquals(id(2346), model.active().recipeId());
        assertEquals(2344, model.firstRow());
        model.scroll(-10000);
        assertEquals(0, model.firstRow());
        model.scroll(10000);
        assertEquals(2344, model.firstRow());
    }

    @Test
    void searchesSpellTooltipMachineAndRecipeIdWithAllTermsRequired() {
        Candidate fire = new Candidate(id(1), "irons_spellbooks_alchemist_cauldron", "火球术 · 等级 3",
                "炼金锅", "传奇卷轴 FIREBALL 水 250 mB");
        Candidate ice = new Candidate(id(2), "irons_spellbooks_alchemist_cauldron", "冰霜术 · 等级 5",
                "炼金锅", "稀有卷轴 ICE 水 250 mB");
        var model = new RecipeCandidatePickerModel(List.of(fire, ice), id(2));
        model.search("  fireBALL   炼金锅 ");
        assertEquals(List.of(fire), model.filtered());
        model.search("冰霜 5");
        assertEquals(List.of(ice), model.filtered());
        model.search("test:recipe/1");
        assertEquals(List.of(fire), model.filtered());
    }

    @Test
    void emptySearchResultCannotSelectOrScrollAndClearingSearchRestoresCandidates() {
        var model = new RecipeCandidatePickerModel(List.of(candidate(0), candidate(1)), id(0));
        model.search("不存在的法术");
        model.move(1);
        model.scroll(99);
        assertNull(model.active());
        assertEquals(0, model.firstRow());
        assertEquals(2, model.total());
        model.search("");
        assertEquals(2, model.filtered().size());
        assertNotNull(model.active());
    }

    @Test
    void keyboardNavigationRevealsOffscreenRecipesAndWrapsSafely() {
        var model = new RecipeCandidatePickerModel(List.of(candidate(0), candidate(1), candidate(2),
                candidate(3), candidate(4)), id(0));
        model.setVisibleRows(2);
        model.move(3);
        assertEquals(id(3), model.active().recipeId());
        assertEquals(2, model.firstRow());
        model.move(1);
        assertEquals(3, model.firstRow());
        model.move(1);
        assertEquals(id(0), model.active().recipeId());
        assertEquals(0, model.firstRow());
        model.move(-1);
        assertEquals(id(4), model.active().recipeId());
        assertEquals(3, model.firstRow());
    }

    @Test
    void filteringKeepsCursorOnTheSameRecipeWhenItStillMatches() {
        var model = new RecipeCandidatePickerModel(List.of(candidate(0), candidate(1), candidate(2)), id(2));
        model.search("recipe");
        assertEquals(id(2), model.active().recipeId());
        model.search("recipe/1");
        assertEquals(id(1), model.active().recipeId());
    }

    @Test
    void duplicateRecipeIdsKeepTheirFirstMetadataWithoutLosingDistinctNbtRecipes() {
        Candidate duplicate = new Candidate(id(0), "wrong_machine", "other", "other", "");
        var model = new RecipeCandidatePickerModel(List.of(candidate(0), duplicate, candidate(1)), id(0));
        assertEquals(2, model.total());
        assertEquals("炼金锅", model.active().machine());
    }

    @Test
    void changingVisibleRowCapacityKeepsCursorAndClampsScroll() {
        var model = new RecipeCandidatePickerModel(List.of(candidate(0), candidate(1), candidate(2)), id(2));
        model.setVisibleRows(10);
        assertEquals(0, model.firstRow());
        assertEquals(id(2), model.active().recipeId());
        model.setVisibleRows(0);
        assertEquals(1, model.visibleRows());
        assertEquals(2, model.firstRow());
    }

    @Test
    void rightPanelAndRecipeRowsStayInsideScreenAcrossGuiSizes() {
        for (int[] screen : List.of(new int[]{180, 120}, new int[]{320, 180},
                new int[]{480, 270}, new int[]{854, 480}, new int[]{1920, 1080})) {
            var bounds = RecipeCandidatePickerModel.layout(screen[0], screen[1], 40);
            assertTrue(bounds.x() >= 0);
            assertTrue(bounds.y() >= 0);
            assertTrue(bounds.x() + bounds.width() <= screen[0]);
            assertTrue(bounds.y() + bounds.height() <= screen[1]);
            assertTrue(bounds.rowHeight() > 0);
            assertTrue(bounds.rows() > 0);
            assertEquals(RecipeCandidatePickerModel.HEADER_HEIGHT + bounds.rowHeight() * bounds.rows()
                    + RecipeCandidatePickerModel.FOOTER_HEIGHT, bounds.height());
            assertTrue(bounds.width() <= 204);
            assertTrue(bounds.width() <= screen[0] * 40 / 100);
            assertTrue(bounds.rows() <= 3);
            assertTrue(bounds.contentRight() + 8 <= bounds.x());
            assertTrue(bounds.contains(bounds.x(), bounds.y()));
            assertFalse(bounds.contains(bounds.x() + bounds.width(), bounds.y()));
        }
    }

    @Test
    void screenshotSizedGuiKeepsMostOfThePlanVisibleAndLeavesTheBottomActionsClear() {
        var bounds = RecipeCandidatePickerModel.layout(656, 350, 40);
        assertTrue(bounds.width() < 656 / 3);
        assertTrue(bounds.contentRight() - 20 > (656 - 40) * 0.7);
        assertTrue(bounds.y() + bounds.height() <= 350 - 84);
        assertEquals(2, bounds.rows());
    }

    private static ResourceLocation id(int number) { return new ResourceLocation("test", "recipe/" + number); }
    private static Candidate candidate(int number) {
        return new Candidate(id(number), "irons_spellbooks_alchemist_cauldron", "卷轴 " + number, "炼金锅", "");
    }
}
