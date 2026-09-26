package com.huanghuang.rsintegration.mixin.ftbquests;
import java.lang.reflect.Method;

import com.huanghuang.rsintegration.compat.ftbquests.FtbQuestRewardDropContext;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.ftb.mods.ftbquests.quest.reward.ItemReward;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Converts only FTB Quests' claim-all item rewards into normal world drops. */
@Mixin(value = ItemReward.class, remap = false)
public abstract class ItemRewardMixin {

    @WrapOperation(
            method = "claim",
            at = @At(value = "INVOKE",
                    target = "Ldev/architectury/hooks/item/ItemStackHooks;giveItem(" +
                            "Lnet/minecraft/server/level/ServerPlayer;" +
                            "Lnet/minecraft/world/item/ItemStack;)V"),
            remap = false)
    private static void rsi$dropReward(ServerPlayer player, ItemStack stack,
                                       Operation<Void> original) {
        if (!FtbQuestRewardDropContext.isActive() || stack.isEmpty()) {
            original.call(player, stack);
            return;
        }

        ItemEntity drop = new ItemEntity(player.level(), player.getX(),
                player.getY() + 0.5D, player.getZ(), stack.copy());
        drop.setDeltaMovement(0.0D, 0.1D, 0.0D);
        if (!player.level().addFreshEntity(drop)) {
            // Do not lose a reward if the level rejects the entity.
            original.call(player, stack);
        }
    }
}
