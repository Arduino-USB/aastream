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

    private MediaProjection media_projection;
    private VirtualDisplay virtual_display;

    @Override
    public void onCreate() {
        super.onCreate();
        ScreenBridge.service = this;
        startForeground(1, create_notification());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int start_id) {
        Log.d("ScreenCaptureService", "SERVICE STARTED");

        if (intent != null) {
            int result_code = intent.getIntExtra("result_code", 0);
            Intent data_intent = intent.getParcelableExtra("data_intent");

            MediaProjectionManager manager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            media_projection = manager.getMediaProjection(result_code, data_intent);

            media_projection.registerCallback(new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    Log.d("ScreenCaptureService", "Projection stopped");
                    DisplayMgr.trigger(false);

                    if (virtual_display != null) {
                        virtual_display.release();
                        virtual_display = null;
                    }
                }
            }, null);
        }

        attach_if_ready();
        return START_STICKY;
    }

    public static void attach_if_ready() {
        if (ScreenBridge.service == null) return;
        ScreenBridge.service.start_display_if_possible();
    }

    private void start_display_if_possible() {
        if (media_projection == null) return;
        if (ScreenBridge.surface == null) return;

        if (virtual_display != null) {
            virtual_display.release();
        }

        virtual_display = media_projection.createVirtualDisplay(
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

    private Notification create_notification() {
        NotificationChannel channel = new NotificationChannel(
                "screen",
                "Screen Capture",
                NotificationManager.IMPORTANCE_LOW
        );

        getSystemService(NotificationManager.class).createNotificationChannel(channel);

        return new Notification.Builder(this, "screen")
                .setContentTitle("Screen streaming")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .build();
    }

    @Override
    public void onDestroy() {
        if (virtual_display != null) {
            virtual_display.release();
            virtual_display = null;
        }
        if (media_projection != null) {
            media_projection.stop();
            media_projection = null;
        }
        DisplayMgr.trigger(false);
        ScreenBridge.service = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
