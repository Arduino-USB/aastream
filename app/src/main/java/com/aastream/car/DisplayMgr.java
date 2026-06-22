package com.aastream.car;

import android.hardware.display.VirtualDisplay;
import android.view.Surface;
import android.content.Context;
import android.app.Presentation;
import android.os.Handler;
import android.os.Looper;
import android.widget.TextView;
import android.view.TextureView;
import android.graphics.SurfaceTexture;
import android.view.View;
import android.util.Log;
import com.aastream.R;
import com.aastream.ScreenBridge;
import com.aastream.ScreenCaptureService;

public class DisplayMgr {
    
    public static VirtualDisplay display = null;
    private static Presentation presentation = null; 
    private static final String TAG = "DisplayMgr";
    private static TextView text_view = null;
    private static TextureView texture_view = null;

    public DisplayMgr(Context context) {
        new Thread(() -> {
            while(true) {    
                try {
                    Thread.sleep(500);
                } catch (Exception e) {
                    Log.e(TAG, "[DisplayMgr] Something went wrong sleeping", e);
                }
                
                if (display != null) {
                    Log.d(TAG, "[DisplayMgr] Display created");
                    new Handler(Looper.getMainLooper()).post(() -> manage_screen(context));
                    break;                
                }
                Log.d(TAG, "[DisplayMgr] Not created yet");
            }
        }).start();
    }
	
    public static void trigger(boolean state){
        new Handler(Looper.getMainLooper()).post(() -> {
            if (state) {
                if (text_view != null) text_view.setVisibility(View.GONE);
                if (texture_view != null) texture_view.setVisibility(View.VISIBLE);
            } else {
                if (text_view != null) text_view.setVisibility(View.VISIBLE);
                if (texture_view != null) texture_view.setVisibility(View.GONE);
            }
        });
    }
		
    private static void manage_screen(Context context) {
        presentation = new Presentation(context, display.getDisplay());    

        presentation.setContentView(R.layout.aascreen_layout);
        text_view = presentation.findViewById(R.id.text_view);
        texture_view = presentation.findViewById(R.id.screen_cast);

        texture_view.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
                ScreenBridge.surface = new Surface(st);
                ScreenBridge.width = w;
                ScreenBridge.height = h;

                ScreenCaptureService.attach_if_ready();
            }

            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {}

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
                ScreenBridge.surface = null;
                DisplayMgr.trigger(false);
                return true;
            }

            @Override public void onSurfaceTextureUpdated(SurfaceTexture st) {}
        });

        presentation.show(); 
        
        if (ScreenBridge.service != null) {
            ScreenCaptureService.attach_if_ready();
        }
    }

    public static void create_display(VirtualDisplay incoming_display) {
        DisplayMgr.display = incoming_display; 
    }
    
    public static boolean display_created(){
        return display != null; 
    }
        
    public static void apply_surface(Surface surface) {
        if (display != null) {
            display.setSurface(surface);
            if (presentation != null && !presentation.isShowing()) {
                presentation.show();
            }
        }
    }
}
