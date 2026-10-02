package com.instanttranslate.app;

/** Compares visible pixels with the captured source, never with our translated overlay. */
final class ScreenChangeDetector {
    static final int COLUMNS = 30;
    static final int ROWS = 45;
    private int[] source;
    private int[] previousFrame;
    private boolean[] previousMasks;

    synchronized void seed(int[] pixels) {
        source = pixels.clone();
        previousFrame = pixels.clone();
        previousMasks = new boolean[pixels.length];
    }

    synchronized boolean hasMotion(int[] pixels, boolean[] ignored) {
        boolean moving = previousFrame != null && previousFrame.length == pixels.length
            && different(previousFrame, pixels, ignored, previousMasks);
        previousFrame = pixels.clone();
        previousMasks = ignored.clone();
        return moving;
    }

    synchronized boolean hasChanged(int[] pixels, boolean[] ignored) {
        return source != null && source.length == pixels.length && different(source, pixels, ignored, null);
    }

    private boolean different(int[] beforePixels, int[] pixels, boolean[] ignored, boolean[] oldMasks) {
        int changed = 0;
        for (int i = 0; i < pixels.length; i++) {
            if (ignored[i] || (oldMasks != null && oldMasks[i])) continue;
            int before = beforePixels[i], after = pixels[i];
            int difference = Math.abs(((before >> 16) & 255) - ((after >> 16) & 255))
                + Math.abs(((before >> 8) & 255) - ((after >> 8) & 255))
                + Math.abs((before & 255) - (after & 255));
            if (difference > 75 && ++changed >= 4) return true;
        }
        return false;
    }
}
