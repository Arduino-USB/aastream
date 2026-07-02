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
import android.media.MediaFormat;
import android.os.Build;
import android.view.View;
import android.net.Uri;

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
	public static void handleNetworkClient(Socket clientSocket) {
		synchronized (socketLock) {
			try {
				Log.i(TAG, "[Network] Client established. Launching PixelCopy rendering extraction bridge...");
				if (networkOutputStream != null) {
					try { networkOutputStream.close(); } catch (IOException ignored) {}
				}
				networkOutputStream = clientSocket.getOutputStream();
				
				int target_w = ScreenBridge.width > 0 ? ScreenBridge.width : 800;
				int target_h = ScreenBridge.height > 0 ? ScreenBridge.height : 400;
				
				releaseEncoder();
	
				MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, target_w, target_h);
				format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
				format.setInteger(MediaFormat.KEY_BIT_RATE, 3000000); 
				format.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
				format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1); 
				format.setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 1000000 / 30);			

				if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
					format.setInteger(MediaFormat.KEY_LATENCY, 0);
					format.setInteger(MediaFormat.KEY_PRIORITY, 0); 
				}
	
				try {
					videoEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
					videoEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
					encoderInputSurface = videoEncoder.createInputSurface();
					videoEncoder.start();
				} catch (Exception codecException) {
					Log.e(TAG, "Codec spin up panic failure", codecException);
					releaseEncoder();
					return;
				}

				isNetworkStreamingActive = true;
				
				// Initialize frame worker extraction threads
				frameCaptureThread = new HandlerThread("AAStreamFrameCapture");
				frameCaptureThread.start();
				frameCaptureHandler = new Handler(frameCaptureThread.getLooper());

				if (cachedSpsPpsHeaders != null) {
					try {
						networkOutputStream.write(cachedSpsPpsHeaders, 0, cachedSpsPpsHeaders.length);
						networkOutputStream.flush();
					} catch (IOException ignored) {}
				}
	
				startEncoderOutputLoop();
				// Kickstart asynchronous loop processing
				frameCaptureHandler.post(new Runnable() {
				    @Override
				    public void run() {
				        if (!isNetworkStreamingActive || presentation == null || presentation.getWindow() == null || encoderInputSurface == null) {
				            return;
				        }
				        
				        try {
				            Bitmap bitmap = Bitmap.createBitmap(target_w, target_h, Bitmap.Config.ARGB_8888);
				            PixelCopy.request(presentation.getWindow(), bitmap, (copyResult) -> {
				                if (copyResult == PixelCopy.SUCCESS && isNetworkStreamingActive && encoderInputSurface != null) {
				                    try {
				                        // FIX: Use standard lockCanvas() instead of lockHardwareCanvas()
				                        android.graphics.Canvas canvas = encoderInputSurface.lockCanvas(null);
				                        if (canvas != null) {
				                            canvas.drawBitmap(bitmap, 0, 0, null);
				                            encoderInputSurface.unlockCanvasAndPost(canvas);
				                        }
				                    } catch (Exception ignored) {
				                        Log.e(TAG, "Canvas drawing failed", ignored);
				                    }
				                }
				                bitmap.recycle();
				                
				                // Schedule next capture frame check (approximates ~30 FPS sync target)
				                if (isNetworkStreamingActive && frameCaptureHandler != null) {
				                    frameCaptureHandler.postDelayed(this, 33);
				                }
				            }, frameCaptureHandler);
				        } catch (Exception e) {
				            if (isNetworkStreamingActive && frameCaptureHandler != null) {
				                frameCaptureHandler.postDelayed(this, 100);
				            }
				        }
				    }
				});
			} catch (IOException e) { 
				Log.e(TAG, "Network configuration handshake error", e); 
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
