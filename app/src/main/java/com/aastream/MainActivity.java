package com.aastream;

import android.app.Activity;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.view.TextureView;
import android.view.Surface;
import android.graphics.SurfaceTexture;
import android.widget.Button;
import android.content.Context;

public class MainActivity extends Activity {

    private static final int REQ = 1000;
    private MediaProjectionManager manager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        manager = (MediaProjectionManager)
                getSystemService(Context.MEDIA_PROJECTION_SERVICE);

        // ✔ TextureView MUST be here
        TextureView tv = findViewById(R.id.screen_cast);

        tv.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {

            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {

                ScreenBridge.surface = new Surface(st);
                ScreenBridge.width = w;
                ScreenBridge.height = h;

                ScreenCaptureService.attachIfReady();
            }

            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {}

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
                ScreenBridge.surface = null;
                return true;
            }

            @Override public void onSurfaceTextureUpdated(SurfaceTexture st) {}
        });

        Button start = findViewById(R.id.start_stream);

        start.setOnClickListener(v -> {
            Intent i = manager.createScreenCaptureIntent();
            startActivityForResult(i, REQ);
        });
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);

        if (req == REQ && res == RESULT_OK && data != null) {

            Intent svc = new Intent(this, ScreenCaptureService.class);
            svc.putExtra("resultCode", res);
            svc.putExtra("data", data);

            startForegroundService(svc);
        }
    }
}
