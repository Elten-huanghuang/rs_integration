package com.huanghuang.rsintegration.compat.ftbquests.client;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.compat.ftbquests.QuestSubmissionSnapshot;
import mezz.jei.api.recipe.RecipeType;

public final class FtbQuestSubmissionRecipe {

    public static final RecipeType<QuestSubmissionSnapshot> TYPE = RecipeType.create(
            RSIntegrationMod.MOD_ID, "ftb_quest_submission", QuestSubmissionSnapshot.class);

    private FtbQuestSubmissionRecipe() {}
}
