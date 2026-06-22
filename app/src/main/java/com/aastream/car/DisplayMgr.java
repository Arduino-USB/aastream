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
    private static final String TAG = "AAStreamDebug";
    private static TextView text_view = null;
    private static TextureView texture_view = null;
    private static boolean lastKnownState = false;

    public DisplayMgr(Context context) {
        Log.d(TAG, "[DisplayMgr] Initializing background loop thread...");
        new Thread(() -> {
            while(true) {    
                try {
                    Thread.sleep(500);
                } catch (Exception e) {
                    Log.e(TAG, "[DisplayMgr] Something went wrong sleeping", e);
                }
                
                if (display != null) {
                    Log.d(TAG, "[DisplayMgr] Loop detected Android Auto VirtualDisplay instance is hot!");
                    new Handler(Looper.getMainLooper()).post(() -> manage_screen(context));
                    break;                
                }
            }
        }).start();
    }
		
	public static void trigger(boolean state){
	    Log.d(TAG, "[DisplayMgr] trigger state: " + state);
	    lastKnownState = state;
	    new Handler(Looper.getMainLooper()).post(() -> {
	        if (text_view != null && texture_view != null) {
	            // Keep texture_view VISIBLE so Android always builds the hardware surface context
	            texture_view.setVisibility(View.VISIBLE); 
	            
	            if (state) {
	                // Hide the text overlay to show the video stream underneath
	                text_view.setVisibility(View.GONE);
	            } else {
	                // Show the text overlay placeholder on top of the idle stream
	                text_view.setVisibility(View.VISIBLE);
	            }
	        }
	    });
	}
    private static void manage_screen(Context context) {
        if (display == null) return;
        
        try {
            Log.d(TAG, "[DisplayMgr] Constructing Presentation overlay on AA display context");
            Context displayContext = context.createDisplayContext(display.getDisplay());
            
            presentation = new Presentation(displayContext, display.getDisplay());    
            presentation.setContentView(R.layout.aascreen_layout);
            
            text_view = presentation.findViewById(R.id.text_view);
            texture_view = presentation.findViewById(R.id.screen_cast);

            trigger(lastKnownState);

            texture_view.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
                @Override
                public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
                    Log.i(TAG, "[DisplayMgr] onSurfaceTextureAvailable event fired! Surface is ready.");
                    ScreenBridge.surface = new Surface(st);
                    ScreenBridge.width = w;
                    ScreenBridge.height = h;
                    
                    // Explicitly tell our service constraints checker that we are ready to roll!
                    if (ScreenBridge.service != null) {
                        Log.d(TAG, "[DisplayMgr] Service reference found during surface creation, poking attach loop");
                        // If your service side uses a non-static method, update this block to run via intent or service reference direct invocation
                        ScreenBridge.service.start_display_if_possible(); 
                    }
                }

                @Override 
                public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {
                    ScreenBridge.width = w;
                    ScreenBridge.height = h;
                }

                @Override
                public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
                    Log.w(TAG, "[DisplayMgr] onSurfaceTextureDestroyed event fired");
                    ScreenBridge.surface = null;
                    DisplayMgr.trigger(false);
                    return true;
                }

                @Override 
                public void onSurfaceTextureUpdated(SurfaceTexture st) {}
            });

            presentation.show(); 
            Log.i(TAG, "[DisplayMgr] Presentation window displayed successfully!");

        } catch (Exception e) {
            Log.e(TAG, "[DisplayMgr] CRITICAL ERROR: Presentation initialization or layout inflation failed", e);
        }
    }

    public static void create_display(VirtualDisplay incoming_display) {
        DisplayMgr.display = incoming_display; 
        Log.d(TAG, "[DisplayMgr] Global display property assigned via create_display()");
    }
    
    public static boolean display_created(){
        return display != null; 
    }
        
    public static void apply_surface(Surface surface) {
        if (display != null) {
            display.setSurface(surface);
            if (presentation != null && !presentation.isShowing()) {
                try {
                    presentation.show();
                } catch (Exception e) {
                    Log.e(TAG, "[DisplayMgr] Presentation restore failed", e);
                }
            }
        }
    }
}
