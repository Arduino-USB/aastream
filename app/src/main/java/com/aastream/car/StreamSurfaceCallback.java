package com.aastream.car;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.app.Presentation;
import android.widget.TextView;
import android.graphics.Color;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.car.app.AppManager;
import androidx.car.app.SurfaceCallback;
import androidx.car.app.SurfaceContainer;

public class StreamSurfaceCallback implements SurfaceCallback {
    private static final String TAG = "AAStreamDebug";
    private final Context context;
    private VirtualDisplay virtualDisplay;
    private Presentation presentation;
	
    public StreamSurfaceCallback (Context context) {
        this.context = context;
        Log.d(TAG, "[StreamSurfaceCallback] Instance instantiated. Spinning up DisplayMgr pipeline...");
        new DisplayMgr(context);
    }

    @Override
    public void onSurfaceAvailable(@NonNull SurfaceContainer surfaceContainer) {
        Log.i(TAG, "[StreamSurfaceCallback] onSurfaceAvailable event received from AA HeadUnit host pipeline!");
        Log.i(TAG, "[StreamSurfaceCallback] AA Container Specifications -> Width: " + surfaceContainer.getWidth() 
                + ", Height: " + surfaceContainer.getHeight() + ", DPI: " + surfaceContainer.getDpi());
        
        if (!DisplayMgr.display_created()) {
            Log.i(TAG, "[StreamSurfaceCallback] Creating new system CarDisplay VirtualDisplay allocation map...");
            virtualDisplay = context.getSystemService(DisplayManager.class)
                    .createVirtualDisplay(
                            "CarDisplay",
                            surfaceContainer.getWidth(),
                            surfaceContainer.getHeight(),
                            surfaceContainer.getDpi(),
                            surfaceContainer.getSurface(), 
                            0
                    );
			
            Log.i(TAG, "[StreamSurfaceCallback] VirtualDisplay allocated successfully. Binding reference instance directly into DisplayMgr.");
            DisplayMgr.create_display(virtualDisplay);			
        } else {
            Log.i(TAG, "[StreamSurfaceCallback] Re-using persistent active display frame config; adjusting internal drawing surface mapping pointers");
            DisplayMgr.apply_surface(surfaceContainer.getSurface());
        }
    }

    @Override
    public void onSurfaceDestroyed(@NonNull SurfaceContainer surfaceContainer) {
        Log.w(TAG, "[StreamSurfaceCallback] onSurfaceDestroyed event intercepted from car display engine frame thread context!");
        if (virtualDisplay != null) {
            Log.d(TAG, "[StreamSurfaceCallback] Detaching rendering drawing canvas from VirtualDisplay tracking structures");
            virtualDisplay.setSurface(null);
        }
    }

    public void release() {
        Log.w(TAG, "[StreamSurfaceCallback] Manual release sequence executed on connection termination routines");
        if (presentation != null) {
            presentation.dismiss();
            presentation = null;
        }
        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }
        Log.d(TAG, "[StreamSurfaceCallback] Resource clean metrics completely compiled and resolved");
    }
}
