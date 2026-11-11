package com.huanghuang.rsintegration.crafting.planning;

import java.util.UUID;

/** 玩家与单次预览共同标识任务；不能仅以玩家覆盖另一个正在计算的任务。 */
public record PlanningRequestKey(UUID playerId, long generation) {}
