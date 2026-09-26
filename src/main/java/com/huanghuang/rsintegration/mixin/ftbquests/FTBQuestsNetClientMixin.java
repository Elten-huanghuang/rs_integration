package com.huanghuang.rsintegration.mixin.ftbquests;
import java.lang.reflect.Method;

import com.huanghuang.rsintegration.compat.ftbquests.client.FtbQuestJeiRuntime;
import dev.ftb.mods.ftbquests.client.FTBQuestsNetClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Coalesces FTB Quests client updates into one deferred JEI refresh. */
@Mixin(value = FTBQuestsNetClient.class, remap = false)
public abstract class FTBQuestsNetClientMixin {

    @Inject(method = {
            "syncTeamData",
            "teamDataChanged",
            "updateTaskProgress",
            "objectStarted",
            "objectCompleted",
            "syncLock",
            "claimReward",
            "resetReward",
            "syncRewardBlocking",
            "createObject",
            "deleteObject",
            "editObject"
    }, at = @At("RETURN"))
    private static void rsi$requestQuestRecipeRefresh(CallbackInfo ci) {
        FtbQuestJeiRuntime.requestRefresh();
        FtbQuestJeiRuntime.requestQuestScreenRefresh();
    }
}
