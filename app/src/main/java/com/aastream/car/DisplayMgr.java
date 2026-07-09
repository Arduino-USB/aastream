package com.aastream.car;

import android.app.Presentation;
import android.content.Context;
import android.graphics.SurfaceTexture;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.net.Uri;
import android.opengl.GLES20;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

import com.aastream.R;
import com.aastream.ScreenBridge;
import com.aastream.grafika.EglCore;
import com.aastream.grafika.FullFrameRect;
import com.aastream.grafika.OffscreenSurface;
import com.aastream.grafika.Texture2dProgram;
import com.aastream.grafika.WindowSurface;

import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class DisplayMgr {
    private static final String TAG = "AAStreamDebug";
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    // UI elements managed from Presentation
    private static TextView text_view = null;
    private static TextureView texture_view = null;
    public static PlayerView nativePlayerView = null;
    public static ExoPlayer exoPlayer = null;
    private static boolean lastKnownState = false;

    // Core Pipeline Engines
    private static RenderThread mRenderThread = null;
    private static VirtualDisplay mOffscreenDisplay = null;
    private static Presentation mPresentation = null;
    private static Context cachedContext = null;

    // Networking & Encoding state
    private static OutputStream networkOutputStream = null;
    private static final Object socketLock = new Object();
    private static volatile boolean isNetworkStreamingActive = false;
    private static MediaCodec videoEncoder = null;
    private static byte[] cachedSpsPpsHeaders = null;

	// Networking Server Properties
    private static ServerSocket mServerSocket = null;
    private static Thread mServerThread = null;
    private static final int PORT = 6741;
	
	private static final java.util.concurrent.atomic.AtomicInteger pendingNetworkWrites = new java.util.concurrent.atomic.AtomicInteger(0);

    public interface PlayerStateListener {
        void onPlaybackEnded();
        void onTracksChanged(List<String> audioTracks);
    }
    private static PlayerStateListener uiListener = null;

    // Hidden Constructor - Initialization happens explicitly via startPipeline
    private DisplayMgr() {}
	
	public static boolean isNetworkBufferChoked() {
		// If more than 2 frames are stuck waiting for the TCP socket layer, choke input
		return pendingNetworkWrites.get() > 2;
	}

    public static void setPlayerStateListener(PlayerStateListener listener) {
        uiListener = listener;
    }

    /**
     * Initializes and spawns the entire display architecture using precise AA surface specifications.
     */
	public static synchronized void startPipeline(Context context, Surface aaSurface, int width, int height, int dpi) {
        // Clean up any leaking graphics loops (keep network output stream context safe)
        if (mRenderThread != null) {
            mRenderThread.shutdown();
            try { mRenderThread.join(500); } catch (InterruptedException ignored) {}
            mRenderThread = null;
        }
        
        cachedContext = context.getApplicationContext();
        
        // Pass surface dimensions into the ScreenBridge global state configuration
        ScreenBridge.width = width;
        ScreenBridge.height = height;

        mRenderThread = new RenderThread(aaSurface, width, height, dpi);
        mRenderThread.start();
        mRenderThread.waitUntilReady();

        // CROSS-CHECK: If a network stream client connected early while mRenderThread was null,
        // spin up the encoder right now and bind it to the newly prepared EGL context.
        synchronized (socketLock) {
            if (isNetworkStreamingActive && networkOutputStream != null && videoEncoder == null) {
                Log.i(TAG, "[DisplayMgr] Network client was waiting. Instantiating video encoder for new EGL window target.");
                int targetWidth = ScreenBridge.width > 0 ? ScreenBridge.width : 800;
                int targetHeight = ScreenBridge.height > 0 ? ScreenBridge.height : 480;
                videoEncoder = createAndConfigureEncoder(targetWidth, targetHeight);
                if (videoEncoder != null) {
                    startEncoderOutputLoop();
                }
            }
        }
    }

    /**
     * Completely shuts down, breaks down, and flushes every structural engine in the rendering pipeline.
     */
/**
     * Completely shuts down, breaks down, and flushes every structural engine in the rendering pipeline.
     */
    public static synchronized void stopPipeline() {
        // Tear down encoder surface coupling safely
        if (mRenderThread != null) {
            mRenderThread.updateEncoderSurface(null);
        }
        if (videoEncoder != null) {
            try { videoEncoder.stop(); } catch (Exception ignored) {}
            try { videoEncoder.release(); } catch (Exception ignored) {}
            videoEncoder = null;
        }

        if (exoPlayer != null) { 
            mainHandler.post(() -> { try { exoPlayer.stop(); exoPlayer.clearMediaItems(); } catch (Exception ignored) {} }); 
        }

        // Shut down the Rendering Loop and EGL instances
        if (mRenderThread != null) {
            mRenderThread.shutdown();
            try {
                mRenderThread.join(500);
            } catch (InterruptedException ignored) {}
            mRenderThread = null;
        }

        // Clear window presentation contexts safely on the Main Looper UI Thread
        mainHandler.post(() -> {
            if (mPresentation != null) {
                try { mPresentation.dismiss(); } catch (Exception ignored) {}
                mPresentation = null;
            }
            if (mOffscreenDisplay != null) {
                mOffscreenDisplay.release();
                mOffscreenDisplay = null;
            }
            text_view = null;
            texture_view = null;
            nativePlayerView = null;
        });
    }

    public static boolean display_created() {
        return mOffscreenDisplay != null;
    }

    /**
     * Dedicated background thread managing EGL configurations, shader lifetimes,
     * and dual output quad blitting.
     */
    private static class RenderThread extends Thread implements SurfaceTexture.OnFrameAvailableListener {
        private final Surface mInitialAaSurface;
        private final int mWidth;
        private final int mHeight;
        private final int mDpi;

        private EglCore mEglCore;
        private OffscreenSurface mPbufferSurface;
        private FullFrameRect mFullScreenRect;
        private int mTextureId;
        private SurfaceTexture mCameraTexture;
        private Surface mPresentationSurface;

        private WindowSurface mAndroidAutoWindow;
        private WindowSurface mEncoderWindow;

        private final Object mReadyLock = new Object();
        private boolean mReady = false;
        private Handler mHandler;
        private Looper mLooper;

        private final float[] mTmpMatrix = new float[16];

        public RenderThread(Surface aaSurface, int width, int height, int dpi) {
            this.mInitialAaSurface = aaSurface;
            this.mWidth = width;
            this.mHeight = height;
            this.mDpi = dpi;
        }

        @Override
        public void run() {
            Looper.prepare();
            synchronized (mReadyLock) {
                mLooper = Looper.myLooper();
                mHandler = new Handler(mLooper);
                mReady = true;
                mReadyLock.notifyAll();
            }

            // 1. Initialize core display structures
            mEglCore = new EglCore(null, EglCore.FLAG_RECORDABLE);

            // 2. Create an initial Offscreen pbuffer surface to fulfill OpenGL requirements
            mPbufferSurface = new OffscreenSurface(mEglCore, 1, 1);
            mPbufferSurface.makeCurrent();

            // 3. Compile and build the Texture2dProgram shaders safely inside the current context
            mFullScreenRect = new FullFrameRect(new Texture2dProgram(Texture2dProgram.ProgramType.TEXTURE_EXT));
            mTextureId = mFullScreenRect.getProgram().createTextureObject();

            // 4. Construct SurfaceTexture and associate it with our Presentation
            mCameraTexture = new SurfaceTexture(mTextureId);
            mCameraTexture.setOnFrameAvailableListener(this);
            mPresentationSurface = new Surface(mCameraTexture);

            // 5. Connect the Android Auto output WindowSurface mapping right away
            if (mInitialAaSurface != null && mInitialAaSurface.isValid()) {
                mAndroidAutoWindow = new WindowSurface(mEglCore, mInitialAaSurface, false);
            }

            mainHandler.post(() -> setupOffscreenPresentation(mPresentationSurface));

            Looper.loop();

            // Explicit Teardown Context Execution when Looper quits
            releaseGlComponents();
        }

        public void waitUntilReady() {
            synchronized (mReadyLock) {
                while (!mReady) {
                    try { mReadyLock.wait(); } catch (InterruptedException ignored) {}
                }
            }
        }

        public void shutdown() {
            if (mHandler != null) {
                mHandler.post(() -> {
                    if (mLooper != null) {
                        mLooper.quit();
                    }
                });
            }
        }

        private void setupOffscreenPresentation(Surface surface) {
            try {
                mCameraTexture.setDefaultBufferSize(mWidth, mHeight);

                DisplayManager dm = (DisplayManager) cachedContext.getSystemService(Context.DISPLAY_SERVICE);

                int flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
                        | DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
                        | DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION;

                mOffscreenDisplay = dm.createVirtualDisplay("AAStreamInternal", mWidth, mHeight, mDpi,
                        surface, flags);

                if (mOffscreenDisplay == null || mOffscreenDisplay.getDisplay() == null) {
                    Log.e(TAG, "Failed to create VirtualDisplay");
                    return;
                }

                Context displayContext = cachedContext.createDisplayContext(mOffscreenDisplay.getDisplay());
                mPresentation = new Presentation(displayContext, mOffscreenDisplay.getDisplay());

                if (mPresentation.getWindow() != null) {
                    mPresentation.getWindow().addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED);
                }

                mPresentation.setContentView(R.layout.aascreen_layout);

                text_view = mPresentation.findViewById(R.id.text_view);
                texture_view = mPresentation.findViewById(R.id.screen_cast);
                nativePlayerView = mPresentation.findViewById(R.id.native_player_view);

                setupExoPlayer(displayContext);
                setupTextureViewListener();

                mPresentation.show();
                DisplayMgr.trigger(lastKnownState);
                Log.i(TAG, "Hardware accelerated Presentation successfully instantiated on customized VirtualDisplay.");
            } catch (Exception e) {
                Log.e(TAG, "Failed creating offscreen presentation window mapping", e);
            }
        }

        public void updateEncoderSurface(final Surface surface) {
            mHandler.post(() -> {
                if (mEncoderWindow != null) {
                    mEncoderWindow.release();
                    mEncoderWindow = null;
                }
                if (surface != null && surface.isValid()) {
                    mEncoderWindow = new WindowSurface(mEglCore, surface, true);
                    Log.d(TAG, "Encoder Input WindowSurface attached to Grafika Pipeline.");
                }
            });
        }

        @Override
        public void onFrameAvailable(SurfaceTexture surfaceTexture) {
            mHandler.post(() -> {
                if (mEglCore == null || mCameraTexture == null) return;

                // Bind offscreen target texture before updating image data
                mPbufferSurface.makeCurrent();
                mCameraTexture.updateTexImage();
                mCameraTexture.getTransformMatrix(mTmpMatrix);

                long timestamp = mCameraTexture.getTimestamp();

                // 1. Draw frame to Android Auto Surface using exact assigned dimension profiles
                if (mAndroidAutoWindow != null) {
                    mAndroidAutoWindow.makeCurrent();
                    GLES20.glViewport(0, 0, mWidth, mHeight);
                    mFullScreenRect.drawFrame(mTextureId, mTmpMatrix);
                    mAndroidAutoWindow.swapBuffers();
                }

                // 2. Mirror frame identically to MediaCodec Encoder Surface
				if (mEncoderWindow != null && isNetworkStreamingActive) {
					// Check custom threshold flag before encoding (see next section)
					if (!isNetworkBufferChoked()) { 
						mEncoderWindow.makeCurrent();
						int w = ScreenBridge.width > 0 ? ScreenBridge.width : 800;
						int h = ScreenBridge.height > 0 ? ScreenBridge.height : 480;
						GLES20.glViewport(0, 0, w, h);
						mFullScreenRect.drawFrame(mTextureId, mTmpMatrix);
						mEncoderWindow.setPresentationTime(timestamp);
						mEncoderWindow.swapBuffers();
					} else {
						// Log.v(TAG, "Dropping frame at EGL layer to prevent network bufferbloat");
					}
				}
            });
        }

        private void releaseGlComponents() {
            if (mAndroidAutoWindow != null) { mAndroidAutoWindow.release(); mAndroidAutoWindow = null; }
            if (mEncoderWindow != null) { mEncoderWindow.release(); mEncoderWindow = null; }
            if (mPresentationSurface != null) { mPresentationSurface.release(); mPresentationSurface = null; }
            if (mCameraTexture != null) { mCameraTexture.release(); mCameraTexture = null; }
            if (mFullScreenRect != null) { mFullScreenRect.release(false); mFullScreenRect = null; }
            if (mPbufferSurface != null) { mPbufferSurface.release(); mPbufferSurface = null; }
            if (mEglCore != null) { mEglCore.release(); mEglCore = null; }
        }
    }

    private static void setupExoPlayer(Context context) {
        if (exoPlayer != null) return;
        DefaultRenderersFactory renderersFactory = new DefaultRenderersFactory(context)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF);
        androidx.media3.exoplayer.DefaultLoadControl loadControl = new androidx.media3.exoplayer.DefaultLoadControl.Builder()
                .setBufferDurationsMs(1000, 2500, 500, 500)
                .build();
        exoPlayer = new ExoPlayer.Builder(context, renderersFactory)
                .setLoadControl(loadControl)
                .build();
        nativePlayerView.setPlayer(exoPlayer);
    }

    private static void setupTextureViewListener() {
        texture_view.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
                st.setDefaultBufferSize(w, h);
                Surface origSurface = new Surface(st);
                ScreenBridge.surface = origSurface;
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
            @Override public void onSurfaceTextureUpdated(SurfaceTexture st) {}
        });
    }

    public static void trigger(boolean state) {
        lastKnownState = state;
        mainHandler.post(() -> {
            if (text_view != null && texture_view != null && nativePlayerView != null) {
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
                } else {
                    text_view.setVisibility(View.VISIBLE);
                }
            }
        });
    }

	public static void handleNetworkClient(Socket clientSocket) {
        synchronized (socketLock) {
            try {
                Log.i(TAG, "[Network] Connecting client target directly to pipeline blit structure...");
                if (networkOutputStream != null) {
                    try { networkOutputStream.close(); } catch (IOException ignored) {}
                }
                networkOutputStream = clientSocket.getOutputStream();
                isNetworkStreamingActive = true;

                // Only spin up the encoder if the EGL pipeline is ready to accept it.
                // If mRenderThread is null, startPipeline() will call this later.
                if (mRenderThread != null) {
                    int targetWidth = ScreenBridge.width > 0 ? ScreenBridge.width : 800;
                    int targetHeight = ScreenBridge.height > 0 ? ScreenBridge.height : 480;

                    if (videoEncoder != null) {
                        try { videoEncoder.stop(); videoEncoder.release(); } catch (Exception ignored) {}
                    }

                    videoEncoder = createAndConfigureEncoder(targetWidth, targetHeight);
                    if (videoEncoder == null) {
                        isNetworkStreamingActive = false;
                        return;
                    }

                    startEncoderOutputLoop();
                } else {
                    Log.w(TAG, "[Network] Client connected, but EGL RenderThread is idle. Postponing encoder activation until AA surface arrives.");
                }
            } catch (IOException e) {
                Log.e(TAG, "Failed negotiating baseline network client sockets", e);
            }
        }
    }

	private static MediaCodec createAndConfigureEncoder(int suggestedWidth, int suggestedHeight) {
		try {
		    MediaCodecList codecList = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
		    String encoderName = null;
		    MediaCodecInfo selectedCodecInfo = null;
		    
		    for (MediaCodecInfo info : codecList.getCodecInfos()) {
		        if (!info.isEncoder()) continue;
		        for (String type : info.getSupportedTypes()) {
		            if (type.equalsIgnoreCase(MediaFormat.MIMETYPE_VIDEO_AVC)) {
		                encoderName = info.getName();
		                selectedCodecInfo = info;
		                break;
		            }
		        }
		        if (encoderName != null) break;
		    }
		    if (encoderName == null || selectedCodecInfo == null) return null;

		    MediaCodec encoder = MediaCodec.createByCodecName(encoderName);
		    MediaCodecInfo.CodecCapabilities capabilities = selectedCodecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC);
		    MediaCodecInfo.VideoCapabilities videoCaps = capabilities.getVideoCapabilities();

		    int width = videoCaps.getSupportedWidths().clamp(suggestedWidth);
		    int height = videoCaps.getSupportedHeightsFor(width).clamp(suggestedHeight);
		    width = Math.max(320, (width / 16) * 16);
		    height = Math.max(240, (height / 16) * 16);

		    MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height);
		    format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
		    format.setInteger(MediaFormat.KEY_BIT_RATE, Math.max(2000000, width * height * 3));
		    format.setInteger(MediaFormat.KEY_FRAME_RATE, 60);
		    format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
		    format.setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 16666);

		    // Dynamic Check: Only apply CBR if the SoC explicitly supports it
		    MediaCodecInfo.EncoderCapabilities encoderCaps = capabilities.getEncoderCapabilities();
			if (encoderCaps != null && encoderCaps.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)) {
				format.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR);
			} else {
				Log.w(TAG, "CBR Bitrate mode unsupported by this hardware. Falling back to VBR.");
				format.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR);
			}

		    // Low-latency configuration safeguards
		    try {
		        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
		            format.setInteger(MediaFormat.KEY_LATENCY, 0);
		        }
		        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
		            format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
		        }
		        format.setInteger(MediaFormat.KEY_PRIORITY, 0); // Real-time priority
		    } catch (Exception e) {
		        Log.w(TAG, "Device rejected strict real-time tuning keys, stripping them out.");
		    }

		    encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
		    Surface inputSurface = encoder.createInputSurface();

		    if (mRenderThread != null) {
		        mRenderThread.updateEncoderSurface(inputSurface);
		    }

		    encoder.start();
		    return encoder;
		} catch (Exception e) {
		    Log.e(TAG, "Unable to spin up independent MediaCodec pipeline configuration", e);
		    return null;
		}
	}


	private static void startEncoderOutputLoop() {
        new Thread(() -> {
            MediaCodec.BufferInfo buffer_info = new MediaCodec.BufferInfo();
            try {
                while (isNetworkStreamingActive) {
                    MediaCodec codec = videoEncoder;
                    if (codec == null) break;

                    int output_buffer_index = -1;
                    try {
                        output_buffer_index = codec.dequeueOutputBuffer(buffer_info, 30000);
                    } catch (IllegalStateException e) { break; }

                    if (output_buffer_index >= 0) {
                        ByteBuffer output_buffer = codec.getOutputBuffer(output_buffer_index);
                        if (output_buffer != null && buffer_info.size > 0) {
                            if ((buffer_info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                                cachedSpsPpsHeaders = new byte[buffer_info.size];
                                output_buffer.position(buffer_info.offset);
                                output_buffer.get(cachedSpsPpsHeaders);
                            }
                            synchronized (socketLock) {
                                if (networkOutputStream != null && isNetworkStreamingActive) {
                                    output_buffer.position(buffer_info.offset);
                                    output_buffer.limit(buffer_info.offset + buffer_info.size);
                                    
                                    // 1. Declare and allocate out_data FIRST
                                    byte[] out_data = new byte[buffer_info.size];
                                    output_buffer.get(out_data);
                                    
                                    try {
                                        // Track that we are attempting a network write
                                        pendingNetworkWrites.incrementAndGet();
                                        
                                        // 2. Wrap metadata into a 16-byte header: [4-bytes Length][8-bytes PTS][4-bytes Flags]
                                        ByteBuffer header = ByteBuffer.allocate(16);
                                        header.putInt(out_data.length);
                                        header.putLong(buffer_info.presentationTimeUs);
                                        header.putInt(buffer_info.flags);
                                        
                                        // 3. Write the framing header, then write the raw video data frame payload
                                        networkOutputStream.write(header.array());
                                        networkOutputStream.write(out_data, 0, out_data.length);
                                        networkOutputStream.flush();
                                    } catch (IOException netEx) {
                                        isNetworkStreamingActive = false;
                                    } finally {
                                        pendingNetworkWrites.decrementAndGet();
                                    }
                                }
                            }
                        }
                        try { codec.releaseOutputBuffer(output_buffer_index, false); } catch (Exception ignored) {}
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Encoder streaming task intercepted unexpected state change", e);
            }
        }, "AAStreamEncoderOutThread").start();
    }

    public static void releaseEncoder() {
        isNetworkStreamingActive = false;
        if (mRenderThread != null) {
            mRenderThread.updateEncoderSurface(null);
        }
        if (videoEncoder != null) {
            try { videoEncoder.stop(); } catch (Exception ignored) {}
            try { videoEncoder.release(); } catch (Exception ignored) {}
            videoEncoder = null;
        }
    }

    public static void stopAllMediaEngines() {
        mainHandler.post(() -> {
            releaseEncoder();
            synchronized (socketLock) {
                if (networkOutputStream != null) {
                    try { networkOutputStream.close(); } catch (Exception ignored) {}
                    networkOutputStream = null;
                }
            }
            if (exoPlayer != null) { exoPlayer.stop(); exoPlayer.clearMediaItems(); }
        });
    }

    public static void playNativeVideoFile(Uri uri) {
        mainHandler.post(() -> {
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
        });
    }

    public static void stopVideoPlaybackEngine() {
        mainHandler.post(() -> {
            if (exoPlayer != null) { exoPlayer.stop(); exoPlayer.clearMediaItems(); }
            if (nativePlayerView != null) nativePlayerView.setVisibility(View.GONE);
            trigger(lastKnownState);
        });
    }

    public static void injectExternalSubtitles(Uri srtUri) {
        mainHandler.post(() -> {
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
        });
    }

    public static void clearSubtitles() {
        mainHandler.post(() -> {
            if (exoPlayer == null) return;
            exoPlayer.setTrackSelectionParameters(
                    exoPlayer.getTrackSelectionParameters().buildUpon()
                            .clearOverridesOfType(androidx.media3.common.C.TRACK_TYPE_TEXT).build()
            );
        });
    }

    public static void selectAudioTrack(int trackIndex) {
        mainHandler.post(() -> {
            if (exoPlayer == null) return;
            androidx.media3.common.Tracks currentTracks = exoPlayer.getCurrentTracks();
            int currentAudioGlobalIndex = 0;
            for (androidx.media3.common.Tracks.Group group : currentTracks.getGroups()) {
                if (group.getType() == androidx.media3.common.C.TRACK_TYPE_AUDIO) {
                    for (int i = 0; i < group.length; i++) {
                        if (currentAudioGlobalIndex == trackIndex) {
                            exoPlayer.setTrackSelectionParameters(
                                    exoPlayer.getTrackSelectionParameters().buildUpon()
                                            .setOverrideForType(new androidx.media3.common.TrackSelectionOverride(group.getMediaTrackGroup(), i))
                                            .build()
                            );
                            return;
                        }
                        currentAudioGlobalIndex++;
                    }
                }
            }
        });
    }
}
