package com.huanghuang.rsintegration.crafting.plan;

import net.minecraft.Util;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * Controls staggered card-entry animations for the crafting plan screen.
 * Each step card slides in from the right with a 50ms delay between cards.
 * Animation duration: 300ms per card using ease-out cubic interpolation.
 *
 * <h3>Wiring status</h3>
 * <b>Wired</b> — {@code CraftingPlanScreen} uses this controller via
 * {@link PlanRenderEngine#animation()}.  The screen calls
 * {@link #start(int)} in {@code init()} and {@link #getAlpha(int)} /
 * {@link #getSlideOffset(int, int)} in its render loop, replacing the
 * previous inline {@code cardFade} formula.
 */
@OnlyIn(Dist.CLIENT)
public final class PlanAnimationController {
    private static final long ENTRY_DURATION_MS = 300;
    private static final long STAGGER_DELAY_MS = 50;

    private long animationStartTime = -1;
    private int cardCount;

    /** Start or restart the animation for {@code cardCount} cards. */
    public void start(int cardCount) {
        this.animationStartTime = Util.getMillis();
        this.cardCount = Math.max(0, cardCount);
    }

    /** Reset the animation to its completed state. */
    public void reset() {
        this.animationStartTime = -1;
        this.cardCount = 0;
    }

    /**
     * Get the raw animation progress (0.0 to 1.0) for a card at the given index.
     * Returns 1.0 if the animation has not been started.
     */
    public float getProgress(int cardIndex) {
        if (animationStartTime < 0) return 1.0f;
        long elapsed = Util.getMillis() - animationStartTime;
        long cardStart = cardIndex * STAGGER_DELAY_MS;
        if (elapsed < cardStart) return 0.0f;
        float cardProgress = (float) (elapsed - cardStart) / (float) ENTRY_DURATION_MS;
        return Math.min(1.0f, cardProgress);
    }

    /**
     * Eased X offset for slide-in effect.
     * @param maxOffset the maximum pixel offset (cards start this far to the right)
     * @return the eased offset in pixels (0 = fully slid in)
     */
    public int getSlideOffset(int cardIndex, int maxOffset) {
        float progress = getProgress(cardIndex);
        // Ease-out cubic: 1 - (1-t)^3
        float eased = 1.0f - (1.0f - progress) * (1.0f - progress) * (1.0f - progress);
        return (int) ((1.0f - eased) * maxOffset);
    }

    /**
     * Alpha multiplier for fade-in effect.
     * Fades in over the first 50% of the animation duration.
     */
    public float getAlpha(int cardIndex) {
        float progress = getProgress(cardIndex);
        return Math.min(1.0f, progress * 2.0f);
    }

    public boolean isActive() {
        return animationStartTime >= 0;
    }

    /** Returns true when all cards have completed their animations. */
    public boolean isFinished() {
        if (animationStartTime < 0) return true;
        return getProgress(Math.max(0, cardCount - 1)) >= 1.0f;
    }
}
