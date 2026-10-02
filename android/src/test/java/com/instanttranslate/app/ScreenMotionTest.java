package com.instanttranslate.app;

import org.junit.Test;
import java.util.Arrays;
import static org.junit.Assert.*;

public final class ScreenMotionTest {
    private final ScreenChangeDetector detector = new ScreenChangeDetector();
    private final int[] source = new int[ScreenChangeDetector.COLUMNS * ScreenChangeDetector.ROWS];
    private final boolean[] masks = new boolean[source.length];

    @Test public void stableChangedPageIsDirtyButNoLongerMoving() {
        detector.seed(source);
        int[] page = source.clone();
        Arrays.fill(page, 100, 140, 0xffffff);
        assertTrue(detector.hasChanged(page, masks));
        assertTrue(detector.hasMotion(page, masks));
        assertTrue(detector.hasChanged(page, masks));
        assertFalse(detector.hasMotion(page.clone(), masks));
    }

    @Test public void appearingAndDisappearingOverlayDoesNotCountAsMotion() {
        detector.seed(source);
        int[] overlay = source.clone();
        Arrays.fill(overlay, 100, 140, 0xffffff);
        Arrays.fill(masks, 100, 140, true);
        assertFalse(detector.hasMotion(overlay, masks));
        Arrays.fill(masks, false);
        assertFalse(detector.hasMotion(source, masks));
        assertFalse(detector.hasMotion(source, masks));
    }

    @Test public void draggingAnOverlayIgnoresItsOldAndNewPosition() {
        detector.seed(source);
        int[] left = source.clone();
        Arrays.fill(left, 100, 140, 0xffffff);
        Arrays.fill(masks, 100, 140, true);
        assertFalse(detector.hasMotion(left, masks));
        int[] right = source.clone();
        Arrays.fill(right, 200, 240, 0xffffff);
        Arrays.fill(masks, false);
        Arrays.fill(masks, 200, 240, true);
        assertFalse(detector.hasMotion(right, masks));
    }

    @Test public void pageMotionOutsideTheOverlayIsStillDetected() {
        detector.seed(source);
        int[] overlay = source.clone();
        Arrays.fill(overlay, 100, 140, 0xffffff);
        Arrays.fill(masks, 100, 140, true);
        assertFalse(detector.hasMotion(overlay, masks));
        int[] movingPage = overlay.clone();
        Arrays.fill(movingPage, 200, 240, 0xffffff);
        assertTrue(detector.hasMotion(movingPage, masks));
    }

    @Test public void savedFrameAndMasksCannotBeMutatedByTheCaller() {
        detector.seed(source);
        int[] frame = source.clone();
        boolean[] ignored = masks.clone();
        assertFalse(detector.hasMotion(frame, ignored));
        Arrays.fill(frame, 0xffffff);
        Arrays.fill(ignored, true);
        assertTrue(detector.hasMotion(frame, masks));
    }

    @Test public void newCaptureResetsMotionAndSourceTogether() {
        detector.seed(source);
        int[] page = source.clone();
        Arrays.fill(page, 100, 140, 0xffffff);
        assertTrue(detector.hasMotion(page, masks));
        detector.seed(page);
        assertFalse(detector.hasChanged(page, masks));
        assertFalse(detector.hasMotion(page, masks));
    }

    @Test public void stableNewPageLetsTheQuietPeriodExpireDespiteSourceMismatch() {
        RefreshPolicy policy = new RefreshPolicy(300);
        detector.seed(source);
        int[] page = source.clone();
        Arrays.fill(page, 100, 140, 0xffffff);
        assertTrue(detector.hasMotion(page, masks));
        policy.changed(1000);
        assertTrue(detector.hasChanged(page, masks));
        assertFalse(detector.hasMotion(page, masks));
        assertEquals(100, policy.delayUntilReady(1200));
        assertFalse(detector.hasMotion(page, masks));
        assertEquals(0, policy.delayUntilReady(1300));
        assertTrue(policy.isDirty());
    }
}
