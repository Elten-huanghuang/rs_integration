package com.huanghuang.rsintegration.feature.ftbquests.client;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.feature.ftbquests.QuestSubmissionSnapshot;
import mezz.jei.api.recipe.RecipeType;

public final class FtbQuestSubmissionRecipe {

    public static final RecipeType<QuestSubmissionSnapshot> TYPE = RecipeType.create(
            RSIntegrationMod.MOD_ID, "ftb_quest_submission", QuestSubmissionSnapshot.class);

    private FtbQuestSubmissionRecipe() {}
}
