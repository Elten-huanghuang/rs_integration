package com.huanghuang.rsintegration.compat.ftbquests;

/** Why an FTB Quests quest can or cannot be handled by recursive submission. */
public enum QuestSubmissionEligibility {
    ELIGIBLE,
    QUEST_ALREADY_COMPLETED,
    QUEST_NOT_STARTABLE,
    QUEST_HIDDEN,
    SEQUENTIAL_TASKS_UNSUPPORTED,
    NO_REMAINING_ITEM_TASKS,
    NON_ITEM_TASK_REQUIRED,
    NON_CONSUMING_TASK_UNSUPPORTED,
    CRAFTING_ONLY_TASK_UNSUPPORTED,
    TASK_SCREEN_ONLY,
    INVALID_ITEM_TASK,
    TEAM_DATA_UNAVAILABLE,
    TEAM_DATA_LOCKED,
    QUEST_FILE_UNAVAILABLE
}
