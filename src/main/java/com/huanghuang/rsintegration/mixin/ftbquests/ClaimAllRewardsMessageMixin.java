package com.huanghuang.rsintegration.mixin.ftbquests;

import com.huanghuang.rsintegration.compat.ftbquests.FtbQuestRewardDropContext;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.ftb.mods.ftbquests.net.ClaimAllRewardsMessage;
import dev.ftb.mods.ftbquests.quest.TeamData;
import dev.ftb.mods.ftbquests.quest.reward.Reward;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Scopes item drops to FTB's native "claim all rewards" packet only. */
@Mixin(value = ClaimAllRewardsMessage.class, remap = false)
public abstract class ClaimAllRewardsMessageMixin {

    @WrapOperation(
            method = "lambda$handle$1",
            at = @At(value = "INVOKE",
                    target = "Ldev/ftb/mods/ftbquests/quest/TeamData;claimReward(" +
                            "Lnet/minecraft/server/level/ServerPlayer;" +
                            "Ldev/ftb/mods/ftbquests/quest/reward/Reward;Z)V"),
            remap = false)
    private static void rsi$scopeClaimAllReward(TeamData data, ServerPlayer player, Reward reward,
                                                 boolean notify, Operation<Void> original) {
        try (FtbQuestRewardDropContext.Scope ignored = FtbQuestRewardDropContext.activate()) {
            original.call(data, player, reward, notify);
        }
    }
}
