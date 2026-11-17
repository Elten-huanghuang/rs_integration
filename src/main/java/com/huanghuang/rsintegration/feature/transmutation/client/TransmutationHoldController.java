package com.huanghuang.rsintegration.feature.transmutation.client;

import com.huanghuang.rsintegration.feature.transmutation.TransmutationTableReroll;
import com.huanghuang.rsintegration.feature.transmutation.TransmutationTableReroll.Outcome;

/** 一次只允许一个请求在途；停止后必须松键才能重新启动。 */
public final class TransmutationHoldController {
    private static final int RESPONSE_TIMEOUT_TICKS = 100;

    private boolean held;
    private boolean running;
    private boolean waiting;
    private int previousState;
    private int waitingTicks;
    private Outcome stoppedWith;

    public boolean press() {
        if (held) return false;
        held = true;
        running = true;
        waiting = false;
        stoppedWith = null;
        return true;
    }

    public void release() {
        held = false;
        cancel();
    }

    public void cancel() {
        running = false;
        waiting = false;
        stoppedWith = null;
    }

    public Outcome stoppedWith() {
        return stoppedWith;
    }

    public boolean nextRequest(int state) {
        if (!running) return false;
        if (waiting) {
            if (state == previousState) {
                if (++waitingTicks >= RESPONSE_TIMEOUT_TICKS) stop(Outcome.UNAVAILABLE);
                return false;
            }
            Outcome outcome = TransmutationTableReroll.outcome(state);
            if (outcome != Outcome.READY) {
                stop(outcome);
                return false;
            }
        }
        previousState = state;
        waitingTicks = 0;
        waiting = true;
        return true;
    }

    private void stop(Outcome outcome) {
        running = false;
        waiting = false;
        stoppedWith = outcome;
    }
}
