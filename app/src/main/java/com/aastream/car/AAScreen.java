package com.aastream.car;

import com.aastream.OverlayService;

import android.content.Intent;
import androidx.annotation.NonNull;
import androidx.car.app.CarContext;
import androidx.car.app.Screen;
import androidx.car.app.AppManager;
import androidx.car.app.model.ActionStrip;
import androidx.car.app.model.Template;
import androidx.car.app.navigation.model.NavigationTemplate;
import androidx.car.app.model.Action;



public class AAScreen extends Screen {

    public AAScreen(@NonNull CarContext carContext) {
        super(carContext);
        
        StreamSurfaceCallback surfaceCallback = new StreamSurfaceCallback(carContext);
        carContext.getCarService(AppManager.class).setSurfaceCallback(surfaceCallback);
    }

    @NonNull
    @Override
    public Template onGetTemplate() {
        // Check current status from the OverlayService
        boolean isScreenOff = OverlayService.isActive();
        String buttonTitle = isScreenOff ? "Screen On" : "Screen Off";

        ActionStrip actionStrip = new ActionStrip.Builder()
                .addAction(new Action.Builder()
                        .setTitle(buttonTitle)
                        .setOnClickListener(() -> {
							android.util.Log.d("AAStreamDebug", "[AAScreen] HeadUnit UI toggle action triggered.");
							Intent intent = new Intent(getCarContext(), com.aastream.OverlayService.class);
							intent.setAction("TOGGLE");
							
							// Crucial: Use startForegroundService instead of startService
							if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
								getCarContext().startForegroundService(intent);
							} else {
								getCarContext().startService(intent);
							}
							
							invalidate();
						})
                        .build())
                .build();

        return new NavigationTemplate.Builder()
                .setActionStrip(actionStrip)
                .build();
    }
}
