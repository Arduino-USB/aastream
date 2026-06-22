package com.aastream.car;

import androidx.annotation.NonNull;
import androidx.car.app.CarAppService;
import androidx.car.app.Session;
import androidx.car.app.Screen;
import androidx.car.app.validation.HostValidator;

public class EntryPoint extends CarAppService {

    @NonNull
    @Override
    public HostValidator createHostValidator() {
        // For testing purposes, allow any car head unit host to connect to your app
        return HostValidator.ALLOW_ALL_HOSTS_VALIDATOR;
    }

    @NonNull
    @Override
    public Session onCreateSession() {
        return new Session() {
            @NonNull
            @Override
            public Screen onCreateScreen(@NonNull android.content.Intent intent) {
                // Launch into the screen we made in Step 3
                return new AAScreen(getCarContext());
            }
        };
    }
}
