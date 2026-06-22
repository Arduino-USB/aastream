package com.aastream.car;

import android.hardware.display.VirtualDisplay;
import android.view.Surface;
import android.content.Context;
import android.app.Presentation;
import android.os.Handler;
import android.os.Looper;
import android.widget.TextView; // Added missing import
import android.graphics.Color;    // Added missing import

import android.util.Log;

public class DisplayMgr {
    
    private static VirtualDisplay display = null;
    private static Presentation presentation = null; // Declared presentation static field
    private static final String TAG = "DisplayMgr";

    public DisplayMgr(Context context) {
        new Thread(() -> {
            while(true) {    
                try {Thread.sleep(500);} catch (Exception e) {
                    Log.e(TAG, "[DisplayMgr] Something went wrong sleeping", e);
                }
                
                if (display != null) {
                    Log.d(TAG, "[DisplayMgr] Display created");
                    
                    // Pass the constructor's context into the main thread runner
                    new Handler(Looper.getMainLooper()).post(() -> pres(context));
                    break;                
                }
				Log.d(TAG, "[DisplayMgr] Not created yet");
            }
        }).start();
    }

    // Accept context as a parameter here so the UI can use it
	private static void pres(Context context) {
		// 1. Instantiate the presentation first
		presentation = new Presentation(context, display.getDisplay());    

		// 2. USE THE PRESENTATION'S CONTEXT for rendering the UI elements!
		Context presentationContext = presentation.getContext();
		TextView textView = new TextView(presentationContext);
		
		textView.setText("can i do whatever here? Yes!");
		textView.setTextSize(24);
		textView.setTextColor(Color.WHITE);
		textView.setBackgroundColor(Color.BLUE);
		
		// 3. FORCE it to occupy the full screen real estate of the VirtualDisplay
		textView.setGravity(android.view.Gravity.CENTER); // Center the text beautifully
		android.view.ViewGroup.LayoutParams params = new android.view.ViewGroup.LayoutParams(
		        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
		        android.view.ViewGroup.LayoutParams.MATCH_PARENT
		);
		textView.setLayoutParams(params);

		// Attach and display
		presentation.setContentView(textView);
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
