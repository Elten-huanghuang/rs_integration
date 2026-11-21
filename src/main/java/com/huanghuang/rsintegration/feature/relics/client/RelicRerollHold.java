package com.huanghuang.rsintegration.feature.relics.client;

import java.util.Map;

final class RelicRerollHold {
    private static final int RESPONSE_TIMEOUT_TICKS = 100;

    enum Action { NONE, REROLL, FULL_QUALITY, UNAVAILABLE, TIMED_OUT }

    record Roll(Map<String, Double> stats, int quality, int maxQuality, int experienceLevel) {}

    private boolean held;
    private boolean queued;
    private boolean suppressRepeat;
    private Map<String, Double> previousStats;
    private int expectedExperienceLevel;
    private int waitingTicks;

    boolean press() {
        if (held) return false;
        held = true;
        queued = true;
        suppressRepeat = false;
        return true;
    }

    void release() {
        // 同步期间的一次轻按仍可在收到结果后执行。
        held = false;
    }

    void cancel() {
        queued = false;
        suppressRepeat = held;
    }

    void clearTarget() {
        cancel();
        previousStats = null;
    }

    boolean isHeld() {
        return held;
    }

    boolean isActive() {
        return canRequest() || previousStats != null;
    }

    Action tick(Roll roll, boolean canReroll, int cost) {
        if (canRequest() && roll.maxQuality() > 0 && roll.quality() >= roll.maxQuality()) {
            return stop(Action.FULL_QUALITY);
        }
        if (previousStats != null) {
            // 槽位与经验会分别同步，必须两者都到达才继续。
            if (previousStats.equals(roll.stats()) || roll.experienceLevel() > expectedExperienceLevel) {
                if (++waitingTicks < RESPONSE_TIMEOUT_TICKS) return Action.NONE;
                previousStats = null;
                return canRequest() ? stop(Action.TIMED_OUT) : Action.NONE;
            }
            previousStats = null;
        }
        if (!canRequest()) return Action.NONE;
        if (!canReroll || cost <= 0) return stop(Action.UNAVAILABLE);

        queued = false;
        previousStats = Map.copyOf(roll.stats());
        expectedExperienceLevel = roll.experienceLevel() - cost;
        waitingTicks = 0;
        return Action.REROLL;
    }

    private Action stop(Action action) {
        cancel();
        return action;
    }

    private boolean canRequest() {
        return queued || (held && !suppressRepeat);
    }
}
