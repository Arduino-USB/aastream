package com.aastream;

import android.app.Activity;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.widget.Button;
import android.content.Context;
import android.util.Log;

public class MainActivity extends Activity {

    private static final String TAG = "AAStreamDebug";
    private static final int REQ_CODE = 1000;
    private MediaProjectionManager projection_manager;
    private Button btn_toggle;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d(TAG, "[MainActivity] onCreate() lifecycle initiated");
        setContentView(R.layout.activity_main);

        projection_manager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        btn_toggle = findViewById(R.id.btn_toggle_stream);
        Log.d(TAG, "[MainActivity] UI Views inflated. Current global Service reference active status: " + (ScreenBridge.service != null));

        update_button_state();

        btn_toggle.setOnClickListener(v -> {
            Log.d(TAG, "[MainActivity] Toggle streaming button interface click intercepted");
            if (ScreenBridge.service != null) {
                Log.d(TAG, "[MainActivity] User requested manual stop; executing service instance halt procedures");
                Intent svc = new Intent(this, ScreenCaptureService.class);
                stopService(svc);
                ScreenBridge.service = null;
                update_button_state();
            } else {
                Log.i(TAG, "[MainActivity] Initiating deployment sequence. Spin lock stage 1: Deploy Foreground target payload context");
                Intent svc = new Intent(this, ScreenCaptureService.class);
                svc.setAction("START_PROJECTION_PREP");
                startForegroundService(svc);
                
                Log.i(TAG, "[MainActivity] Spin lock stage 2: Requesting runtime projection authorization system token prompt via startActivityForResult");
                Intent i = projection_manager.createScreenCaptureIntent();
                startActivityForResult(i, REQ_CODE);
            }
        });
    }

    private void update_button_state() {
        if (ScreenBridge.service != null) {
            btn_toggle.setText("Stop");
            Log.v(TAG, "[MainActivity] Button label applied state -> [Stop]");
        } else {
            btn_toggle.setText("Start");
            Log.v(TAG, "[MainActivity] Button label applied state -> [Start]");
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        Log.d(TAG, "[MainActivity] onActivityResult caught -> Req: " + req + ", Res: " + res + ", Payload exists: " + (data != null));

        if (req == REQ_CODE) {
            if (res == RESULT_OK && data != null) {
                Log.i(TAG, "[MainActivity] Permission token verification checks PASSED by user interaction rules");
                Log.d(TAG, "[MainActivity] Relaying verified context tracking data into the target background foreground service layer");
                
                Intent svc = new Intent(this, ScreenCaptureService.class);
                svc.setAction("COMMAND_START_STREAM");
                svc.putExtra("result_code", res);
                svc.putExtra("data_intent", data);
                startForegroundService(svc);
                btn_toggle.setText("Stop");
            } else {
                Log.w(TAG, "[MainActivity] User declined permission mapping interface request rules or pressed back! Aborting operations.");
                Log.d(TAG, "[MainActivity] Compiling clean teardown of baseline preparation service properties models");
                Intent svc = new Intent(this, ScreenCaptureService.class);
                stopService(svc);
                update_button_state();
            }
        }
    }
}
