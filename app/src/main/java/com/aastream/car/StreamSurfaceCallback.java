package com.aastream.car;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.app.Presentation;
import android.widget.TextView;
import android.graphics.Color;
import androidx.annotation.NonNull;
import androidx.car.app.AppManager;
import androidx.car.app.SurfaceCallback;
import androidx.car.app.SurfaceContainer;

public class StreamSurfaceCallback implements SurfaceCallback {
    private final Context context;
	private VirtualDisplay virtualDisplay;
    private Presentation presentation;
	
    public StreamSurfaceCallback (Context context) {
        this.context = context;
		new DisplayMgr(context);
    }

    @Override
    public void onSurfaceAvailable(@NonNull SurfaceContainer surfaceContainer) {
        // 1. Initialize the VirtualDisplay only if it doesn't exist yet
        if (!DisplayMgr.display_created()) {
            virtualDisplay = context.getSystemService(DisplayManager.class)
                    .createVirtualDisplay(
                            "CarDisplay",
                            surfaceContainer.getWidth(),
                            surfaceContainer.getHeight(),
                            surfaceContainer.getDpi(),
                            surfaceContainer.getSurface(), // Direct initial injection
                            0
                    );
			
			DisplayMgr.create_display(virtualDisplay);			
			
        } else {
            // 2. If it already exists, just swap the new surface in!
            DisplayMgr.apply_surface(surfaceContainer.getSurface());
        }

    }

    @Override
    public void onSurfaceDestroyed(@NonNull SurfaceContainer surfaceContainer) {
        // 4. Detach the surface and pause presentation instead of tearing down
        
        if (virtualDisplay != null) {
            // Passing null detaches the car's surface, stopping all drawing pipelines safely
            virtualDisplay.setSurface(null);
        }
    }

    /**
     * Call this when your Android Auto App/Service is actually being closed permanently 
     * (e.g., in CarAppService onDestroy) to fully clean up resources.
     */
    public void release() {
        if (presentation != null) {
            presentation.dismiss();
            presentation = null;
        }
        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }
    }
}
