package com.aastream.car;

import android.content.Context;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.car.app.SurfaceCallback;
import androidx.car.app.SurfaceContainer;

public class StreamSurfaceCallback implements SurfaceCallback {
    private static final String TAG = "AAStreamDebug";
    private final Context context;
	
    public StreamSurfaceCallback (Context context) {
        this.context = context;
        Log.d(TAG, "[StreamSurfaceCallback] Instance instantiated. Waiting for AA Surface container callback...");
    }

    @Override
    public void onSurfaceAvailable(@NonNull SurfaceContainer surfaceContainer) {
        Log.i(TAG, "[StreamSurfaceCallback] onSurfaceAvailable event received from AA HeadUnit host pipeline!");
        Log.i(TAG, "[StreamSurfaceCallback] AA Container Specifications -> Width: " + surfaceContainer.getWidth() 
                + ", Height: " + surfaceContainer.getHeight() + ", DPI: " + surfaceContainer.getDpi());
        
        // Dynamically initialize and boot up the entire DisplayMgr rendering pipeline matching the exact dimensions provided
        DisplayMgr.startPipeline(
                context, 
                surfaceContainer.getSurface(), 
                surfaceContainer.getWidth(), 
                surfaceContainer.getHeight(), 
                surfaceContainer.getDpi()
        );
    }

    @Override
    public void onSurfaceDestroyed(@NonNull SurfaceContainer surfaceContainer) {
        Log.w(TAG, "[StreamSurfaceCallback] onSurfaceDestroyed event intercepted. Initiating absolute teardown sequence...");
        // Fully wipe out all VirtualDisplays, Presentations, and the GL rendering thread 
        DisplayMgr.stopPipeline();
    }

    public void release() {
        Log.w(TAG, "[StreamSurfaceCallback] Manual release sequence executed.");
        DisplayMgr.stopPipeline();
    }
}
