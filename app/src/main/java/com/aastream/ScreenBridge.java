package com.aastream;

import android.view.Surface;

public class ScreenBridge {
    public static Surface surface;
    public static int width;
    public static int height;
    public static ScreenCaptureService service;
    
    // Explicit single-process visibility state flag
    public static volatile boolean isSurfaceReady = false;
}
