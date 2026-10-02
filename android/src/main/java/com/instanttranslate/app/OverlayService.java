package com.instanttranslate.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.Context;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.animation.ValueAnimator;
import android.content.SharedPreferences;
import android.view.ViewConfiguration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.text.Layout;
import android.graphics.text.LineBreaker;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public final class OverlayService extends Service {
    public static final String ACTION_START = "com.instanttranslate.app.START";
    public static final String ACTION_STOP = "com.instanttranslate.app.STOP";
    public static final String ACTION_PAUSE = "com.instanttranslate.app.PAUSE";
    public static final String ACTION_RESUME = "com.instanttranslate.app.RESUME";
    public static final String ACTION_SCAN = "com.instanttranslate.app.SCAN";
    public static final String EXTRA_RESULT_CODE = "capture_result_code";
    public static final String EXTRA_RESULT_DATA = "capture_result_data";
    public static volatile boolean running;
    private static volatile OverlayService activeService;

    private static final String CHANNEL = "screen_translation";
    private static final int NOTIFICATION_ID = 101;
    private static final long SETTLE_DELAY_MS = 300;
    private static final long PROBE_INTERVAL_MS = 200;
    private static final Pattern HAN = Pattern.compile("[\\u3400-\\u9fff\\uf900-\\ufaff]");
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, String> cache = new LinkedHashMap<String, String>(128, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, String> eldest) { return size() > 500; }
    };
    private HandlerThread imageThread;
    private Handler imageHandler;
    private WindowManager windowManager;
    private WindowManager overlayWindowManager;
    private FrameLayout overlay;
    private DuckView duck;
    private ValueAnimator dockAnimation;
    private boolean duckDragging;
    private SharedPreferences preferences;
    private final Runnable refreshControlMasks = this::updateProbeMasks;
    private final Map<String, TextView> renderedViews = new LinkedHashMap<>();
    private WindowManager.LayoutParams duckParams;
    private LinearLayout duckMenu;
    private WindowManager.LayoutParams menuParams;
    private WindowManager.LayoutParams overlayParams;
    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private volatile ImageReader imageReader;
    private TextRecognizer recognizer;
    private Translator translator;
    private boolean modelReady;
    private volatile boolean paused;
    private volatile boolean composerVisible;
    private long lastCaptureAt;
    private volatile boolean busy;
    private volatile boolean stopping;
    private volatile boolean captureRequested;
    private volatile int screenWidth;
    private volatile int screenHeight;
    private volatile int captureEpoch;
    private volatile int probeGeneration;
    private final ScreenChangeDetector changeDetector = new ScreenChangeDetector();
    private volatile List<Rect> probeMasks = new ArrayList<>();
    private List<Rect> translationMasks = new ArrayList<>();
    private volatile boolean capturePreparing;
    private long lastProbeAt;
    private volatile long lastControlMoveAt;
    private final RefreshPolicy refreshPolicy = new RefreshPolicy(SETTLE_DELAY_MS);
    private boolean pendingRefresh;
    private volatile String scanStatus = "Getting ready";
    private int statusBarHeight;
    private final Runnable nextCapture = this::capture;
    private final Runnable captureTimeout = () -> {
        if (!busy || stopping) return;
        captureRequested = false;
        capturePreparing = false;
        showOverlay();
        busy = false;
        setScanStatus("Screen capture timed out · tap Refresh to retry");
        if (pendingRefresh) { pendingRefresh = false; scheduleCapture(); }

    };

    private static final class Block {
        final String source;
        final Rect box;
        final int background;
        final int foreground;
        final int lineCount;
        String result;
        Block(String source, Rect box, int background, int lineCount) {
            this.source = source;
            this.box = new Rect(box);
            this.background = background;
            this.lineCount = Math.max(1, lineCount);
            int brightness = (Color.red(background) * 299 + Color.green(background) * 587
                + Color.blue(background) * 114) / 1000;
            this.foreground = brightness < 145 ? Color.WHITE : Color.rgb(28, 34, 52);
        }
    }

    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    @Override public void onCreate() {
        super.onCreate();
        activeService = this;
        preferences = getSharedPreferences("overlay_controls", MODE_PRIVATE);
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        imageThread = new HandlerThread("screen-frames");
        imageThread.start();
        imageHandler = new Handler(imageThread.getLooper());
        recognizer = TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
        TranslatorOptions options = new TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.CHINESE)
            .setTargetLanguage(TranslateLanguage.ENGLISH)
            .build();
        translator = Translation.getClient(options);
        int resource = getResources().getIdentifier("status_bar_height", "dimen", "android");
        statusBarHeight = resource > 0 ? getResources().getDimensionPixelSize(resource) : 0;
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || ACTION_STOP.equals(intent.getAction())) {
            stopNow();
            return START_NOT_STICKY;
        }
        if (ACTION_PAUSE.equals(intent.getAction())) { setPaused(true); return START_NOT_STICKY; }
        if (ACTION_RESUME.equals(intent.getAction())) { setPaused(false); return START_NOT_STICKY; }
        if (ACTION_SCAN.equals(intent.getAction())) { manualScan(); return START_NOT_STICKY; }
        if (!ACTION_START.equals(intent.getAction()) || running || stopping) return START_NOT_STICKY;
        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
        Intent resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA);
        if (resultCode == 0 || resultData == null) {
            stopNow();
            return START_NOT_STICKY;
        }
        try {
            createNotificationChannel();
            Notification starting = notification("Preparing on-device translation…");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                startForeground(NOTIFICATION_ID, starting, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            else startForeground(NOTIFICATION_ID, starting);
            startProjection(resultCode, resultData);
            running = true;
            setScanStatus("Preparing offline translation…");
            prepareModel();
        } catch (Exception error) {
            Log.e("InstantTranslate", "Could not start screen overlay", error);
            stopNow();
        }
        return START_NOT_STICKY;
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(CHANNEL, "Screen translation",
            NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Controls the live translation overlay");
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(channel);
    }

    private Notification notification(String message) {
        Intent stop = new Intent(this, OverlayService.class).setAction(ACTION_STOP);
        PendingIntent stopAction = PendingIntent.getService(this, 1, stop,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent openAction = PendingIntent.getActivity(this, 2, open,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Instant Translate is active")
            .setContentText(message)
            .setOngoing(true)
            .setContentIntent(openAction)
            .addAction(android.R.drawable.ic_media_pause, paused ? "Resume" : "Pause",
                PendingIntent.getService(this, 3, new Intent(this, OverlayService.class)
                    .setAction(paused ? ACTION_RESUME : ACTION_PAUSE),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE))
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopAction)
            .build();
    }

    private void startProjection(int resultCode, Intent resultData) {
        MediaProjectionManager manager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        projection = manager.getMediaProjection(resultCode, resultData);
        if (projection == null) throw new IllegalStateException("No screen capture token");
        projection.registerCallback(new MediaProjection.Callback() {
            @Override public void onStop() { main.post(() -> stopNow()); }
            @Override public void onCapturedContentResize(int width, int height) {
                main.post(() -> resizeCapture(width, height));
            }
        }, main);
        DisplayMetrics metrics = new DisplayMetrics();
        windowManager.getDefaultDisplay().getRealMetrics(metrics);
        screenWidth = metrics.widthPixels;
        screenHeight = metrics.heightPixels;
        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2);
        imageReader.setOnImageAvailableListener(this::onImageAvailable, imageHandler);
        virtualDisplay = projection.createVirtualDisplay("Instant Translate", screenWidth, screenHeight,
            metrics.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader.getSurface(), null, imageHandler);
        createOverlay();
        createDuck();
    }

    private void createOverlay() {
        renderedViews.clear();
        translationMasks = new ArrayList<>();
        overlay = new FrameLayout(this);
        overlay.setBackgroundColor(Color.TRANSPARENT);
        TranslationAccessibilityService accessibility = TranslationAccessibilityService.connected;
        boolean solid = accessibility != null;
        overlayWindowManager = solid ? accessibility.overlayWindowManager() : windowManager;
        overlayParams = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            solid ? WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                : WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT);
        overlayParams.gravity = Gravity.TOP | Gravity.LEFT;
        overlayParams.alpha = solid ? 1f : .79f;
        try {
            overlayWindowManager.addView(overlay, overlayParams);
        } catch (RuntimeException error) {
            if (!solid || !Settings.canDrawOverlays(this)) throw error;
            Log.w("InstantTranslate", "Accessibility overlay unavailable; using standard overlay", error);
            overlayWindowManager = windowManager;
            overlayParams.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
            overlayParams.alpha = .79f;
            overlayWindowManager.addView(overlay, overlayParams);
        }
    }

    public static boolean isPaused() {
        OverlayService service = activeService;
        return service != null && service.paused;
    }

    public static String sessionStatus() {
        OverlayService service = activeService;
        if (service == null || !running) return "Ready to translate";
        return service.paused ? "Paused · tap the duck to resume" : service.scanStatus;
    }

    static void onAccessibilityChanged() {
        OverlayService service = activeService;
        if (service != null) service.main.post(service::switchOverlayHost);
    }

    static void onForegroundWindowChanged() {
        OverlayService service = activeService;
        if (service != null) service.main.post(() -> service.onContentChanged(true));
    }

    static void onScreenTap() {
        OverlayService service = activeService;
        if (service != null) service.main.post(() -> {
            // A tap alone does not change screen text. Renew a pending scroll/page update only.
            if (service.refreshPolicy.isDirty()) service.onContentChanged(true);
        });
    }

    static void onScreenInteraction() {
        OverlayService service = activeService;
        if (service != null) service.main.post(() -> service.onContentChanged(true));
    }

    static void onComposerVisible(boolean visible) {
        OverlayService service = activeService;
        if (service != null) service.main.post(() -> service.setComposerVisible(visible));
    }

    private void setComposerVisible(boolean visible) {
        composerVisible = visible;
        if (visible) {
            captureEpoch++;
            captureRequested = false;
            capturePreparing = false;
            busy = false;
            main.removeCallbacks(nextCapture);
            main.removeCallbacks(captureTimeout);
            dismissDuckMenu();
            if (overlay != null) overlay.setVisibility(View.GONE);
            if (duck != null) duck.setVisibility(View.GONE);
        } else {
            if (duck != null) duck.setVisibility(View.VISIBLE);
            if (overlay != null) { overlay.removeAllViews(); renderedViews.clear(); translationMasks = new ArrayList<>(); overlay.setVisibility(paused ? View.GONE : View.VISIBLE); }
            scheduleCapture();
        }
    }

    private void onContentChanged(boolean accessibilityEvent) {
        if (stopping || paused || composerVisible || overlay == null) return;
        refreshPolicy.changed(SystemClock.uptimeMillis());
        if (busy) { pendingRefresh = true; return; }
        if (!running || !modelReady) return;
        main.removeCallbacks(nextCapture);
        main.postDelayed(nextCapture, refreshPolicy.delayUntilReady(SystemClock.uptimeMillis()));
    }

    private void switchOverlayHost() {
        if (stopping || overlay == null || overlayParams == null) return;
        boolean wantsSolid = TranslationAccessibilityService.connected != null;
        boolean isSolid = overlayParams.type == WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY;
        if (wantsSolid == isSolid) return;
        if (!wantsSolid && !Settings.canDrawOverlays(this)) {
            stopNow();
            return;
        }
        captureEpoch++;
        busy = false;
        captureRequested = false;
        capturePreparing = false;
        main.removeCallbacks(nextCapture);
        main.removeCallbacks(captureTimeout);
        removeDuck();
        removeOverlay();
        createOverlay();
        createDuck();
        probeGeneration++;
        scheduleCapture();
    }

    private void removeOverlay() {
        if (overlay != null) {
            try { overlayWindowManager.removeView(overlay); } catch (Exception ignored) { }
            overlay = null;
        }
        overlayWindowManager = null;
        overlayParams = null;
    }

    private void prepareModel() {
        translator.downloadModelIfNeeded(new DownloadConditions.Builder().build())
            .addOnSuccessListener(unused -> {
                if (stopping) return;
                modelReady = true;
                setScanStatus("Ready · open Taobao or another app");
                scheduleCapture();
            })
            .addOnFailureListener(error -> {
                if (stopping) return;
                Log.e("InstantTranslate", "Language pack unavailable", error);
                setScanStatus("Language pack unavailable · tap Refresh to retry");
            });
    }

    private void scheduleCapture() {
        if (stopping || paused || composerVisible || !running || !modelReady) return;
        main.removeCallbacks(nextCapture);
        main.postDelayed(nextCapture, refreshPolicy.isDirty() ? refreshPolicy.delayUntilReady(SystemClock.uptimeMillis()) : SETTLE_DELAY_MS);
    }

    private void capture() {
        if (stopping || paused || composerVisible || busy || imageReader == null || !modelReady) return;
        if (duckDragging) { main.postDelayed(nextCapture, SETTLE_DELAY_MS); return; }
        long quietDelay = refreshPolicy.delayUntilReady(SystemClock.uptimeMillis());
        if (quietDelay > 0) { main.postDelayed(nextCapture, quietDelay); return; }
        pendingRefresh = false;
        busy = true;
        capturePreparing = true;
        lastCaptureAt = SystemClock.uptimeMillis();
        if (Log.isLoggable("InstantTranslate", Log.DEBUG)) Log.d("InstantTranslate", "Capturing changed screen");
        int epoch = captureEpoch;
        imageHandler.post(() -> {
            // Discard frames from before the overlay was hidden. They can contain
            // translations belonging to the previous page.
            Image old = null;
            try { old = imageReader.acquireLatestImage(); }
            catch (Exception ignored) { }
            finally { if (old != null) old.close(); }
            main.post(() -> {
                if (stopping || epoch != captureEpoch) return;
                hideCaptureWindows();
                probeGeneration++;
                main.postDelayed(() -> {
                    if (stopping || epoch != captureEpoch) return;
                    captureRequested = true;
                    // A static screen may emit just one frame when the overlay hides.
                    // Keep it buffered until the overlay has disappeared.
                    imageHandler.post(() -> onImageAvailable(imageReader));
                }, 110);
            });
        });
        main.postDelayed(captureTimeout, 2500);
    }

    private void onImageAvailable(ImageReader reader) {
        if (reader == null || reader != imageReader || stopping) return;
        Image image = null;
        Bitmap unsubmittedBitmap = null;
        int startEpoch = captureEpoch;
        try {
            // Preserve the frame produced by hiding our windows until capture is armed.
            if (capturePreparing && !captureRequested) return;
            image = reader.acquireLatestImage();
            if (image == null || stopping) return;
            if (!captureRequested) {
                if (!paused && !composerVisible) probeForChanges(image);
                return;
            }
            // Resizing or stopping may invalidate the reader while this frame is acquired.
            if (startEpoch != captureEpoch || reader != imageReader || stopping) return;
            int epoch = startEpoch;
            captureRequested = false;
            capturePreparing = false;
            changeDetector.seed(fingerprint(image));
            Bitmap bitmap = bitmapFromImage(image);
            unsubmittedBitmap = bitmap;
            main.post(() -> {
                if (epoch == captureEpoch) {
                    main.removeCallbacks(captureTimeout);
                    refreshPolicy.captured();
                    showOverlay();
                    probeGeneration++;
                }
            });
            InputImage input = InputImage.fromBitmap(bitmap, 0);
            recognizer.process(input)
                .addOnSuccessListener(result -> main.post(() -> {
                    try {
                        if (epoch == captureEpoch) processText(result, bitmap, epoch);
                    } finally {
                        bitmap.recycle();
                    }
                }))
                .addOnFailureListener(error -> main.post(() -> {
                    bitmap.recycle();
                    if (stopping || epoch != captureEpoch) return;
                    Log.w("InstantTranslate", "OCR failed", error);
                    setScanStatus("Text recognition failed · tap Refresh to retry");
                    finishCycle();
                }));
            unsubmittedBitmap = null; // OCR completion now owns and releases the bitmap.
        } catch (Exception error) {
            if (unsubmittedBitmap != null) unsubmittedBitmap.recycle();
            Log.w("InstantTranslate", "Could not read screen frame", error);
            main.post(() -> {
                if (stopping || startEpoch != captureEpoch) return;
                setScanStatus("Could not read screen · tap Refresh to retry");
                finishCycle();
            });
        } finally {
            if (image != null) image.close();
        }
    }

    private void probeForChanges(Image image) {
        int generation = probeGeneration;
        long now = SystemClock.uptimeMillis();
        // Window movement and its shadows need a frame to settle before masking them.
        if (now - lastControlMoveAt < SETTLE_DELAY_MS || now - lastProbeAt < PROBE_INTERVAL_MS) return;
        lastProbeAt = now;
        int[] pixels = fingerprint(image);
        boolean[] ignored = new boolean[pixels.length];
        int top = Math.min(image.getHeight() / 3, statusBarHeight + dp(28));
        int bottom = Math.max(top + 1, image.getHeight() - dp(56));
        List<Rect> masks = probeMasks;
        for (int row = 0; row < ScreenChangeDetector.ROWS; row++) {
            int y = top + row * (bottom - top) / ScreenChangeDetector.ROWS;
            for (int column = 0; column < ScreenChangeDetector.COLUMNS; column++) {
                int x = (column * image.getWidth() + image.getWidth() / 2) / ScreenChangeDetector.COLUMNS;
                for (Rect mask : masks) if (mask.contains(x, y)) {
                    ignored[row * ScreenChangeDetector.COLUMNS + column] = true;
                    break;
                }
            }
        }
        boolean changed = changeDetector.hasChanged(pixels, ignored);
        boolean moving = changeDetector.hasMotion(pixels, ignored);
        if (changed || moving) main.post(() -> {
            if (generation != probeGeneration) return;
            if ((changed && !refreshPolicy.isDirty()) || (moving && refreshPolicy.isDirty()))
                onContentChanged(false);
        });
    }

    private int[] fingerprint(Image image) {
        Image.Plane plane = image.getPlanes()[0];
        ByteBuffer pixels = plane.getBuffer();
        int top = Math.min(image.getHeight() / 3, statusBarHeight + dp(28));
        int bottom = Math.max(top + 1, image.getHeight() - dp(56));
        int[] result = new int[ScreenChangeDetector.COLUMNS * ScreenChangeDetector.ROWS];
        for (int row = 0; row < ScreenChangeDetector.ROWS; row++) {
            int y = top + row * (bottom - top) / ScreenChangeDetector.ROWS;
            for (int column = 0; column < ScreenChangeDetector.COLUMNS; column++) {
                int x = (column * image.getWidth() + image.getWidth() / 2) / ScreenChangeDetector.COLUMNS;
                int offset = y * plane.getRowStride() + x * plane.getPixelStride();
                if (offset + 2 < pixels.limit()) result[row * ScreenChangeDetector.COLUMNS + column] =
                    ((pixels.get(offset) & 255) << 16) | ((pixels.get(offset + 1) & 255) << 8)
                        | (pixels.get(offset + 2) & 255);
            }
        }
        return result;
    }

    private void hideCaptureWindows() {
        if (overlay != null) overlay.setVisibility(View.INVISIBLE);
        if (duck != null) duck.setVisibility(View.INVISIBLE);
        if (duckMenu != null) duckMenu.setVisibility(View.INVISIBLE);
        probeMasks = new ArrayList<>();
    }

    /** Publish immutable screen rectangles; the frame thread never reads View state. */
    private void updateProbeMasks() {
        List<Rect> masks = new ArrayList<>();
        if (overlay != null && overlay.getVisibility() == View.VISIBLE) {
            masks.addAll(translationMasks);
        }
        addProbeMask(masks, duck);
        addProbeMask(masks, duckMenu);
        probeMasks = masks;
    }

    private void addProbeMask(List<Rect> masks, View view) {
        if (view == null || view.getVisibility() != View.VISIBLE) return;
        int[] origin = new int[2];
        view.getLocationOnScreen(origin);
        Rect area = new Rect(origin[0], origin[1], origin[0] + view.getWidth(), origin[1] + view.getHeight());
        area.inset(-dp(4), -dp(4));
        masks.add(area);
    }

    private Bitmap bitmapFromImage(Image image) {
        Image.Plane plane = image.getPlanes()[0];
        return FrameBitmap.copy(plane.getBuffer(), image.getWidth(), image.getHeight(),
            plane.getPixelStride(), plane.getRowStride());
    }

    private void resizeCapture(int width, int height) {
        if (stopping || virtualDisplay == null || width <= 0 || height <= 0
            || (width == screenWidth && height == screenHeight)) return;
        captureEpoch++;
        captureRequested = false;
        capturePreparing = false;
        busy = false;
        main.removeCallbacks(nextCapture);
        main.removeCallbacks(captureTimeout);
        if (overlay != null) { overlay.removeAllViews(); renderedViews.clear(); translationMasks = new ArrayList<>(); overlay.setVisibility(View.VISIBLE); }
        probeGeneration++;
        ImageReader previous = imageReader;
        ImageReader replacement = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
        imageReader = replacement;
        replacement.setOnImageAvailableListener(this::onImageAvailable, imageHandler);
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        virtualDisplay.resize(width, height, metrics.densityDpi);
        virtualDisplay.setSurface(replacement.getSurface());
        screenWidth = width;
        screenHeight = height;
        if (duck != null) { removeDuck(); createDuck(); }
        if (previous != null) previous.close();
        scheduleCapture();
    }

    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        DisplayMetrics metrics = new DisplayMetrics();
        windowManager.getDefaultDisplay().getRealMetrics(metrics);
        resizeCapture(metrics.widthPixels, metrics.heightPixels);
    }

    private void processText(Text text, Bitmap screenshot, int epoch) {
        if (stopping || epoch != captureEpoch) return;
        List<Block> blocks = new ArrayList<>();
        for (Text.TextBlock item : text.getTextBlocks()) {
            for (Text.Line line : item.getLines()) {
                String source = line.getText().trim();
                Rect box = line.getBoundingBox();
                if (box != null && containsChinese(source)
                    && !looksLikeAddress(source)
                    && box.top > statusBarHeight + dp(2)
                    && box.width() > dp(6) && box.height() > dp(5)) {
                    blocks.add(new Block(source, box, sampleBackground(screenshot, box), 1));
                }
            }
        }
        blocks.sort(Comparator.comparingInt((Block block) -> block.box.top)
            .thenComparingInt(block -> block.box.left));
        setScanStatus(blocks.isEmpty() ? "Ready · no Chinese text here" : "Translating " + blocks.size() + " lines…");
        new TranslationBatch(blocks, epoch).pump();
    }

    private int sampleBackground(Bitmap screenshot, Rect box) {
        int[] reds = new int[8];
        int[] greens = new int[8];
        int[] blues = new int[8];
        int inset = Math.max(1, Math.min(dp(3), box.height() / 5));
        int[] xs = {box.left + inset, box.centerX(), box.right - inset, box.left + inset,
            box.right - inset, box.left + inset, box.centerX(), box.right - inset};
        int[] ys = {box.top - inset, box.top - inset, box.top - inset, box.centerY(),
            box.centerY(), box.bottom + inset, box.bottom + inset, box.bottom + inset};
        for (int i = 0; i < xs.length; i++) {
            int x = Math.max(0, Math.min(screenshot.getWidth() - 1, xs[i]));
            int y = Math.max(0, Math.min(screenshot.getHeight() - 1, ys[i]));
            int pixel = screenshot.getPixel(x, y);
            reds[i] = Color.red(pixel);
            greens[i] = Color.green(pixel);
            blues[i] = Color.blue(pixel);
        }
        Arrays.sort(reds);
        Arrays.sort(greens);
        Arrays.sort(blues);
        return Color.rgb((reds[3] + reds[4]) / 2, (greens[3] + greens[4]) / 2,
            (blues[3] + blues[4]) / 2);
    }

    private boolean containsChinese(String source) {
        int count = 0;
        for (int i = 0; i < source.length(); i++) {
            if (HAN.matcher(String.valueOf(source.charAt(i))).find()) count++;
        }
        return count >= 2 || (count == 1 && source.length() > 1);
    }

    private boolean looksLikeAddress(String source) {
        String lower = source.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("://") || lower.contains("www.")
            || lower.contains(".com") || lower.contains(".cn")
            || lower.contains(".html")
            || lower.matches(".*\\d+\\.\\d+\\.\\d+\\.\\d+.*");
    }

    private final class TranslationBatch {
        private final List<Block> blocks;
        private final List<Block> uncached = new ArrayList<>();
        private final Map<String, List<Block>> matching = new LinkedHashMap<>();
        private final int epoch;
        private int next;
        private int active;
        private boolean drawPending;
        private final Runnable partialDraw;

        TranslationBatch(List<Block> blocks, int epoch) {
            this.blocks = blocks;
            this.epoch = epoch;
            for (Block block : blocks) {
                String cached = cache.get(block.source);
                if (cached != null) {
                    block.result = cached;
                } else {
                    List<Block> copies = matching.get(block.source);
                    if (copies == null) {
                        copies = new ArrayList<>();
                        matching.put(block.source, copies);
                        uncached.add(block);
                    }
                    copies.add(block);
                }
            }
            // Restore every cached line together before starting slower inference.
            // This also puts familiar labels at their new positions after a scroll.
            drawBlocks(blocks);
            this.partialDraw = () -> {
                drawPending = false;
                if (!stopping && epoch == captureEpoch) drawBlocks(blocks);
            };
        }

        void pump() {
            if (stopping || epoch != captureEpoch) return;
            while (active < 4 && next < uncached.size()) {
                Block block = uncached.get(next++);
                active++;
                translator.translate(block.source).addOnCompleteListener(task -> {
                    if (stopping || epoch != captureEpoch) return;
                    if (task.isSuccessful()) {
                        String result = task.getResult();
                        if (result != null && !result.trim().isEmpty()) {
                            cache.put(block.source, result);
                            for (Block copy : matching.get(block.source)) copy.result = result;
                            requestDraw();
                        }
                    } else {
                        Log.w("InstantTranslate", "Translation failed", task.getException());
                    }
                    active--;
                    pump();
                });
            }
            if (next == uncached.size() && active == 0) {
                main.removeCallbacks(partialDraw);
                drawBlocks(blocks);
                int shown = 0;
                for (Block block : blocks) if (block.result != null) shown++;
                if (!blocks.isEmpty()) setScanStatus(shown == blocks.size() ? "Up to date · " + shown + " lines" : "Translated " + shown + " of " + blocks.size() + " lines · tap Refresh to retry");
                finishCycle();
            }
        }

        private void requestDraw() {
            if (drawPending || stopping || epoch != captureEpoch) return;
            drawPending = true;
            main.postDelayed(partialDraw, 50);
        }
    }

    private void drawBlocks(List<Block> blocks) {
        if (overlay == null || stopping || pendingRefresh) return;
        java.util.Set<String> retained = new java.util.HashSet<>();
        int[] origin = new int[2];
        overlay.getLocationOnScreen(origin);
        int viewportWidth = overlay.getWidth();
        int viewportHeight = overlay.getHeight();
        if (viewportWidth == 0 || viewportHeight == 0) {
            int epoch = captureEpoch;
            overlay.post(() -> {
                if (epoch == captureEpoch && overlay != null && overlay.getWidth() > 0) drawBlocks(blocks);
            });
            return;
        }
        List<Rect> drawnMasks = new ArrayList<>();
        for (Block block : blocks) {
            if (block.result == null) continue;
            int left = Math.max(0, block.box.left - origin[0]);
            int top = Math.max(0, block.box.top - origin[1]);
            if (left >= viewportWidth || top >= viewportHeight) continue;
            int rightLimit = viewportWidth - dp(2);
            for (Block other : blocks) {
                if (other == block || other.box.left <= block.box.left) continue;
                if (other.box.top < block.box.bottom + dp(2)
                    && other.box.bottom > block.box.top - dp(2)) {
                    rightLimit = Math.min(rightLimit, other.box.left - origin[0] - dp(3));
                }
            }
            int availableWidth = rightLimit - left;
            if (availableWidth < dp(20)) continue;
            Typeface face = Typeface.create("sans-serif-medium", Typeface.NORMAL);
            float textSize = Math.max(dp(8), Math.min(
                block.box.height() * .8f / block.lineCount, dp(28)));
            Paint measure = new Paint(Paint.ANTI_ALIAS_FLAG);
            measure.setTypeface(face);
            measure.setTextSize(textSize);
            int measuredWidth = (int) Math.ceil(measure.measureText(block.result)) + dp(3);
            int width = block.lineCount == 1
                ? Math.min(availableWidth, Math.max(block.box.width() + dp(2), measuredWidth))
                : Math.min(availableWidth, block.box.width() + dp(2));
            int height = Math.max(block.box.height(), (int) Math.ceil(textSize * 1.16f));
            height = Math.min(height, viewportHeight - top);
            String key = block.source + "\n" + block.box.toShortString();
            retained.add(key);
            TextView translated = renderedViews.get(key);
            boolean newView = translated == null;
            if (newView) { translated = new TextView(this); renderedViews.put(key, translated); }
            String style = block.result + "\n" + width + ":" + height + ":" + textSize + ":" + block.background;
            if (!style.equals(translated.getTag())) {
                textSize = fitTextSize(block.result, face, width - dp(2), height, textSize);
                if (!block.result.contentEquals(translated.getText())) translated.setText(block.result);
                translated.setTextColor(block.foreground);
                translated.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, textSize);
                translated.setTypeface(face);
                // This compile-time constant is inlined; no API 29 class is loaded on older devices.
                translated.setBreakStrategy(LineBreaker.BREAK_STRATEGY_SIMPLE);
                translated.setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE);
                translated.setMaxLines(Math.max(1, (int) (height / Math.max(1f, textSize * 1.08f))));
                translated.setEllipsize(android.text.TextUtils.TruncateAt.END);
                translated.setIncludeFontPadding(false);
                translated.setGravity(block.lineCount == 1 ? Gravity.CENTER_VERTICAL : Gravity.TOP);
                translated.setPadding(dp(1), 0, dp(1), 0);
                translated.setBackgroundColor(block.background);
                translated.setTag(style);
            }
            FrameLayout.LayoutParams position = new FrameLayout.LayoutParams(width, height);
            position.leftMargin = left;
            position.topMargin = top;
            if (newView) overlay.addView(translated, position);
            else {
                FrameLayout.LayoutParams previous = (FrameLayout.LayoutParams) translated.getLayoutParams();
                if (previous.width != width || previous.height != height || previous.leftMargin != left || previous.topMargin != top)
                    translated.setLayoutParams(position);
            }
            Rect mask = new Rect(left + origin[0], top + origin[1], left + origin[0] + width, top + origin[1] + height);
            mask.inset(-dp(4), -dp(4));
            drawnMasks.add(mask);
        }
        java.util.Iterator<Map.Entry<String, TextView>> views = renderedViews.entrySet().iterator();
        while (views.hasNext()) {
            Map.Entry<String, TextView> entry = views.next();
            if (!retained.contains(entry.getKey())) { overlay.removeView(entry.getValue()); views.remove(); }
        }
        translationMasks = drawnMasks;
        // Do not reset the source baseline when our own labels are redrawn.
        overlay.post(this::updateProbeMasks);
        updateProbeMasks();
    }

    private float fitTextSize(String value, Typeface face, int width, int height, float preferred) {
        float minimum = dp(8);
        float low = minimum;
        float high = Math.max(minimum, preferred);
        float best = minimum;
        for (int attempt = 0; attempt < 9; attempt++) {
            float candidate = (low + high) / 2f;
            TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
            paint.setTypeface(face);
            paint.setTextSize(candidate);
            StaticLayout layout = StaticLayout.Builder.obtain(value, 0, value.length(), paint,
                    Math.max(1, width))
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setIncludePad(false)
                .setBreakStrategy(LineBreaker.BREAK_STRATEGY_SIMPLE)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
                .build();
            if (layout.getHeight() <= height) {
                best = candidate;
                low = candidate;
            } else {
                high = candidate;
            }
        }
        return best;
    }

    private void showOverlay() {
        if (stopping || composerVisible) return;
        if (overlay != null && !paused) overlay.setVisibility(View.VISIBLE);
        if (duck != null) duck.setVisibility(View.VISIBLE);
        if (duckMenu != null) duckMenu.setVisibility(View.VISIBLE);
        updateProbeMasks();
    }

    private void setScanStatus(String message) {
        scanStatus = message;
        if (duck != null) {
            duck.invalidate();
            duck.setContentDescription((paused ? "Translation paused" : "Instant Translate is running") + ". Tap for controls or drag to move.");
        }
        MainActivity.onOverlayChanged();
        if (!stopping && running) ((NotificationManager) getSystemService(NOTIFICATION_SERVICE))
            .notify(NOTIFICATION_ID, notification(paused ? "Paused · tap the duck to continue" : message));
        if (duckMenu != null && duckMenu.getChildCount() > 0
            && duckMenu.getChildAt(0) instanceof TextView)
            ((TextView) duckMenu.getChildAt(0)).setText(message);
    }

    private void setPaused(boolean value) {
        if (!running || stopping) return;
        paused = value;
        dismissDuckMenu();
        captureEpoch++;
        captureRequested = false;
        capturePreparing = false;
        busy = false;
        pendingRefresh = false;
        refreshPolicy.reset();
        main.removeCallbacks(nextCapture);
        main.removeCallbacks(captureTimeout);
        if (overlay != null) {
            overlay.removeAllViews(); renderedViews.clear(); translationMasks = new ArrayList<>();
            overlay.setVisibility(value ? View.GONE : View.VISIBLE);
        }
        showOverlay();
        setScanStatus(value ? "Paused" : "Scanning screen");
        if (!value) scheduleCapture();
    }

    private void manualScan() {
        if (stopping || !running) return;
        if (!modelReady) { prepareModel(); return; }
        if (paused) setPaused(false);
        dismissDuckMenu();
        if (busy) { pendingRefresh = true; return; }
        refreshPolicy.reset();
        main.removeCallbacks(nextCapture);
        main.post(nextCapture);
    }

    private void createDuck() {
        if (overlayWindowManager == null || duck != null) return;
        duck = new DuckView(this);
        int size = dp(56);
        duckParams = new WindowManager.LayoutParams(size, size, overlayParams.type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT);
        duckParams.gravity = Gravity.TOP | Gravity.LEFT;
        duckParams.x = preferences.getBoolean("dock_left", false) ? dp(10) : screenWidth - size - dp(10);
        duckParams.y = Math.max(statusBarHeight + dp(8), Math.min(screenHeight - size - dp(36),
            Math.round(preferences.getFloat("dock_y", .62f) * screenHeight)));
        duck.setElevation(dp(4));
        duck.setContentDescription("Instant Translate is running. Tap for controls or drag to move.");
        duck.setOnClickListener(view -> toggleDuckMenu());
        duck.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> updateProbeMasks());
        duck.setOnTouchListener(new View.OnTouchListener() {
            float downX, downY;
            int startX, startY;
            boolean dragged;
            @Override public boolean onTouch(View view, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        if (dockAnimation != null) dockAnimation.cancel();
                        downX = event.getRawX(); downY = event.getRawY();
                        startX = duckParams.x; startY = duckParams.y;
                        dragged = false;
                        duckDragging = true;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        int dx = Math.round(event.getRawX() - downX), dy = Math.round(event.getRawY() - downY);
                        int slop = ViewConfiguration.get(OverlayService.this).getScaledTouchSlop();
                        if (Math.abs(dx) > slop || Math.abs(dy) > slop) dragged = true;
                        if (dragged) {
                            lastControlMoveAt = SystemClock.uptimeMillis();
                            dismissDuckMenu();
                            duckParams.x = Math.max(0, Math.min(screenWidth - size, startX + dx));
                            duckParams.y = Math.max(statusBarHeight + dp(8), Math.min(screenHeight - size - dp(36), startY + dy));
                            updateDuckPosition();
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        duckDragging = false;
                        if (dragged) dockDuck(); else view.performClick();
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        duckDragging = false;
                        dockDuck();
                        return true;
                    default: return true;
                }
            }
        });
        overlayWindowManager.addView(duck, duckParams);
        duck.post(this::updateProbeMasks);
    }

    private void updateDuckPosition() {
        if (duck == null || stopping) return;
        overlayWindowManager.updateViewLayout(duck, duckParams);
        main.removeCallbacks(refreshControlMasks);
        main.postDelayed(refreshControlMasks, SETTLE_DELAY_MS);
    }

    private void dockDuck() {
        if (duck == null || stopping) return;
        boolean leftSide = duckParams.x + dp(28) < screenWidth / 2;
        int target = leftSide ? dp(10) : screenWidth - dp(66);
        preferences.edit().putBoolean("dock_left", leftSide)
            .putFloat("dock_y", duckParams.y / (float) Math.max(1, screenHeight)).apply();
        dockAnimation = ValueAnimator.ofInt(duckParams.x, target);
        dockAnimation.setDuration(160);
        dockAnimation.addUpdateListener(animation -> {
            if (duck == null || stopping) return;
            lastControlMoveAt = SystemClock.uptimeMillis();
            duckParams.x = (int) animation.getAnimatedValue();
            updateDuckPosition();
        });
        dockAnimation.start();
    }

    private void toggleDuckMenu() {
        lastControlMoveAt = SystemClock.uptimeMillis();
        if (duckMenu != null) { dismissDuckMenu(); return; }
        duckMenu = new LinearLayout(this);
        duckMenu.setOrientation(LinearLayout.VERTICAL);
        duckMenu.setPadding(dp(10), dp(8), dp(10), dp(8));
        android.graphics.drawable.GradientDrawable background = new android.graphics.drawable.GradientDrawable();
        background.setColor(Color.WHITE);
        background.setCornerRadius(dp(18));
        background.setStroke(dp(1), 0xffe5e8ef);
        duckMenu.setBackground(background);
        duckMenu.setElevation(dp(6));
        duckMenu.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> updateProbeMasks());
        TextView status = menuText(sessionStatus(), false);
        status.setTextColor(0xff667085);
        status.setTextSize(12);
        duckMenu.addView(status);
        addMenuAction(paused ? "▶  Resume translation" : "Ⅱ  Pause translation", () -> setPaused(!paused));
        addMenuAction("✎  Write in Chinese", () -> {
            dismissDuckMenu();
            startActivity(new Intent(this, ComposeActivity.class).putExtra("return_to_app", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        });
        addMenuAction("↻  Refresh translation", this::manualScan);
        TextView close = menuText("✕  Close helper", true);
        close.setTextColor(0xffad3748);
        close.setOnClickListener(view -> stopNow());
        duckMenu.addView(close, new LinearLayout.LayoutParams(-1, dp(43)));
        menuParams = new WindowManager.LayoutParams(dp(206), -2, overlayParams.type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT);
        menuParams.gravity = Gravity.TOP | Gravity.LEFT;
        menuParams.x = duckParams.x + dp(28) < screenWidth / 2 ? duckParams.x + dp(64) : duckParams.x - dp(214);
        menuParams.x = Math.max(dp(8), Math.min(screenWidth - dp(214), menuParams.x));
        menuParams.y = Math.max(statusBarHeight + dp(8), Math.min(screenHeight - dp(258), duckParams.y - dp(20)));
        overlayWindowManager.addView(duckMenu, menuParams);
        duckMenu.post(this::updateProbeMasks);
        main.removeCallbacks(dismissMenuLater);
        main.postDelayed(dismissMenuLater, 12000);
    }

    private final Runnable dismissMenuLater = this::dismissDuckMenu;

    private TextView menuText(String value, boolean action) {
        TextView item = new TextView(this);
        item.setText(value);
        item.setTextSize(action ? 15 : 12);
        item.setTextColor(0xff27324a);
        item.setGravity(Gravity.CENTER_VERTICAL);
        item.setPadding(dp(7), dp(9), dp(7), dp(9));
        return item;
    }

    private void addMenuAction(String label, Runnable onClick) {
        TextView item = menuText(label, true);
        item.setOnClickListener(view -> onClick.run());
        duckMenu.addView(item, new LinearLayout.LayoutParams(-1, dp(43)));
    }

    private void dismissDuckMenu() {
        main.removeCallbacks(dismissMenuLater);
        if (duckMenu == null) return;
        lastControlMoveAt = SystemClock.uptimeMillis();
        try { overlayWindowManager.removeView(duckMenu); } catch (Exception ignored) { }
        duckMenu = null;
        menuParams = null;
        updateProbeMasks();
    }

    private void removeDuck() {
        duckDragging = false;
        dismissDuckMenu();
        if (dockAnimation != null) { dockAnimation.cancel(); dockAnimation = null; }
        if (duck == null) return;
        try { overlayWindowManager.removeView(duck); } catch (Exception ignored) { }
        duck = null;
        duckParams = null;
    }

    private final class DuckView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        DuckView(Context context) { super(context); }
        @Override public boolean performClick() { super.performClick(); return true; }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float w = getWidth(), h = getHeight();
            canvas.save();
            canvas.translate((getWidth() - w) / 2f, 0);
            paint.setColor(0xfff2f4f8);
            canvas.drawCircle(w * .5f, h * .5f, w * .49f, paint);
            paint.setColor(Color.WHITE);
            canvas.drawCircle(w * .5f, h * .5f, w * .45f, paint);
            paint.setColor(0xffdfe5ee);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(1));
            canvas.drawCircle(w * .5f, h * .5f, w * .45f, paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xffeff1f5);
            canvas.drawOval(w * .20f, h * .51f, w * .77f, h * .79f, paint);
            paint.setColor(Color.WHITE);
            canvas.drawOval(w * .19f, h * .49f, w * .75f, h * .75f, paint);
            canvas.drawCircle(w * .48f, h * .40f, w * .23f, paint);
            paint.setColor(0xff172032);
            canvas.drawCircle(w * .54f, h * .34f, dp(2), paint);
            paint.setColor(0xffffae38);
            android.graphics.Path beak = new android.graphics.Path();
            beak.moveTo(w * .65f, h * .43f);
            beak.lineTo(w * .85f, h * .47f);
            beak.lineTo(w * .65f, h * .51f);
            beak.close();
            canvas.drawPath(beak, paint);
            paint.setColor(paused ? 0xffffae38 : (modelReady ? 0xff65bc8c : 0xff5b4ade));
            canvas.drawCircle(w * .76f, h * .20f, dp(4), paint);
            canvas.restore();
        }
    }

    private void finishCycle() {
        main.removeCallbacks(captureTimeout);
        showOverlay();
        busy = false;
        MainActivity.onOverlayChanged();
        if (pendingRefresh) { pendingRefresh = false; scheduleCapture(); }

    }

    private void stopNow() {
        if (stopping) return;
        stopping = true;
        running = false;
        activeService = null;
        MainActivity.onOverlayChanged();
        captureRequested = false;
        capturePreparing = false;
        main.removeCallbacksAndMessages(null);
        removeDuck();
        removeOverlay();
        if (virtualDisplay != null) { virtualDisplay.release(); virtualDisplay = null; }
        if (imageReader != null) { imageReader.close(); imageReader = null; }
        if (projection != null) { projection.stop(); projection = null; }
        if (recognizer != null) { recognizer.close(); recognizer = null; }
        if (translator != null) { translator.close(); translator = null; }
        if (imageThread != null) { imageThread.quitSafely(); imageThread = null; }
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override public void onDestroy() {
        stopNow();
        super.onDestroy();
    }
}
