package com.aastream.car;

import android.hardware.display.VirtualDisplay;
import android.view.Surface;
import android.content.Context;
import android.app.Presentation;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.widget.TextView;
import android.view.TextureView;
import android.view.WindowManager;
import android.graphics.SurfaceTexture;
import android.graphics.Bitmap;
import android.view.PixelCopy;
import android.util.Log;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.Build;
import android.view.View;
import android.net.Uri;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;

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

	private static OutputStream networkOutputStream = null;
	private static final Object socketLock = new Object();
	private static volatile boolean isNetworkStreamingActive = false;

	private static MediaCodec videoEncoder = null;
	private static Surface encoderInputSurface = null;
	private static Surface originalDisplaySurface = null;
	private static Context cachedContext = null;

	private static byte[] cachedSpsPpsHeaders = null;
	private static final Handler mainHandler = new Handler(Looper.getMainLooper());
	
	// Separate thread context to run the frame collection engine away from the UI thread
	private static HandlerThread frameCaptureThread = null;
	private static Handler frameCaptureHandler = null;

	public interface PlayerStateListener {
		void onPlaybackEnded();
		void onTracksChanged(List<String> audioTracks);
	}

	private static PlayerStateListener uiListener = null;

	public DisplayMgr(Context context) {
		cachedContext = context.getApplicationContext();
		new Thread(() -> {
			while(true) {	
				try { Thread.sleep(500); } catch (Exception ignored) {}
				if (display != null) {
					mainHandler.post(() -> manage_screen(cachedContext));
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
	
	private static void manage_screen(Context context) {
    if (display == null) return;
    try {
        Context displayContext = context.createDisplayContext(display.getDisplay());
        presentation = new Presentation(displayContext, display.getDisplay());	
        
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

        texture_view.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
                st.setDefaultBufferSize(w, h);
                originalDisplaySurface = new Surface(st);
                
                ScreenBridge.surface = originalDisplaySurface;
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
                originalDisplaySurface = null;
                ScreenBridge.surface = null; 
                DisplayMgr.trigger(false); 
                return true;
            }
            @Override public void onSurfaceTextureUpdated(SurfaceTexture st) {
                // Keep frame loops intact
            }
        });

        // 1. Show the presentation window FIRST so the surface layers exist in the system window manager
        presentation.show(); 
        
        // 2. Call trigger AFTER show() so views modify visibility contexts safely inside an existing window
        trigger(lastKnownState);

    } catch (Exception e) { Log.e(TAG, "Presentation setup error", e); }
}
// Add at class level
private static MediaCodec createAndConfigureEncoder(int suggestedWidth, int suggestedHeight) {
    try {
        // Find the best encoder
        MediaCodecList codecList = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
        String encoderName = null;

        for (MediaCodecInfo info : codecList.getCodecInfos()) {
            if (!info.isEncoder()) continue;
            for (String type : info.getSupportedTypes()) {
                if (type.equalsIgnoreCase(MediaFormat.MIMETYPE_VIDEO_AVC)) {
                    encoderName = info.getName();
                    Log.i(TAG, "[Encoder] Found AVC encoder: " + encoderName);
                    break;
                }
            }
            if (encoderName != null) break;
        }

        if (encoderName == null) {
            Log.e(TAG, "[Encoder] No AVC encoder found!");
            return null;
        }

        MediaCodec encoder = MediaCodec.createByCodecName(encoderName);
        MediaCodecInfo info = encoder.getCodecInfo();
        MediaCodecInfo.CodecCapabilities caps = info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC);
        MediaCodecInfo.VideoCapabilities videoCaps = caps.getVideoCapabilities();

        if (videoCaps == null) {
            Log.e(TAG, "[Encoder] No VideoCapabilities");
            encoder.release();
            return null;
        }

        // Clamp to supported ranges
        int width = videoCaps.getSupportedWidths().clamp(suggestedWidth);
        int height = videoCaps.getSupportedHeightsFor(width).clamp(suggestedHeight);

        // Ensure even and minimum alignment
        width = Math.max(320, (width / 16) * 16);   // many encoders want multiple of 16
        height = Math.max(240, (height / 16) * 16);

        Log.i(TAG, "[Encoder] Using supported resolution: " + width + "x" + height);

        MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, Math.max(2000000, width * height * 3)); // higher bitrate often helps
        format.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        format.setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 33333); // ~30fps

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            format.setInteger(MediaFormat.KEY_LATENCY, 0);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
        }

        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        encoderInputSurface = encoder.createInputSurface();
        encoder.start();

        Log.i(TAG, "[Encoder] ✅ Successfully configured using proper capabilities");
        return encoder;

    } catch (Exception e) {
        Log.e(TAG, "[Encoder] Failed to configure with capabilities", e);
        return null;
    }
}
private static void sendBitmapOverNetwork(Bitmap bitmap) {
    if (bitmap == null || networkOutputStream == null) return;
    
    try {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, 65, baos);
        byte[] data = baos.toByteArray();
        
        synchronized (socketLock) {
            if (networkOutputStream != null && isNetworkStreamingActive) {
                DataOutputStream dos = new DataOutputStream(networkOutputStream);
                dos.writeInt(data.length);
                dos.write(data);
                dos.flush();
            }
        }
    } catch (IOException e) {
        Log.e(TAG, "Failed to send bitmap over network", e);
        isNetworkStreamingActive = false;
    }
}
public static void handleNetworkClient(Socket clientSocket) {
    synchronized (socketLock) {
        try {
            Log.i(TAG, "[Network] Client established. Using raw bitmap streaming (no encoder)...");
            
            if (networkOutputStream != null) {
                try { networkOutputStream.close(); } catch (IOException ignored) {}
            }
            networkOutputStream = clientSocket.getOutputStream();
            
            isNetworkStreamingActive = true;
            
            // Setup frame capture thread
            frameCaptureThread = new HandlerThread("AAStreamBitmapCapture", 
                    android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY);
            frameCaptureThread.start();
            frameCaptureHandler = new Handler(frameCaptureThread.getLooper());

            // Start sending bitmaps
            frameCaptureHandler.post(new Runnable() {
                private int frameCount = 0;

                @Override
                public void run() {
                    if (!isNetworkStreamingActive || presentation == null || 
                        presentation.getWindow() == null) {
                        return;
                    }

                    frameCount++;
                    if (frameCount % 30 == 0) {
                        Log.i(TAG, "[Bitmap] Sending frame #" + frameCount);
                    }

                    int w = ScreenBridge.width > 0 ? ScreenBridge.width : 800;
                    int h = ScreenBridge.height > 0 ? ScreenBridge.height : 480;

                    Bitmap bitmap = null;
                    try {
                        bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                        
                        final Bitmap finalBitmap = bitmap;  // Make it effectively final for lambda
                        
                        PixelCopy.request(presentation.getWindow(), bitmap, (copyResult) -> {
                            if (copyResult == PixelCopy.SUCCESS) {
                                sendBitmapOverNetwork(finalBitmap);
                            } else {
                                Log.w(TAG, "PixelCopy failed: " + copyResult);
                            }
                            
                            if (finalBitmap != null) {
                                finalBitmap.recycle();
                            }

                            // Schedule next frame
                            if (isNetworkStreamingActive && frameCaptureHandler != null) {
                                frameCaptureHandler.postDelayed(this, 50);
                            }
                        }, frameCaptureHandler);

                    } catch (Exception e) {
                        Log.e(TAG, "Bitmap capture error", e);
                        if (bitmap != null) {
                            bitmap.recycle();
                        }
                        
                        if (isNetworkStreamingActive && frameCaptureHandler != null) {
                            frameCaptureHandler.postDelayed(this, 100);
                        }
                    }
                }
            });

        } catch (IOException e) { 
            Log.e(TAG, "Network client setup error", e); 
        }
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
									
									byte[] out_data = new byte[buffer_info.size];
									output_buffer.get(out_data);
									try {
										networkOutputStream.write(out_data, 0, out_data.length);
										networkOutputStream.flush();
									} catch (IOException netEx) {
										isNetworkStreamingActive = false;
									}
								}
							}
						}
						try { codec.releaseOutputBuffer(output_buffer_index, false); } catch (Exception ignored) {}
					}
				}
			} catch (Exception ignored) {}
		}, "AAStreamEncoderOutThread").start();
	}

	public static void releaseEncoder() {
		isNetworkStreamingActive = false;
		
		if (frameCaptureThread != null) {
			frameCaptureThread.quitSafely();
			frameCaptureThread = null;
			frameCaptureHandler = null;
		}

		if (videoEncoder != null) {
			try { videoEncoder.stop(); } catch (Exception ignored) {}
			try { videoEncoder.release(); } catch (Exception ignored) {}
			videoEncoder = null;
		}
		if (encoderInputSurface != null) {
			encoderInputSurface.release();
			encoderInputSurface = null;
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

	public static void create_display(VirtualDisplay incoming_display) { display = incoming_display; }
	public static boolean display_created(){ return display != null; }
	public static void apply_surface(Surface surface) {
		originalDisplaySurface = surface;
		if (display != null) {
			display.setSurface(surface);
			if (presentation != null && !presentation.isShowing()) {
				try { presentation.show(); } catch (Exception ignored) {}
			}
		}
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
					exoPlayer.getTrackSelectionParameters()
							.buildUpon()
							.clearOverridesOfType(androidx.media3.common.C.TRACK_TYPE_TEXT)
							.build()
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
									exoPlayer.getTrackSelectionParameters()
											.buildUpon()
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
