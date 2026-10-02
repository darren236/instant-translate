package com.instanttranslate.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.media.projection.MediaProjectionConfig;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.lang.ref.WeakReference;

/** Session launcher with one start action and optional setup details. */
public final class MainActivity extends Activity {
    private static volatile WeakReference<MainActivity> visibleActivity = new WeakReference<>(null);
    private static final int BACKGROUND = 0xfff7f8fc;
    private static final int INK = 0xff1c2234;
    private static final int MUTED = 0xff636b7e;
    private static final int BORDER = 0xffe2e6ef;
    private static final int ACCENT = 0xff5b4ade;
    private static final int ACCENT_PALE = 0xfff3f1ff;
    private static final int GREEN = 0xff148b5d;
    private static final int REQUEST_OVERLAY = 1;
    private static final int REQUEST_CAPTURE = 2;
    private static final int REQUEST_NOTIFICATIONS = 3;
    private static final String PREFERENCES = "home_preferences";
    private static final String NOTIFICATIONS_ASKED = "notifications_asked";

    private final Handler main = new Handler(Looper.getMainLooper());
    private ScrollView scroll;
    private TextView state;
    private TextView sessionTitle;
    private TextView primary;
    private TextView stop;
    private TextView seamless;
    private TextView notifications;
    private TextView setupToggle;
    private LinearLayout setupDetails;
    private boolean startPending;
    private boolean waitingForOverlay;
    private boolean waitingForNotifications;
    private boolean waitingForCapture;
    private boolean launching;
    private boolean setupExpanded;
    private String message = "";
    private final Runnable launchTimeout = () -> {
        if (launching && !OverlayService.running) {
            launching = false;
            message = "Couldn’t start translation. Please try again.";
            updateState();
        }
    };

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private GradientDrawable shape(int fill, int radius, int stroke) {
        GradientDrawable result = new GradientDrawable();
        result.setColor(fill);
        result.setCornerRadius(dp(radius));
        result.setStroke(dp(1), stroke);
        return result;
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView result = new TextView(this);
        result.setText(value);
        result.setTextSize(size);
        result.setTextColor(color);
        result.setIncludeFontPadding(false);
        result.setTypeface(Typeface.create(bold ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        return result;
    }

    private LinearLayout vertical() {
        LinearLayout result = new LinearLayout(this);
        result.setOrientation(LinearLayout.VERTICAL);
        return result;
    }

    private LinearLayout.LayoutParams spaced(int height, int top) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, height);
        params.topMargin = dp(top);
        return params;
    }

    private TextView action(String title, int background, int color, View.OnClickListener listener) {
        TextView result = text(title, 15, color, true);
        result.setGravity(Gravity.CENTER);
        result.setPadding(dp(12), dp(12), dp(12), dp(12));
        result.setMinHeight(dp(50));
        result.setBackground(new RippleDrawable(ColorStateList.valueOf(0x225b4ade),
            shape(background, 14, background), null));
        result.setClickable(true);
        result.setFocusable(true);
        result.setOnClickListener(listener);
        return result;
    }

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        if (saved != null) {
            startPending = saved.getBoolean("start_pending");
            waitingForOverlay = saved.getBoolean("waiting_overlay");
            waitingForNotifications = saved.getBoolean("waiting_notifications");
            waitingForCapture = saved.getBoolean("waiting_capture");
            launching = saved.getBoolean("launching");
            setupExpanded = saved.getBoolean("setup_expanded");
            message = saved.getString("message", "");
        }
        getWindow().setStatusBarColor(BACKGROUND);
        getWindow().setNavigationBarColor(BACKGROUND);
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        buildUi();
        if (saved != null) scroll.post(() -> scroll.scrollTo(0, saved.getInt("scroll_y")));
    }

    private void buildUi() {
        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BACKGROUND);
        setContentView(scroll);
        LinearLayout root = vertical();
        root.setPadding(dp(20), dp(18), dp(20), dp(28));
        scroll.addView(root);

        LinearLayout brand = new LinearLayout(this);
        brand.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(brand, new LinearLayout.LayoutParams(-1, dp(48)));
        ImageView mark = new ImageView(this);
        mark.setImageResource(R.drawable.ic_launcher);
        mark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        brand.addView(mark, new LinearLayout.LayoutParams(dp(44), dp(44)));
        LinearLayout heading = vertical();
        LinearLayout.LayoutParams headingParams = new LinearLayout.LayoutParams(0, -2, 1);
        headingParams.leftMargin = dp(12);
        brand.addView(heading, headingParams);
        heading.addView(text("Instant Translate", 20, INK, true));
        heading.addView(text(versionLabel() + "  ·  On your screen", 11, MUTED, false));

        TextView eyebrow = text("CHINESE → ENGLISH", 11, ACCENT, true);
        eyebrow.setLetterSpacing(.08f);
        root.addView(eyebrow, spaced(-2, 26));
        TextView title = text("Read Chinese.\nKeep shopping.", 30, INK, true);
        title.setLineSpacing(dp(2), 1f);
        root.addView(title, spaced(-2, 9));
        TextView intro = text("English appears over the Chinese text. Scroll and tap as usual.", 15, MUTED, false);
        intro.setLineSpacing(dp(3), 1f);
        root.addView(intro, spaced(-2, 10));

        LinearLayout session = vertical();
        session.setPadding(dp(18), dp(18), dp(18), dp(18));
        session.setBackground(shape(Color.WHITE, 20, BORDER));
        root.addView(session, spaced(-2, 22));
        sessionTitle = text("Ready when you are", 19, INK, true);
        session.addView(sessionTitle);
        state = text("Share your screen, then open Taobao or any Chinese app.", 13, MUTED, false);
        state.setLineSpacing(dp(3), 1f);
        state.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        session.addView(state, spaced(-2, 8));
        primary = action("Start translating", ACCENT, Color.WHITE, v -> {
            if (OverlayService.running) {
                if (OverlayService.isPaused())
                    startService(new Intent(this, OverlayService.class).setAction(OverlayService.ACTION_RESUME));
                moveTaskToBack(true);
            } else startOverlay();
        });
        session.addView(primary, spaced(-2, 16));
        stop = action("Stop translation", ACCENT_PALE, ACCENT, v -> stopOverlay());
        session.addView(stop, spaced(-2, 8));
        TextView duckTip = text("Tap the floating duck to pause, translate a message, or stop.", 12, MUTED, false);
        duckTip.setLineSpacing(dp(2), 1f);
        session.addView(duckTip, spaced(-2, 12));

        LinearLayout compose = vertical();
        compose.setPadding(dp(18), dp(18), dp(18), dp(18));
        compose.setBackground(shape(Color.WHITE, 20, BORDER));
        root.addView(compose, spaced(-2, 14));
        compose.addView(text("Message a seller", 17, INK, true));
        TextView composeCopy = text("Write in English. Copy the Chinese into Taobao.", 13, MUTED, false);
        composeCopy.setLineSpacing(dp(3), 1f);
        compose.addView(composeCopy, spaced(-2, 7));
        compose.addView(action("Write in Chinese  →", ACCENT_PALE, ACCENT,
            v -> startActivity(new Intent(this, ComposeActivity.class))), spaced(-2, 13));

        setupToggle = action("Setup & permissions  ▾", BACKGROUND, MUTED, v -> {
            setupExpanded = !setupExpanded;
            updateSetup();
        });
        root.addView(setupToggle, spaced(-2, 13));
        setupDetails = vertical();
        setupDetails.setPadding(dp(14), dp(5), dp(14), dp(12));
        root.addView(setupDetails, new LinearLayout.LayoutParams(-1, -2));
        TextView setupCopy = text("Optional: enable Accessibility for solid text overlays and more reliable refreshes. Screen sharing is still required each session.", 12, MUTED, false);
        setupCopy.setLineSpacing(dp(3), 1f);
        setupDetails.addView(setupCopy);
        seamless = action("Improve text overlays  →", ACCENT_PALE, ACCENT,
            v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        setupDetails.addView(seamless, spaced(-2, 12));
        notifications = action("Notification settings  →", Color.WHITE, MUTED, v -> {
            Intent intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
            intent.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
            startActivity(intent);
        });
        setupDetails.addView(notifications, spaced(-2, 8));
        TextView privacy = text("Screen text is processed on this device. The first use needs internet to download the language packs.", 12, MUTED, false);
        privacy.setLineSpacing(dp(3), 1f);
        root.addView(privacy, spaced(-2, 13));
        root.addView(action("About translation", BACKGROUND, ACCENT, v -> showInfo()), spaced(-2, 5));
        updateSetup();
        updateState();
    }

    private void updateSetup() {
        setupDetails.setVisibility(setupExpanded ? View.VISIBLE : View.GONE);
        setupToggle.setText(setupExpanded ? "Setup & permissions  ▴" : "Setup & permissions  ▾");
        setupToggle.setContentDescription(setupExpanded
            ? "Collapse setup and permissions" : "Expand setup and permissions");
    }

    private void showInfo() {
        new AlertDialog.Builder(this)
            .setTitle("Translation by Google")
            .setMessage("This app uses Google ML Kit for on-device OCR and translation. A language pack is downloaded on first use. Captured images are processed in memory and are not saved. Android blocks capture of protected screens, and text in fast-moving or small images may be missed.\n\nTHIS SERVICE MAY CONTAIN TRANSLATIONS POWERED BY GOOGLE. GOOGLE DISCLAIMS ALL WARRANTIES RELATED TO THE TRANSLATIONS, EXPRESS OR IMPLIED, INCLUDING ANY WARRANTIES OF ACCURACY, RELIABILITY, AND ANY IMPLIED WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.")
            .setPositiveButton("Got it", null)
            .show();
    }

    private String versionLabel() {
        try {
            return "v" + getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException error) {
            return "";
        }
    }

    private void startOverlay() {
        if (startPending || launching) return;
        startPending = true;
        message = "";
        continueStartFlow();
    }

    private void continueStartFlow() {
        if (!startPending || waitingForOverlay || waitingForNotifications || waitingForCapture) return;
        if (TranslationAccessibilityService.connected == null && !Settings.canDrawOverlays(this)) {
            waitingForOverlay = true;
            message = "Allow display over other apps, then return here.";
            updateState();
            startActivityForResult(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName())), REQUEST_OVERLAY);
            return;
        }
        // Notification denial is optional and must not prompt again on every session.
        if (Build.VERSION.SDK_INT >= 33
            && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            && !getSharedPreferences(PREFERENCES, MODE_PRIVATE).getBoolean(NOTIFICATIONS_ASKED, false)) {
            waitingForNotifications = true;
            getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit().putBoolean(NOTIFICATIONS_ASKED, true).apply();
            message = "Notifications are optional. They add a quick Stop control.";
            updateState();
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS);
            return;
        }
        requestCapture();
    }

    private void requestCapture() {
        if (!startPending || waitingForCapture) return;
        waitingForCapture = true;
        message = "Choose full-screen sharing to start translation.";
        updateState();
        MediaProjectionManager manager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        Intent intent = Build.VERSION.SDK_INT >= 34
            ? manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
            : manager.createScreenCaptureIntent();
        startActivityForResult(intent, REQUEST_CAPTURE);
    }

    private void stopOverlay() {
        startService(new Intent(this, OverlayService.class).setAction(OverlayService.ACTION_STOP));
        message = "";
        state.setText("Stopping translation…");
        stop.setEnabled(false);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_OVERLAY) {
            waitingForOverlay = false;
            if (TranslationAccessibilityService.connected != null || Settings.canDrawOverlays(this)) {
                continueStartFlow();
            } else {
                startPending = false;
                message = "Display over other apps is needed. Tap Start to try again.";
                updateState();
            }
        } else if (requestCode == REQUEST_CAPTURE) {
            waitingForCapture = false;
            startPending = false;
            if (resultCode != RESULT_OK || data == null) {
                message = "Screen sharing was cancelled. Start whenever you’re ready.";
                updateState();
                return;
            }
            launching = true;
            message = "Starting translation…";
            updateState();
            Intent service = new Intent(this, OverlayService.class).setAction(OverlayService.ACTION_START);
            service.putExtra(OverlayService.EXTRA_RESULT_CODE, resultCode);
            service.putExtra(OverlayService.EXTRA_RESULT_DATA, data);
            startForegroundService(service);
            main.postDelayed(launchTimeout, 4000);
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQUEST_NOTIFICATIONS) {
            waitingForNotifications = false;
            continueStartFlow();
        }
    }

    @Override protected void onSaveInstanceState(Bundle saved) {
        saved.putBoolean("start_pending", startPending);
        saved.putBoolean("waiting_overlay", waitingForOverlay);
        saved.putBoolean("waiting_notifications", waitingForNotifications);
        saved.putBoolean("waiting_capture", waitingForCapture);
        saved.putBoolean("launching", launching);
        saved.putBoolean("setup_expanded", setupExpanded);
        saved.putString("message", message);
        saved.putInt("scroll_y", scroll.getScrollY());
        super.onSaveInstanceState(saved);
    }

    static void onAccessibilityChanged() { onOverlayChanged(); }

    static void onOverlayChanged() {
        MainActivity activity = visibleActivity.get();
        if (activity != null) activity.runOnUiThread(activity::updateState);
    }

    @Override protected void onResume() {
        super.onResume();
        visibleActivity = new WeakReference<>(this);
        updateState();
        if (launching) main.postDelayed(launchTimeout, 4000);
    }

    @Override protected void onPause() {
        if (visibleActivity.get() == this) visibleActivity = new WeakReference<>(null);
        super.onPause();
    }

    @Override protected void onDestroy() {
        main.removeCallbacks(launchTimeout);
        super.onDestroy();
    }

    private void updateState() {
        if (state == null) return;
        boolean active = OverlayService.running;
        boolean pending = startPending || launching;
        seamless.setText(TranslationAccessibilityService.connected == null
            ? "Improve text overlays  →" : "Accessibility enabled  ✓");
        notifications.setText(Build.VERSION.SDK_INT < 33
            || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            ? "Notification settings  →" : "Notifications off · manage  →");
        primary.setEnabled(!pending || active);
        primary.setAlpha(pending && !active ? .65f : 1f);
        stop.setVisibility(active ? View.VISIBLE : View.GONE);
        stop.setEnabled(true);
        if (active) {
            String status = OverlayService.sessionStatus();
            sessionTitle.setText(OverlayService.isPaused() ? "Translation paused"
                : status.startsWith("Preparing") ? "Preparing translation"
                : status.startsWith("Downloading") ? "Downloading language pack"
                : "Translation is running");
            state.setText(status);
            state.setTextColor(OverlayService.isPaused() ? MUTED : GREEN);
            primary.setText("Continue translating");
            message = "";
            if (launching) {
                launching = false;
                main.removeCallbacks(launchTimeout);
                moveTaskToBack(true);
            }
        } else {
            sessionTitle.setText(pending ? "Getting ready…" : "Ready when you are");
            state.setText(message.isEmpty() ? "Share your screen, then open Taobao or any Chinese app." : message);
            state.setTextColor(MUTED);
            primary.setText(pending ? "Starting…" : "Start translating");
        }
    }
}
