package com.aastream;

import android.app.Service;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;

import android.content.Intent;
import android.content.Context;

import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;

import android.hardware.display.VirtualDisplay;
import android.hardware.display.DisplayManager;

import android.os.IBinder;
import android.util.Log;

import com.aastream.car.DisplayMgr;

public class ScreenCaptureService extends Service {

    private MediaProjection projection;
    private VirtualDisplay display;

    @Override
    public void onCreate() {
        super.onCreate();

        ScreenBridge.service = this;

        startForeground(1, createNotification());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        Log.d("ScreenCaptureService", "SERVICE STARTED");

        int resultCode = intent.getIntExtra("resultCode", 0);
        Intent data = intent.getParcelableExtra("data");

        MediaProjectionManager manager =
                (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);

        projection = manager.getMediaProjection(resultCode, data);

        projection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                Log.d("ScreenCaptureService", "Projection stopped");
                DisplayMgr.trigger(false);

                if (display != null) {
                    display.release();
                    display = null;
                }
            }
        }, null);

        attachIfReady();

        return START_STICKY;
    }

    public static void attachIfReady() {
        if (ScreenBridge.service == null) return;
        ScreenBridge.service.startDisplayIfPossible();
    }

    private void startDisplayIfPossible() {

        if (projection == null) return;
        if (ScreenBridge.surface == null) return;

        if (display != null) {
            display.release();
        }

        display = projection.createVirtualDisplay(
                "ScreenCapture",
                ScreenBridge.width,
                ScreenBridge.height,
                320,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                ScreenBridge.surface,
                null,
                null
        );

        DisplayMgr.trigger(true);
    }

    private Notification createNotification() {

        NotificationChannel channel = new NotificationChannel(
                "screen",
                "Screen Capture",
                NotificationManager.IMPORTANCE_LOW
        );

        getSystemService(NotificationManager.class)
                .createNotificationChannel(channel);

        return new Notification.Builder(this, "screen")
                .setContentTitle("Screen streaming")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .build();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
