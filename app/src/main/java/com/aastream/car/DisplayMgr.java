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

	private static OutputStream networkOutputStream = null;
	private static final Object socketLock = new Object();
	private static volatile boolean isNetworkStreamingActive = false;

	private static MediaCodec videoEncoder = null;
	private static Surface encoderInputSurface = null;
	private static Surface originalDisplaySurface = null;
	private static Context cachedContext = null;

	private static byte[] cachedSpsPpsHeaders = null;

	private static HandlerThread copyThread = null;
	private static Handler copyHandler = null;
	private static final Handler mainHandler = new Handler(Looper.getMainLooper());

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
					new Handler(Looper.getMainLooper()).post(() -> manage_screen(cachedContext));
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

			trigger(lastKnownState);

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
				@Override public void onSurfaceTextureUpdated(SurfaceTexture st) {}
			});
			presentation.show(); 
		} catch (Exception e) { Log.e(TAG, "Presentation setup error", e); }
	}

	public static void handleNetworkClient(Socket clientSocket) {
		synchronized (socketLock) {
			try {
				if (networkOutputStream != null) networkOutputStream.close();
				networkOutputStream = clientSocket.getOutputStream();
				
				int target_w = ScreenBridge.width > 0 ? ScreenBridge.width : 800;
				int target_h = ScreenBridge.height > 0 ? ScreenBridge.height : 400;
				
				releaseEncoder();
	
				Log.i(TAG, "Configuring H.264 MediaCodec hardware streaming layout map at " + target_w + "x" + target_h);
				MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, target_w, target_h);
				
				format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
				format.setInteger(MediaFormat.KEY_BIT_RATE, 3500000); 
				format.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
				format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1); 
				
				if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
					format.setInteger(MediaFormat.KEY_LATENCY, 0);
					format.setInteger(MediaFormat.KEY_PRIORITY, 0); 
				}
	
				videoEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
				videoEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
				
				encoderInputSurface = videoEncoder.createInputSurface();
				videoEncoder.start();
				isNetworkStreamingActive = true;
	
				// Direct the display engine surface straight into our encoder pipeline
				if (display != null && encoderInputSurface != null) {
					display.setSurface(encoderInputSurface);
				}

				mainHandler.post(() -> {
					if (texture_view != null && text_view != null) {
						texture_view.setVisibility(View.VISIBLE);
						text_view.setVisibility(View.GONE);
						if (nativePlayerView != null) {
							nativePlayerView.setVisibility(View.GONE);
						}
						texture_view.invalidate();
					}
				});
	
				if (cachedSpsPpsHeaders != null) {
					try {
						networkOutputStream.write(cachedSpsPpsHeaders, 0, cachedSpsPpsHeaders.length);
						networkOutputStream.flush();
					} catch (IOException e) {
						Log.e(TAG, "Failed forwarding bootstrap headers to client context", e);
					}
				}
	
				startEncoderOutputLoop();
			} catch (IOException e) { 
				Log.e(TAG, "Failed initialization of network client stack", e); 
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

					int output_buffer_index = codec.dequeueOutputBuffer(buffer_info, 10000); 
					
					if (output_buffer_index >= 0) {
						ByteBuffer output_buffer = codec.getOutputBuffer(output_buffer_index);
						if (output_buffer != null && buffer_info.size > 0) {
							
							if ((buffer_info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
								cachedSpsPpsHeaders = new byte[buffer_info.size];
								output_buffer.position(buffer_info.offset);
								output_buffer.limit(buffer_info.offset + buffer_info.size);
								output_buffer.get(cachedSpsPpsHeaders);
								Log.i(TAG, "Cached SPS/PPS initialization blocks updated (" + buffer_info.size + " bytes)");
							}

							synchronized (socketLock) {
								if (networkOutputStream != null) {
									output_buffer.position(buffer_info.offset);
									output_buffer.limit(buffer_info.offset + buffer_info.size);
									
									byte[] out_data = new byte[buffer_info.size];
									output_buffer.get(out_data);
									networkOutputStream.write(out_data, 0, out_data.length);
									networkOutputStream.flush();
								}
							}
						}
						codec.releaseOutputBuffer(output_buffer_index, false);
					}
				}
			} catch (Exception e) {
				Log.e(TAG, "Encoder stream terminated safely during lifecycle transition", e);
				isNetworkStreamingActive = false;
			}
		}, "AAStreamEncoderOutThread").start();
	}

	private static void releaseEncoder() {
		isNetworkStreamingActive = false;
		
		if (videoEncoder != null) {
			try {
				videoEncoder.signalEndOfInputStream();
			} catch (Exception ignored) {}
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
		
		// Fallback display frame pipeline target routing safely back onto car view context
		if (display != null && originalDisplaySurface != null) {
			display.setSurface(originalDisplaySurface);
		}
	}

	public static void stopAllMediaEngines() {
		new Handler(Looper.getMainLooper()).post(() -> {
			releaseEncoder();
			synchronized (socketLock) {
				if (networkOutputStream != null) {
					try { networkOutputStream.close(); } catch (Exception ignored) {}
					networkOutputStream = null;
				}
			}
			
			if (text_view != null && texture_view != null) {
				texture_view.setVisibility(View.INVISIBLE);
				text_view.setVisibility(View.VISIBLE);
			}
			
			if (exoPlayer != null) { exoPlayer.stop(); exoPlayer.clearMediaItems(); }
		});
	}

	public static void create_display(VirtualDisplay incoming_display) { 
		display = incoming_display; 
	}
	
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
							return;
						}
						currentAudioGlobalIndex++;
					}
				}
			}
		});
	}
}
