package com.aastream;

import android.app.Activity;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.widget.Button;
import android.content.Context;

public class MainActivity extends Activity {

    private static final int REQ_CODE = 1000;
    private MediaProjectionManager projection_manager;
    private Button btn_toggle;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        projection_manager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        btn_toggle = findViewById(R.id.btn_toggle_stream);

        update_button_state();

        btn_toggle.setOnClickListener(v -> {
            if (ScreenBridge.service != null) {
                Intent svc = new Intent(this, ScreenCaptureService.class);
                stopService(svc);
                ScreenBridge.service = null;
                update_button_state();
            } else {
                Intent i = projection_manager.createScreenCaptureIntent();
                startActivityForResult(i, REQ_CODE);
            }
        });
    }

    private void update_button_state() {
        if (ScreenBridge.service != null) {
            btn_toggle.setText("Stop");
        } else {
            btn_toggle.setText("Start");
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);

        if (req == REQ_CODE && res == RESULT_OK && data != null) {
            Intent svc = new Intent(this, ScreenCaptureService.class);
            svc.putExtra("result_code", res);
            svc.putExtra("data_intent", data);

            startForegroundService(svc);
            btn_toggle.setText("Stop");
        }
    }
}
