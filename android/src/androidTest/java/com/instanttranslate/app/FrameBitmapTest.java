package com.instanttranslate.app;

import android.graphics.Bitmap;
import android.graphics.Color;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.mlkit.vision.common.InputImage;
import java.nio.ByteBuffer;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class FrameBitmapTest {
    @Test public void unpaddedFrameRemainsUsableForOcr() {
        checkFrame(8, 16);
    }

    @Test public void paddedFrameCropsEveryRowAndPreservesPixels() {
        checkFrame(16, 32);
    }

    @Test public void lastRowNeedsNoMappedTrailingPadding() {
        checkFrame(16, 24);
    }

    private void checkFrame(int rowStride, int mappedBytes) {
        ByteBuffer pixels = ByteBuffer.allocateDirect(mappedBytes);
        for (int row = 0; row < 2; row++) {
            pixels.position(row * rowStride);
            // Capture buffers store RGBA bytes, including an opaque alpha channel.
            pixels.put(new byte[]{(byte) 255, 0, 0, (byte) 255});
            pixels.put(new byte[]{0, (byte) 255, 0, (byte) 255});
        }
        pixels.rewind();
        Bitmap bitmap = FrameBitmap.copy(pixels, 2, 2, 4, rowStride);
        try {
            assertFalse(bitmap.isRecycled());
            assertEquals(2, bitmap.getWidth());
            assertEquals(2, bitmap.getHeight());
            for (int row = 0; row < 2; row++) {
                assertEquals(Color.RED, bitmap.getPixel(0, row));
                assertEquals(Color.GREEN, bitmap.getPixel(1, row));
            }
            InputImage image = InputImage.fromBitmap(bitmap, 0);
            assertEquals(2, image.getWidth());
            assertEquals(2, image.getHeight());
        } finally {
            bitmap.recycle();
        }
    }
}
