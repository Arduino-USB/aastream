package com.aastream.car;

import android.hardware.display.VirtualDisplay;
import android.view.Surface;
import android.content.Context;
import android.app.Presentation;
import android.os.Handler;
import android.os.Looper;
import android.widget.TextView; // Added missing import
import android.graphics.Color;    // Added missing import
import android.view.View;

import android.util.Log;
import com.aastream.R;

import com.aastream.ScreenBridge;
public class DisplayMgr {
    
    private static VirtualDisplay display = null;
    private static Presentation presentation = null; // Declared presentation static field
    private static final String TAG = "DisplayMgr";
	private static TextView text_view = null;
    public DisplayMgr(Context context) {
        new Thread(() -> {
            while(true) {    
                try {Thread.sleep(500);} catch (Exception e) {
                    Log.e(TAG, "[DisplayMgr] Something went wrong sleeping", e);
                }
                
                if (display != null) {
                    Log.d(TAG, "[DisplayMgr] Display created");
                    
                    // Pass the constructor's context into the main thread runner
                    new Handler(Looper.getMainLooper()).post(() -> manage_screen(context));
                    break;                
                }
				Log.d(TAG, "[DisplayMgr] Not created yet");
            }
        }).start();
    }
	
	public static void trigger(boolean state){
		if (state) {
			if (text_view != null){
				text_view.setVisibility(View.GONE);
			}
			
			
			
		} else {
			if (text_view != null){
				text_view.setVisibility(View.VISIBLE);
			}
		}		
	}
		
	
	
    // Accept context as a parameter here so the UI can use it
	private static void manage_screen(Context context) {
		// 1. Instantiate the presentation first
		presentation = new Presentation(context, display.getDisplay());    

		// 2. USE THE PRESENTATION'S CONTEXT for rendering the UI elements!
		Context presentationContext = presentation.getContext();
		text_view = new TextView(presentationContext);
		
		presentation.setContentView(R.layout.aascreen_layout);
		text_view = presentation.findViewById(R.id.textView);

		presentation.show(); 
	}

    public static void create_display(VirtualDisplay incomingDisplay) {
        DisplayMgr.display = incomingDisplay; // Fixed 'this' compilation error
    }
    
    public static boolean display_created(){
        return display != null; // Simpler syntax doing exactly what your if block did
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
