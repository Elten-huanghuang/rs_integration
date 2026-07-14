package com.huanghuang.rsintegration.crafting;

import javax.annotation.Nullable;
import java.util.UUID;

/** Immutable progress snapshot sent from server to client. */
public record CraftProgressSnapshot(
        UUID craftId,
        int sequence,
        byte chainState,   // 0=EXECUTING, 1=STOPPING
        int completedNodes,
        int totalNodes,
        int runningNodes,
        @Nullable String failedStep
) {
    public static final byte STATE_EXECUTING = 0;
    public static final byte STATE_STOPPING = 1;
    public static final int TERMINAL_SEQUENCE = Integer.MAX_VALUE;

    public boolean isTerminal() {
        return sequence == TERMINAL_SEQUENCE || chainState == STATE_STOPPING;
    }
}
