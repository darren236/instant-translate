package com.instanttranslate.app;

import org.junit.Test;
import static org.junit.Assert.*;

public final class RefreshPolicyTest {
    private final RefreshPolicy policy = new RefreshPolicy(300);

    @Test public void sustainedScrollingNeverForcesAnEarlyCapture() {
        for (long now = 0; now <= 3000; now += 100) {
            policy.changed(now);
            assertEquals(300, policy.delayUntilReady(now));
            assertEquals(201, policy.delayUntilReady(now + 99));
        }
        assertTrue(policy.isDirty());
    }

    @Test public void readyThreeHundredMillisecondsAfterTheLastChange() {
        policy.changed(1000);
        policy.changed(1200);
        assertEquals(1, policy.delayUntilReady(1499));
        assertEquals(0, policy.delayUntilReady(1500));
        assertEquals(0, policy.delayUntilReady(1900));
        assertTrue(policy.isDirty());
    }

    @Test public void changesDuringInferenceRemainPendingUntilTheNextCapture() {
        policy.changed(1000);
        policy.captured();
        assertFalse(policy.isDirty());
        // The page moves while translation of that captured frame is still running.
        policy.changed(1100);
        assertTrue(policy.isDirty());
        assertEquals(0, policy.delayUntilReady(1700));
        assertTrue(policy.isDirty());
        policy.captured();
        assertFalse(policy.isDirty());
    }

    @Test public void observingStableFramesDoesNotRenewTheQuietTimer() {
        policy.changed(1000);
        assertEquals(200, policy.delayUntilReady(1100));
        assertEquals(100, policy.delayUntilReady(1200));
        assertEquals(0, policy.delayUntilReady(1300));
    }

    @Test public void resetDiscardsPendingChanges() {
        assertFalse(policy.isDirty());
        assertEquals(0, policy.delayUntilReady(0));
        policy.changed(1000);
        policy.reset();
        assertFalse(policy.isDirty());
        assertEquals(0, policy.delayUntilReady(1001));
    }

    @Test public void earlierClockReadingDoesNotShortenTheQuietPeriod() {
        policy.changed(1000);
        assertEquals(300, policy.delayUntilReady(999));
    }

    @Test(expected = IllegalArgumentException.class)
    public void negativeQuietPeriodIsRejected() {
        new RefreshPolicy(-1);
    }
}
