package com.aastream.car;

import android.hardware.display.VirtualDisplay;
import android.view.Surface;
import android.content.Context;
import android.app.Presentation;
import android.os.Handler;
import android.os.Looper;
import android.widget.TextView;
import android.view.TextureView;
import android.view.WindowManager;
import android.graphics.PixelFormat;
import android.graphics.SurfaceTexture;
import android.graphics.Canvas;
import android.net.Uri;
import android.view.View;
import android.util.Log;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Build;

import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

import com.aastream.R;
import com.aastream.ScreenBridge;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class DisplayMgr {
    
    public static VirtualDisplay display = null;
    private static Presentation presentation = null; 
    private static final String TAG = "AAStreamDebug"; 
    private static TextView text_view = null;
    private static TextureView texture_view = null;
    private static boolean lastKnownState = false;

    public static PlayerView nativePlayerView = null;
    public static ExoPlayer exoPlayer = null;

    private static android.os.HandlerThread processingThread = null;
    private static Handler processingHandler = null;

    private static OutputStream networkOutputStream = null;
    private static final Object socketLock = new Object();
    private static boolean isNetworkStreamingActive = false;

    // Hardware encoder pipeline states
    private static MediaCodec videoEncoder = null;
    private static Surface encoderInputSurface = null;
    private static boolean isCodecRunning = false;

    public interface PlayerStateListener {
        void onPlaybackEnded();
        void onTracksChanged(List<String> audioTracks);
    }

    private static PlayerStateListener uiListener = null;

    public DisplayMgr(Context context) {
        new Thread(() -> {
            while(true) {    
                try { Thread.sleep(500); } catch (Exception ignored) {}
                if (display != null) {
                    new Handler(Looper.getMainLooper()).post(() -> manage_screen(context));
                    break;                
                }
            }
        }).start();
    }
		
    public static void setPlayerStateListener(PlayerStateListener listener) {
        uiListener = listener;
    }

    public static void trigger(boolean state){
        lastKnownState = state;
        new Handler(Looper.getMainLooper()).post(() -> {
            if (text_view != null && texture_view != null && nativePlayerView != null) {
                
                // FIX: If native video layout is currently supposed to be active, 
                // do not forcefully overwrite the layer visibility configurations.
                if (exoPlayer != null && (exoPlayer.getPlaybackState() == Player.STATE_READY || exoPlayer.getPlaybackState() == Player.STATE_BUFFERING)) {
                    texture_view.setVisibility(View.GONE);
                    text_view.setVisibility(View.GONE);
                    nativePlayerView.setVisibility(View.VISIBLE);
                    return;
                }

                texture_view.setVisibility(View.VISIBLE);
                if (state) {
                    text_view.setVisibility(View.GONE);
                    nativePlayerView.setVisibility(View.GONE);
                    texture_view.invalidate();
                    Log.d(TAG, "[trigger] Screen Capture layout active. Forced texture layer invalidation.");
                } else {
                    text_view.setVisibility(View.VISIBLE);
                    Log.d(TAG, "[trigger] Layout reverted to idle view.");
                }
            }
        });
    }

    private static void manage_screen(Context context) {
        if (display == null) return;
        try {
            Context displayContext = context.createDisplayContext(display.getDisplay());
            presentation = new Presentation(displayContext, display.getDisplay());    
            
            // FIX: Force Hardware Acceleration parameters on the window BEFORE content is rendered
            if (presentation.getWindow() != null) {
                presentation.getWindow().addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED);
            }
            
            presentation.setContentView(R.layout.aascreen_layout);
            
            text_view = presentation.findViewById(R.id.text_view);
            texture_view = presentation.findViewById(R.id.screen_cast);
            nativePlayerView = presentation.findViewById(R.id.native_player_view);

            if (exoPlayer == null) {
                exoPlayer = new ExoPlayer.Builder(displayContext).build();
                nativePlayerView.setPlayer(exoPlayer);
            }

            trigger(lastKnownState);

            texture_view.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
                @Override
                public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
                    st.setDefaultBufferSize(w, h);
                    ScreenBridge.surface = new Surface(st);
                    ScreenBridge.width = w;
                    ScreenBridge.height = h;

                    if (ScreenBridge.service != null) {
                        ScreenBridge.service.start_display_if_possible(); 
                    }
                }
                @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {
                    st.setDefaultBufferSize(w, h);
                    ScreenBridge.width = w; 
                    ScreenBridge.height = h;
                }
                @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
                    ScreenBridge.surface = null; 
                    DisplayMgr.trigger(false); 
                    return true;
                }
                
                @Override 
                public void onSurfaceTextureUpdated(SurfaceTexture st) {
                    // FIX: Process frame pipeline intercept hook asynchronously without stalling rendering loop
                    if (isNetworkStreamingActive && processingHandler != null) {
                        processingHandler.post(() -> dispatchFrameAsynchronous());
                    }
                }
            });
            presentation.show(); 
        } catch (Exception e) { Log.e(TAG, "Presentation setup error", e); }
    }

    public static void handleNetworkClient(Socket clientSocket) {
        synchronized (socketLock) {
            try {
                if (networkOutputStream != null) networkOutputStream.close();
                networkOutputStream = clientSocket.getOutputStream();
                
                if (processingThread == null) {
                    processingThread = new android.os.HandlerThread("AAStreamSplitterThread");
                    processingThread.start();
                    processingHandler = new Handler(processingThread.getLooper());
                }
                
                int targetW = ScreenBridge.width > 0 ? ScreenBridge.width : 1280;
                int targetH = ScreenBridge.height > 0 ? ScreenBridge.height : 720;
                
                // Tear down any previous encoder context safely
                releaseEncoder();

                Log.i(TAG, "Initializing hardware H.264 MediaCodec pipeline at " + targetW + "x" + targetH);
                MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, targetW, targetH);
                
                // Configure low latency real-time streaming constraints
                format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
                format.setInteger(MediaFormat.KEY_BIT_RATE, 4000000); // 4 Mbps
                format.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
                format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1); // 1 second keyframes
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    format.setInteger(MediaFormat.KEY_LATENCY, 0); // Real-time priority mode
                }

                videoEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
                videoEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                
                // Generate an input surface to blit frames into directly via GPU VRAM
                encoderInputSurface = videoEncoder.createInputSurface();
                videoEncoder.start();
                isCodecRunning = true;
                isNetworkStreamingActive = true;

                // Fire up output drainer thread to transmit bytes instantly
                startEncoderOutputLoop();
                
                Log.i(TAG, "Asynchronous network hardware H.264 streaming broadcast pipeline attached securely.");
            } catch (IOException e) { Log.e(TAG, "Failed network client handshakes / encoder initialization", e); }
        }
    }

    private static void dispatchFrameAsynchronous() {
        if (texture_view == null || encoderInputSurface == null || !isCodecRunning) return;
        
        // FIX: TextureView.draw(canvas) MUST be marshalled and executed on the Main Thread (UI Looper). 
        // Invoking this straight from a background or rendering handler worker causes a cross-thread canvas exception
        // that corrupts the graphics buffer queue and immediately destroys the MediaProjection token context.
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                if (encoderInputSurface != null && isCodecRunning) {
                    Canvas canvas = null;
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        canvas = encoderInputSurface.lockHardwareCanvas();
                    } else {
                        canvas = encoderInputSurface.lockCanvas(null);
                    }

                    if (canvas != null) {
                        texture_view.draw(canvas);
                        encoderInputSurface.unlockCanvasAndPost(canvas);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Hardware canvas blit extraction dropped frame or encountered surface loss", e);
            }
        });
    }

    private static void startEncoderOutputLoop() {
        new Thread(() -> {
            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
            try {
                while (isNetworkStreamingActive && isCodecRunning) {
                    int outputBufferIndex = videoEncoder.dequeueOutputBuffer(bufferInfo, 10000); // 10ms timeout
                    
                    if (outputBufferIndex >= 0) {
                        ByteBuffer outputBuffer = videoEncoder.getOutputBuffer(outputBufferIndex);
                        if (outputBuffer != null && bufferInfo.size > 0) {
                            synchronized (socketLock) {
                                if (networkOutputStream != null) {
                                    byte[] outData = new byte[bufferInfo.size];
                                    outputBuffer.get(outData);
                                    
                                    // Send Annex-B stream units sequentially down raw TCP socket
                                    networkOutputStream.write(outData, 0, outData.length);
                                    networkOutputStream.flush();
                                }
                            }
                        }
                        videoEncoder.releaseOutputBuffer(outputBufferIndex, false);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error inside output pipeline byte writer thread context", e);
                isNetworkStreamingActive = false;
            }
        }, "AAStreamEncoderOutThread").start();
    }

    private static void releaseEncoder() {
        isCodecRunning = false;
        if (videoEncoder != null) {
            try {
                videoEncoder.stop();
                videoEncoder.release();
            } catch (Exception ignored) {}
            videoEncoder = null;
        }
        if (encoderInputSurface != null) {
            encoderInputSurface.release();
            encoderInputSurface = null;
        }
    }

    public static void stopAllMediaEngines() {
        new Handler(Looper.getMainLooper()).post(() -> {
            isNetworkStreamingActive = false;
            releaseEncoder();
            
            synchronized (socketLock) {
                if (networkOutputStream != null) {
                    try { networkOutputStream.close(); } catch (Exception ignored) {}
                    networkOutputStream = null;
                }
            }
            if (processingThread != null) {
                processingThread.quitSafely();
                processingThread = null;
                processingHandler = null;
            }
            if (exoPlayer != null) { exoPlayer.stop(); exoPlayer.clearMediaItems(); }
        });
    }

    public static void create_display(VirtualDisplay incoming_display) { display = incoming_display; }
    public static boolean display_created(){ return display != null; }
    public static void apply_surface(Surface surface) {
        if (display != null) {
            display.setSurface(surface);
            if (presentation != null && !presentation.isShowing()) {
                try { presentation.show(); } catch (Exception ignored) {}
            }
        }
    }

    // ==========================================
    // video stuff
    // ==========================================

    public static void playNativeVideoFile(Uri uri) {
        new Handler(Looper.getMainLooper()).post(() -> {
            if (exoPlayer == null) return;

            if (text_view != null) text_view.setVisibility(View.GONE);
            if (texture_view != null) texture_view.setVisibility(View.GONE);
            if (nativePlayerView != null) nativePlayerView.setVisibility(View.VISIBLE);

            MediaItem mediaItem = MediaItem.fromUri(uri);
            exoPlayer.setMediaItem(mediaItem);
            
            exoPlayer.addListener(new Player.Listener() {
                @Override
                public void onPlaybackStateChanged(int state) {
                    if (state == Player.STATE_ENDED && uiListener != null) {
                        uiListener.onPlaybackEnded();
                    }
                }

                @Override
                public void onTracksChanged(androidx.media3.common.Tracks tracks) {
                    if (uiListener == null) return;
                    List<String> audioTrackNames = new ArrayList<>();
                    
                    for (androidx.media3.common.Tracks.Group group : tracks.getGroups()) {
                        if (group.getType() == androidx.media3.common.C.TRACK_TYPE_AUDIO) {
                            for (int i = 0; i < group.length; i++) {
                                androidx.media3.common.Format format = group.getTrackFormat(i);
                                String name = format.language != null ? format.language : "Audio Track " + (audioTrackNames.size() + 1);
                                audioTrackNames.add(name);
                            }
                        }
                    }
                    uiListener.onTracksChanged(audioTrackNames);
                }
            });

            exoPlayer.prepare();
            exoPlayer.play();
            Log.i(TAG, "[DisplayMgr] Native ExoPlayer pipeline loaded and streaming target file.");
        });
    }

    public static void stopVideoPlaybackEngine() {
        new Handler(Looper.getMainLooper()).post(() -> {
            if (exoPlayer != null) {
                exoPlayer.stop();
                exoPlayer.clearMediaItems();
            }
            if (nativePlayerView != null) {
                nativePlayerView.setVisibility(View.GONE);
            }
            trigger(lastKnownState);
        });
    }

    public static void injectExternalSubtitles(Uri srtUri) {
        new Handler(Looper.getMainLooper()).post(() -> {
            if (exoPlayer == null) return;

            MediaItem currentItem = exoPlayer.getCurrentMediaItem();
            if (currentItem == null) return;

            long savedPlaybackPosition = exoPlayer.getCurrentPosition();
            boolean wasPlaying = exoPlayer.getPlayWhenReady();

            MediaItem.SubtitleConfiguration subConfig = new MediaItem.SubtitleConfiguration.Builder(srtUri)
                    .setMimeType(MimeTypes.APPLICATION_SUBRIP)
                    .setSelectionFlags(androidx.media3.common.C.SELECTION_FLAG_DEFAULT)
                    .build();

            MediaItem extendedItem = currentItem.buildUpon()
                    .setSubtitleConfigurations(Collections.singletonList(subConfig))
                    .build();

            exoPlayer.setMediaItem(extendedItem);
            exoPlayer.prepare();
            exoPlayer.seekTo(savedPlaybackPosition);
            exoPlayer.setPlayWhenReady(wasPlaying);
            Log.i(TAG, "[DisplayMgr] Dynamic subtitle stream injection completed successfully.");
        });
    }

    public static void clearSubtitles() {
        new Handler(Looper.getMainLooper()).post(() -> {
            if (exoPlayer == null) return;
            exoPlayer.setTrackSelectionParameters(
                    exoPlayer.getTrackSelectionParameters()
                            .buildUpon()
                            .clearOverridesOfType(androidx.media3.common.C.TRACK_TYPE_TEXT)
                            .build()
            );
        });
    }

    public static void selectAudioTrack(int trackIndex) {
        new Handler(Looper.getMainLooper()).post(() -> {
            if (exoPlayer == null) return;

            androidx.media3.common.Tracks currentTracks = exoPlayer.getCurrentTracks();
            int currentAudioGlobalIndex = 0;

            for (androidx.media3.common.Tracks.Group group : currentTracks.getGroups()) {
                if (group.getType() == androidx.media3.common.C.TRACK_TYPE_AUDIO) {
                    for (int i = 0; i < group.length; i++) {
                        if (currentAudioGlobalIndex == trackIndex) {
                            exoPlayer.setTrackSelectionParameters(
                                    exoPlayer.getTrackSelectionParameters()
                                            .buildUpon()
                                            .setOverrideForType(new androidx.media3.common.TrackSelectionOverride(group.getMediaTrackGroup(), i))
                                            .build()
                            );
                            Log.d(TAG, "[DisplayMgr] Swapped active runtime audio layout to index: " + trackIndex);
                            return;
                        }
                        currentAudioGlobalIndex++;
                    }
                }
            }
        });
    }
}
