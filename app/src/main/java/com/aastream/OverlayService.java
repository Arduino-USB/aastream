package com.aastream; // Adjusted package name based on your compiler setup

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.IBinder;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import androidx.core.app.NotificationCompat;

public class OverlayService extends Service {
    private WindowManager windowManager;
    private View overlayView;
    private static boolean isOverlayActive = false;
    private static final String CHANNEL_ID = "aa_screen_overlay_channel";
    private static final int NOTIFICATION_ID = 9999;

    public static boolean isActive() {
        return isOverlayActive;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        android.util.Log.d("AAStreamDebug", "[OverlayService] onStartCommand intercepted. Action: " + (intent != null ? intent.getAction() : "null"));
        
        if (intent != null && "TOGGLE".equals(intent.getAction())) {
            if (isOverlayActive) {
                removeOverlay();
            } else {
                showOverlay();
            }
        }
        return START_NOT_STICKY;
    }

    private void showOverlay() {
        if (isOverlayActive) return;
        android.util.Log.d("AAStreamDebug", "[OverlayService] Injecting system overlay and dimming backlight...");

        // 1. Instantly promote to Foreground Service to bypass background limits
        createNotificationChannel();
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("AAStream Screen Dimmer")
                .setContentText("Phone screen interaction is locked.")
                .setSmallIcon(android.R.drawable.ic_secure)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
        
        startForeground(NOTIFICATION_ID, notification);

        // 2. Build and attach the window layer
        windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        overlayView = new FrameLayout(this);
        overlayView.setBackgroundColor(Color.TRANSPARENT);
        overlayView.setOnTouchListener((v, event) -> true); // Eat touch events

        int layoutFlag;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            layoutFlag = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        } else {
            layoutFlag = WindowManager.LayoutParams.TYPE_PHONE;
        }

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                layoutFlag,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | 
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
        );

        params.gravity = Gravity.CENTER;
        params.screenBrightness = 0.0f; // Kill backlight

        try {
            windowManager.addView(overlayView, params);
            isOverlayActive = true;
            android.util.Log.d("AAStreamDebug", "[OverlayService] Overlay deployed successfully!");
        } catch (Exception e) {
            android.util.Log.e("AAStreamDebug", "[OverlayService] Failed to bind window overlay layer", e);
        }
    }

    private void removeOverlay() {
        if (!isOverlayActive) return;
        android.util.Log.d("AAStreamDebug", "[OverlayService] Tearing down overlay and restoring backlight...");
        
        if (windowManager != null && overlayView != null) {
            windowManager.removeView(overlayView);
        }
        isOverlayActive = false;
        stopForeground(true);
        stopSelf();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    "Screen Dimmer Service Channel",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(serviceChannel);
            }
        }
    }

    @Override
    public void onDestroy() {
        removeOverlay();
        super.onDestroy();
    }
}
