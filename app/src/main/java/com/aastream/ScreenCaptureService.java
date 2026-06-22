package com.aastream;

import android.app.Service;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.Context;
import android.content.pm.ServiceInfo;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.hardware.display.VirtualDisplay;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import com.aastream.car.DisplayMgr;

public class ScreenCaptureService extends Service {

    private static final String TAG = "AAStreamDebug";
    private MediaProjection media_projection;
    private VirtualDisplay virtual_display;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "[ScreenCaptureService] onCreate() invoked");
        ScreenBridge.service = this;
        
        Log.d(TAG, "[ScreenCaptureService] Registering foreground type bindings matching current API platform level");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Log.d(TAG, "[ScreenCaptureService] API level 29+; mapping FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION");
            startForeground(1, create_notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            Log.d(TAG, "[ScreenCaptureService] Legacy platform API; invoking classic startForeground");
            startForeground(1, create_notification());
        }
        Log.d(TAG, "[ScreenCaptureService] Foreground context initialization step finalized");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int start_id) {
        String action = intent != null ? intent.getAction() : "NULL_INTENT_ACTION";
        Log.d(TAG, "[ScreenCaptureService] onStartCommand triggered with Action: " + action + ", Flags: " + flags + ", StartId: " + start_id);

        if (intent != null && "COMMAND_START_STREAM".equals(intent.getAction())) {
            int result_code = intent.getIntExtra("result_code", 0);
            Intent data_intent = intent.getParcelableExtra("data_intent");
            Log.d(TAG, "[ScreenCaptureService] Unpacking Intent values -> Result Code: " + result_code + ", Intent payload exists: " + (data_intent != null));

            if (data_intent != null) {
                MediaProjectionManager manager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
                Log.d(TAG, "[ScreenCaptureService] Querying system for MediaProjection instance token allocation");
                media_projection = manager.getMediaProjection(result_code, data_intent);
                Log.i(TAG, "[ScreenCaptureService] Token allocated successfully. Token Instance: " + (media_projection != null));

                if (media_projection != null) {
                    Log.d(TAG, "[ScreenCaptureService] Subscribing tracking listener callbacks to media projection token lifecycle");
                    media_projection.registerCallback(new MediaProjection.Callback() {
                        @Override
                        public void onStop() {
                            Log.w(TAG, "[ScreenCaptureService Callback] Core hardware MediaProjection token received teardown onStop() notification command from Android OS!");
                            DisplayMgr.trigger(false);

                            if (virtual_display != null) {
                                Log.d(TAG, "[ScreenCaptureService Callback] Tearing down trailing screen mirror virtual display instances");
                                virtual_display.release();
                                virtual_display = null;
                            }
                        }
                    }, null);
                }
                
                Log.d(TAG, "[ScreenCaptureService] Driving pipeline directly into attachment confirmation validation blocks");
                attach_if_ready();
            } else {
                Log.e(TAG, "[ScreenCaptureService] Processing error: intent action was COMMAND_START_STREAM but extraction yielded null projection data components!");
            }
        } else {
            Log.d(TAG, "[ScreenCaptureService] Service handled generic start routing strategy configuration steps");
        }
        return START_STICKY;
    }

    public static void attach_if_ready() {
        Log.d(TAG, "[ScreenCaptureService] Static attach_if_ready() entry check invoked");
        if (ScreenBridge.service == null) {
            Log.w(TAG, "[ScreenCaptureService] Aborted attachment processing route: Global reference ScreenBridge.service is currently NULL");
            return;
        }
        ScreenBridge.service.start_display_if_possible();
    }

	// Inside com.aastream.ScreenCaptureService.java

	public void start_display_if_possible() {
	    Log.d("AAStreamDebug", "Static attach_if_ready() entry check invoked");
	    Log.d("AAStreamDebug", "Running start_display_if_possible constraints evaluation sequence");
	    
	    // Explicitly check what references are hot in the logs to see exactly what condition fails
	    Log.d("AAStreamDebug", "Evaluation status properties -> media_projection exists: " 
	            + (media_projection != null) + ", ScreenBridge.surface exists: " + (ScreenBridge.surface != null));
	
	    if (media_projection == null) {
	        Log.w("AAStreamDebug", "Target constraints failed: MediaProjection token is null");
	        return;
	    }
	
	    if (ScreenBridge.surface == null) {
	        Log.w("AAStreamDebug", "Target constraints failed: ScreenBridge Surface target is not fully instantiated yet");
	        return;
	    }
	
	    // Tearing down old display allocations if they exist
	    if (virtual_display != null) {
	        virtual_display.release();
	    }
	
	    Log.i("AAStreamDebug", "ALL CONSTRAINTS PASSED! Binding MediaProjection to Surface target.");
	    
	    // Bind your device screen to the hot surface bridge
	    virtual_display = media_projection.createVirtualDisplay(
	            "ScreenCapture",
	            ScreenBridge.width > 0 ? ScreenBridge.width : 800,
	            ScreenBridge.height > 0 ? ScreenBridge.height : 400,
	            320, // DPI density
	            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
	            ScreenBridge.surface, // This routes frames directly into the active layout viewport
	            null,
	            null
	    );
	
	    // Tell the presentation overlay that the mirror stream is hot!
	    DisplayMgr.trigger(true);
	}

    private Notification create_notification() {
        Log.d(TAG, "[ScreenCaptureService] Building tracking notifications layer context packages");
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
        Log.w(TAG, "[ScreenCaptureService] onDestroy service engine shutdown command registered!");
        if (virtual_display != null) {
            Log.d(TAG, "[ScreenCaptureService] Releasing VirtualDisplay mapping instances gracefully");
            virtual_display.release();
            virtual_display = null;
        }
        if (media_projection != null) {
            Log.d(TAG, "[ScreenCaptureService] Destroying MediaProjection core token session allocations");
            media_projection.stop();
            media_projection = null;
        }
        Log.d(TAG, "[ScreenCaptureService] Forcing Android Auto presentation layout display views clear formatting models");
        DisplayMgr.trigger(false);
        ScreenBridge.service = null;
        super.onDestroy();
        Log.w(TAG, "[ScreenCaptureService] Teardown execution completely wrapped up");
    }

    @Override
    public IBinder onBind(Intent intent) {
        Log.d(TAG, "[ScreenCaptureService] onBind component routing loop query captured");
        return null;
    }
}
