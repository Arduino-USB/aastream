package com.aastream.car;

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
        
        // Grab the AppManager service and register your custom SurfaceCallback
        StreamSurfaceCallback surfaceCallback = new StreamSurfaceCallback(carContext);
        carContext.getCarService(AppManager.class).setSurfaceCallback(surfaceCallback);
    }

    @NonNull
    @Override
	public Template onGetTemplate() {
        // RULE: NavigationTemplate MUST contain a non-empty ActionStrip,
        // or a back button/map toggle action, otherwise it crashes instantly.
        ActionStrip actionStrip = new ActionStrip.Builder()
                .addAction(new Action.Builder()
                        .setTitle("Settings")
                        .setOnClickListener(() -> { /* Optional click handler */ })
                        .build())
                .build();

        return new NavigationTemplate.Builder()
                .setActionStrip(actionStrip)
                .build();
    }
}
