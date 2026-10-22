package com.huanghuang.rsintegration.crafting.plan;

import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanTreeMouseGestureTest {
    private final Object node = new Object();
    private final PlanTreeMouseGesture<Object> gesture = new PlanTreeMouseGesture<>();

    @Test
    void rightClickNeverReturnsANodeAction() {
        assertTrue(gesture.begin(20, 30, GLFW.GLFW_MOUSE_BUTTON_RIGHT, node));
        assertTrue(gesture.captures(GLFW.GLFW_MOUSE_BUTTON_RIGHT));

        assertNull(gesture.release(20, 30, GLFW.GLFW_MOUSE_BUTTON_RIGHT, node));
        assertFalse(gesture.captures(GLFW.GLFW_MOUSE_BUTTON_RIGHT));
    }

    @Test
    void middleClickReturnsTheNodeOnlyOnRelease() {
        assertTrue(gesture.begin(20, 30, GLFW.GLFW_MOUSE_BUTTON_MIDDLE, node));
        assertTrue(gesture.captures(GLFW.GLFW_MOUSE_BUTTON_MIDDLE));

        assertSame(node, gesture.release(20, 30, GLFW.GLFW_MOUSE_BUTTON_MIDDLE, node));
        assertFalse(gesture.captures(GLFW.GLFW_MOUSE_BUTTON_MIDDLE));
        assertNull(gesture.release(20, 30, GLFW.GLFW_MOUSE_BUTTON_MIDDLE, node));
    }

    @Test
    void smallMiddleClickJitterDoesNotPan() {
        gesture.begin(20, 30, GLFW.GLFW_MOUSE_BUTTON_MIDDLE, node);

        assertEquals(new PlanTreeMouseGesture.PanDelta(0, 0),
                gesture.drag(22, 32, GLFW.GLFW_MOUSE_BUTTON_MIDDLE));
        assertSame(node, gesture.release(22, 32, GLFW.GLFW_MOUSE_BUTTON_MIDDLE, node));
    }

    @Test
    void exactlyFourPixelsStillCountsAsAClick() {
        gesture.begin(20, 30, GLFW.GLFW_MOUSE_BUTTON_MIDDLE, node);

        assertEquals(new PlanTreeMouseGesture.PanDelta(0, 0),
                gesture.drag(24, 30, GLFW.GLFW_MOUSE_BUTTON_MIDDLE));
        assertSame(node, gesture.release(24, 30, GLFW.GLFW_MOUSE_BUTTON_MIDDLE, node));
    }

    @Test
    void crossingTheThresholdPansTheFullDistanceFromThePress() {
        gesture.begin(20, 30, GLFW.GLFW_MOUSE_BUTTON_RIGHT, node);
        gesture.drag(22, 30, GLFW.GLFW_MOUSE_BUTTON_RIGHT);

        assertEquals(new PlanTreeMouseGesture.PanDelta(5, 0),
                gesture.drag(25, 30, GLFW.GLFW_MOUSE_BUTTON_RIGHT));
        assertEquals(new PlanTreeMouseGesture.PanDelta(3, -2),
                gesture.drag(28, 28, GLFW.GLFW_MOUSE_BUTTON_RIGHT));
    }

    @Test
    void diagonalMovementUsesDistanceInsteadOfAnAxisThreshold() {
        gesture.begin(20, 30, GLFW.GLFW_MOUSE_BUTTON_MIDDLE, node);

        assertEquals(new PlanTreeMouseGesture.PanDelta(3, 3),
                gesture.drag(23, 33, GLFW.GLFW_MOUSE_BUTTON_MIDDLE));
        assertNull(gesture.release(23, 33, GLFW.GLFW_MOUSE_BUTTON_MIDDLE, node));
    }

    @Test
    void middleDragDoesNotOpenTheNodeAfterReturningToThePressPosition() {
        gesture.begin(20, 30, GLFW.GLFW_MOUSE_BUTTON_MIDDLE, node);
        gesture.drag(26, 30, GLFW.GLFW_MOUSE_BUTTON_MIDDLE);

        assertEquals(new PlanTreeMouseGesture.PanDelta(-6, 0),
                gesture.drag(20, 30, GLFW.GLFW_MOUSE_BUTTON_MIDDLE));
        assertNull(gesture.release(20, 30, GLFW.GLFW_MOUSE_BUTTON_MIDDLE, node));
    }

    @Test
    void releaseFarFromThePressCannotClickEvenWithoutADragEvent() {
        gesture.begin(20, 30, GLFW.GLFW_MOUSE_BUTTON_MIDDLE, node);

        assertNull(gesture.release(20, 35, GLFW.GLFW_MOUSE_BUTTON_MIDDLE, node));
    }

    @Test
    void releasingOverAnotherNodeDoesNotOpenThePressedNode() {
        gesture.begin(20, 30, GLFW.GLFW_MOUSE_BUTTON_MIDDLE, node);

        assertNull(gesture.release(21, 30, GLFW.GLFW_MOUSE_BUTTON_MIDDLE, new Object()));
    }

    @Test
    void releasingOutsideTheCanvasOrOverAnOverlayDoesNotOpenTheNode() {
        gesture.begin(20, 30, GLFW.GLFW_MOUSE_BUTTON_MIDDLE, node);

        assertNull(gesture.release(20, 30, GLFW.GLFW_MOUSE_BUTTON_MIDDLE, null));
    }

    @Test
    void blankCanvasCanPanWithoutANodeAction() {
        gesture.begin(20, 30, GLFW.GLFW_MOUSE_BUTTON_MIDDLE, null);

        assertEquals(new PlanTreeMouseGesture.PanDelta(10, -10),
                gesture.drag(30, 20, GLFW.GLFW_MOUSE_BUTTON_MIDDLE));
        assertNull(gesture.release(30, 20, GLFW.GLFW_MOUSE_BUTTON_MIDDLE, null));
    }

    @Test
    void dragWithoutACapturedPressDoesNotPan() {
        assertEquals(new PlanTreeMouseGesture.PanDelta(0, 0),
                gesture.drag(100, 100, GLFW.GLFW_MOUSE_BUTTON_RIGHT));
        assertEquals(new PlanTreeMouseGesture.PanDelta(0, 0),
                gesture.drag(100, 100, GLFW.GLFW_MOUSE_BUTTON_MIDDLE));
    }

    @Test
    void unrelatedButtonsCannotMoveOrReleaseTheGesture() {
        gesture.begin(20, 30, GLFW.GLFW_MOUSE_BUTTON_MIDDLE, node);

        assertEquals(new PlanTreeMouseGesture.PanDelta(0, 0),
                gesture.drag(100, 100, GLFW.GLFW_MOUSE_BUTTON_LEFT));
        assertNull(gesture.release(100, 100, GLFW.GLFW_MOUSE_BUTTON_RIGHT, node));
        assertTrue(gesture.captures(GLFW.GLFW_MOUSE_BUTTON_MIDDLE));
        assertSame(node, gesture.release(20, 30, GLFW.GLFW_MOUSE_BUTTON_MIDDLE, node));
    }

    @Test
    void leftClickCannotBeginAPanGesture() {
        assertFalse(gesture.begin(20, 30, GLFW.GLFW_MOUSE_BUTTON_LEFT, node));
        assertFalse(gesture.captures(GLFW.GLFW_MOUSE_BUTTON_LEFT));
        assertEquals(new PlanTreeMouseGesture.PanDelta(0, 0),
                gesture.drag(100, 100, GLFW.GLFW_MOUSE_BUTTON_LEFT));
    }

    @Test
    void cancelStopsAnActiveDragAndDiscardsTheClickTarget() {
        gesture.begin(20, 30, GLFW.GLFW_MOUSE_BUTTON_MIDDLE, node);
        gesture.drag(30, 30, GLFW.GLFW_MOUSE_BUTTON_MIDDLE);

        gesture.cancel();

        assertFalse(gesture.captures(GLFW.GLFW_MOUSE_BUTTON_MIDDLE));
        assertEquals(new PlanTreeMouseGesture.PanDelta(0, 0),
                gesture.drag(40, 30, GLFW.GLFW_MOUSE_BUTTON_MIDDLE));
        assertNull(gesture.release(20, 30, GLFW.GLFW_MOUSE_BUTTON_MIDDLE, node));
    }

    @Test
    void aNewPressDoesNotInheritThePreviousDrag() {
        gesture.begin(20, 30, GLFW.GLFW_MOUSE_BUTTON_MIDDLE, node);
        gesture.drag(40, 30, GLFW.GLFW_MOUSE_BUTTON_MIDDLE);

        gesture.begin(40, 30, GLFW.GLFW_MOUSE_BUTTON_MIDDLE, node);

        assertSame(node, gesture.release(40, 30, GLFW.GLFW_MOUSE_BUTTON_MIDDLE, node));
    }
}
