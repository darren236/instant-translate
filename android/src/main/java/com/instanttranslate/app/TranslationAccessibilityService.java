package com.instanttranslate.app;

import android.accessibilityservice.AccessibilityService;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

/** Hosts a fully opaque, touch-through translation overlay when enabled by the user. */
public final class TranslationAccessibilityService extends AccessibilityService {
    static volatile TranslationAccessibilityService connected;

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        connected = this;
        MainActivity.onAccessibilityChanged();
        OverlayService.onAccessibilityChanged();
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || (event.getPackageName() != null
            && getPackageName().contentEquals(event.getPackageName()))) return;
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
            OverlayService.onForegroundWindowChanged();
        else if (event.getEventType() == AccessibilityEvent.TYPE_VIEW_CLICKED)
            OverlayService.onScreenTap();
        else if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            || event.getEventType() == AccessibilityEvent.TYPE_VIEW_SCROLLED)
            OverlayService.onScreenInteraction();
    }

    @Override public void onInterrupt() { }

    @Override public boolean onUnbind(android.content.Intent intent) {
        connected = null;
        MainActivity.onAccessibilityChanged();
        OverlayService.onAccessibilityChanged();
        return super.onUnbind(intent);
    }

    @Override public void onDestroy() {
        connected = null;
        MainActivity.onAccessibilityChanged();
        OverlayService.onAccessibilityChanged();
        super.onDestroy();
    }

    WindowManager overlayWindowManager() {
        return (WindowManager) getSystemService(WINDOW_SERVICE);
    }
}
