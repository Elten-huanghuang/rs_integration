package com.huanghuang.rsintegration.mixin.ftbquests;

import dev.ftb.mods.ftbquests.quest.TeamData;
import dev.ftb.mods.ftbquests.quest.task.Task;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Exposes FTB Quests' protected sequential-task guard to the native fallback. */
@Mixin(value = Task.class, remap = false)
public interface ItemTaskSequenceAccessor {
    @Invoker("checkTaskSequence")
    boolean rsi$checkTaskSequence(TeamData data);
}
