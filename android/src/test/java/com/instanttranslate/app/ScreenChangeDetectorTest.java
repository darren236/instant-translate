package com.instanttranslate.app;

import org.junit.Test;
import java.util.Arrays;
import static org.junit.Assert.*;

public final class ScreenChangeDetectorTest {
    private final ScreenChangeDetector detector = new ScreenChangeDetector();
    private final int[] source = new int[ScreenChangeDetector.COLUMNS * ScreenChangeDetector.ROWS];
    private final boolean[] masks = new boolean[source.length];

    @Test public void staticScreenDoesNotRefresh() {
        detector.seed(source);
        assertFalse(detector.hasChanged(source.clone(), masks));
    }

    @Test public void upperScreenChangeDoesNotRequireLowerScreenChanges() {
        detector.seed(source);
        int[] changed = source.clone();
        Arrays.fill(changed, 35, 44, 0xffffff);
        assertTrue(detector.hasChanged(changed, masks));
    }

    @Test public void changeDuringInferenceRemainsDirtyUntilNewSourceIsCaptured() {
        detector.seed(source);
        int[] nextPage = source.clone();
        Arrays.fill(nextPage, 100, 140, 0xffffff);
        assertTrue(detector.hasChanged(nextPage, masks));
        // More frames and overlay redraws must not turn the new page into the baseline.
        assertTrue(detector.hasChanged(nextPage.clone(), masks));
        detector.seed(nextPage);
        assertFalse(detector.hasChanged(nextPage, masks));
    }

    @Test public void OverlayPixelsDoNotCauseRefreshAndMovingMaskDoesNotResetSource() {
        detector.seed(source);
        int[] withOverlay = source.clone();
        Arrays.fill(withOverlay, 100, 140, 0xffffff);
        Arrays.fill(masks, 100, 140, true);
        assertFalse(detector.hasChanged(withOverlay, masks));
        Arrays.fill(masks, false);
        assertFalse(detector.hasChanged(source, masks));
        assertTrue(detector.hasChanged(withOverlay, masks));
    }

    @Test public void sourceSnapshotCannotBeMutatedByCaller() {
        detector.seed(source);
        Arrays.fill(source, 0xffffff);
        assertTrue(detector.hasChanged(source, masks));
    }
}
