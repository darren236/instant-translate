package com.instanttranslate.app;

import android.graphics.Bitmap;
import java.nio.ByteBuffer;

/** Copies an RGBA capture frame and removes row padding without recycling its result. */
final class FrameBitmap {
    private FrameBitmap() { }

    static Bitmap copy(ByteBuffer pixels, int width, int height, int pixelStride, int rowStride) {
        int paddedWidth = width + (rowStride - pixelStride * width) / pixelStride;
        Bitmap padded = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888);
        try {
            // Image.Plane guarantees pixels through the last row, not its trailing padding.
            int frameBytes = rowStride * height;
            int requiredBytes = rowStride * (height - 1) + pixelStride * width;
            if (pixels.remaining() < requiredBytes) throw new IllegalArgumentException("Incomplete RGBA frame");
            ByteBuffer readable = pixels;
            if (pixels.remaining() < frameBytes) {
                readable = ByteBuffer.allocateDirect(frameBytes);
                readable.put(pixels.duplicate());
                readable.rewind();
            }
            padded.copyPixelsFromBuffer(readable);
            Bitmap result = Bitmap.createBitmap(padded, 0, 0, width, height);
            // A full-size crop can return the input bitmap, especially on unpadded frames.
            if (result != padded) padded.recycle();
            return result;
        } catch (RuntimeException | Error error) {
            padded.recycle();
            throw error;
        }
    }
}
