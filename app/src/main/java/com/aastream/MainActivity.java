package com.aastream;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.Toast;

import com.aastream.car.DisplayMgr;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity implements DisplayMgr.PlayerStateListener {

    private static final String TAG = "AAStreamDebug";
    private static final int REQ_CODE = 1000;
    private static final int OVERLAY_REQ_CODE = 2000; 
    private static final int PICK_VIDEO_CODE = 3001;
    private static final int PICK_SRT_CODE = 3002;
    private static final int PERMS_REQ_CODE = 4001;

    private MediaProjectionManager projection_manager;
    private Button btn_toggle, btnNetworkStream, btnNativeVideo, btnMediaPause, btnMediaStop;
    private LinearLayout layoutMediaControls;
    private Spinner spinnerSubtitles, spinnerAudioTracks;
    
    private ArrayList<String> subtitleList;
    private ArrayAdapter<String> subtitleAdapter;
    
    private ArrayList<String> audioTrackList;
    private ArrayAdapter<String> audioTrackAdapter;
    
    private NetworkStreamHub netHub;
    private int customTrackCounter = 1;
    private Uri currentVideoUri = null;
    private boolean isPlaybackCompleted = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        projection_manager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        netHub = new NetworkStreamHub(this);

        btn_toggle = findViewById(R.id.btn_toggle_stream);
        btnNetworkStream = findViewById(R.id.btn_network_stream);
        btnNativeVideo = findViewById(R.id.btn_native_video);
        btnMediaPause = findViewById(R.id.btn_media_pause);
        btnMediaStop = findViewById(R.id.btn_media_stop);
        layoutMediaControls = findViewById(R.id.layout_media_controls);
        spinnerSubtitles = findViewById(R.id.spinner_subtitles);
        spinnerAudioTracks = findViewById(R.id.spinner_audio_tracks); // Ensure this component is added in layout resource

        btn_toggle.setText("Start Screen Capture");
        btnNetworkStream.setText("Start Network Stream");

        DisplayMgr.setPlayerStateListener(this);

        // Subtitle Spinner setup
        subtitleList = new ArrayList<>();
        subtitleList.add("No Track");
        subtitleList.add("Add New Track...");
        subtitleAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, subtitleList);
        subtitleAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerSubtitles.setAdapter(subtitleAdapter);

        // Audio Track Spinner Setup
        audioTrackList = new ArrayList<>();
        audioTrackAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, audioTrackList);
        audioTrackAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerAudioTracks.setAdapter(audioTrackAdapter);

        btn_toggle.setOnClickListener(v -> {
            if (!checkAndRequestOverlayPermission()) return;
            if (ScreenBridge.service != null) {
                stopScreenCaptureService();
            } else {
                Intent svc = new Intent(this, ScreenCaptureService.class);
                svc.setAction("START_PROJECTION_PREP");
                startForegroundService(svc);
                
                Intent i = projection_manager.createScreenCaptureIntent();
                startActivityForResult(i, REQ_CODE);
            }
        });

        btnNetworkStream.setOnClickListener(v -> {
            if (netHub.isActive()) {
                netHub.shutdownPipeline();
                btnNetworkStream.setText("Start Network Stream");
            } else {
                checkAndStartNetworkPipeline();
            }
        });

        btnNativeVideo.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.setType("video/*");
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(intent, PICK_VIDEO_CODE);
        });

        btnMediaPause.setOnClickListener(v -> {
            if (DisplayMgr.exoPlayer != null) {
                if (DisplayMgr.exoPlayer.isPlaying()) {
                    DisplayMgr.exoPlayer.pause();
                    btnMediaPause.setText("Resume");
                } else {
                    if (isPlaybackCompleted) {
                        restartVideoPlayback();
                    } else {
                        DisplayMgr.exoPlayer.play();
                    }
                    btnMediaPause.setText("Pause");
                }
            }
        });

        btnMediaStop.setOnClickListener(v -> {
            if (isPlaybackCompleted) {
                restartVideoPlayback();
            } else {
                teardownVideoPlaybackUi();
            }
        });

        spinnerSubtitles.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String selected = subtitleList.get(position);
                if ("Add New Track...".equals(selected)) {
                    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    intent.setType("*/*"); 
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    startActivityForResult(intent, PICK_SRT_CODE);
                } else if ("No Track".equals(selected)) {
                    DisplayMgr.clearSubtitles();
                }
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        spinnerAudioTracks.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String selected = audioTrackList.get(position);
                if ("No Track".equals(selected)) {
                    DisplayMgr.selectAudioTrack(-1);
                } else {
                    // Offset index by 1 due to "No Track" element sitting at position 0
                    DisplayMgr.selectAudioTrack(position - 1);
                }
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    private void restartVideoPlayback() {
        if (currentVideoUri != null) {
            isPlaybackCompleted = false;
            btnMediaStop.setText("Stop");
            btnMediaPause.setText("Pause");
            btnMediaPause.setEnabled(true);
            DisplayMgr.playNativeVideoFile(currentVideoUri);
        }
    }

    @Override
    public void onPlaybackEnded() {
        runOnUiThread(() -> {
            isPlaybackCompleted = true;
            btnMediaStop.setText("Restart");
            btnMediaPause.setText("Pause");
            btnMediaPause.setEnabled(false);
        });
    }

    @Override
    public void onTracksChanged(List<String> audioTracks) {
        runOnUiThread(() -> {
            audioTrackList.clear();
            audioTrackList.addAll(audioTracks);
            audioTrackList.add(0, "No Track");
            audioTrackAdapter.notifyDataSetChanged();
            if (audioTrackList.size() > 1) {
                spinnerAudioTracks.setSelection(1); // Set primary default track active
            } else {
                spinnerAudioTracks.setSelection(0);
            }
        });
    }

    private void checkAndStartNetworkPipeline() {
        ArrayList<String> permissions = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE);
            }
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.BLUETOOTH_CONNECT);
            }
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES);
            }
        } else {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
            }
        }

        if (!permissions.isEmpty()) {
            requestPermissions(permissions.toArray(new String[0]), PERMS_REQ_CODE);
        } else {
            netHub.startNetworkPipeline();
            btnNetworkStream.setText("Stop Network Stream");
        }
    }

    private void stopScreenCaptureService() {
        Intent svc = new Intent(this, ScreenCaptureService.class);
        stopService(svc);
        ScreenBridge.service = null;
        btn_toggle.setText("Start Screen Capture");
    }

    private void teardownVideoPlaybackUi() {
        DisplayMgr.stopVideoPlaybackEngine();
        layoutMediaControls.setVisibility(View.GONE);
        btnNativeVideo.setEnabled(true);
        btnMediaPause.setText("Pause");
        btnMediaPause.setEnabled(true);
        btnMediaStop.setText("Stop");
        isPlaybackCompleted = false;
        currentVideoUri = null;
        
        subtitleList.clear();
        subtitleList.add("No Track");
        subtitleList.add("Add New Track...");
        customTrackCounter = 1;
        subtitleAdapter.notifyDataSetChanged();
        spinnerSubtitles.setSelection(0);

        audioTrackList.clear();
        audioTrackAdapter.notifyDataSetChanged();
    }

    private boolean checkAndRequestOverlayPermission() {
        if (!Settings.canDrawOverlays(this)) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()));
            startActivityForResult(intent, OVERLAY_REQ_CODE);
            return false;
        }
        return true;
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null) return;

        if (req == REQ_CODE) {
            Intent svc = new Intent(this, ScreenCaptureService.class);
            svc.setAction("COMMAND_START_STREAM");
            svc.putExtra("result_code", res);
            svc.putExtra("data_intent", data);
            startForegroundService(svc);
            btn_toggle.setText("Stop Screen Capture");
        } else if (req == PICK_VIDEO_CODE) {
            layoutMediaControls.setVisibility(View.VISIBLE);
            btnNativeVideo.setEnabled(false);
            currentVideoUri = data.getData();
            isPlaybackCompleted = false;
            btnMediaStop.setText("Stop");
            DisplayMgr.playNativeVideoFile(currentVideoUri);
        } else if (req == PICK_SRT_CODE) {
            int insertIndex = subtitleList.size() - 1;
            String trackName = "Custom Track #" + (customTrackCounter++);
            subtitleList.add(insertIndex, trackName);
            subtitleAdapter.notifyDataSetChanged();
            spinnerSubtitles.setSelection(insertIndex);
            DisplayMgr.injectExternalSubtitles(data.getData());
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == PERMS_REQ_CODE) {
            boolean clean = true;
            for (int r : grantResults) {
                if (r != PackageManager.PERMISSION_GRANTED) clean = false;
            }
            if (clean) {
                netHub.startNetworkPipeline();
                btnNetworkStream.setText("Stop Network Stream");
            } else {
                Toast.makeText(this, "Permissions required for wireless stream initialization rejected.", Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    protected void onDestroy() {
        netHub.shutdownPipeline();
        DisplayMgr.stopAllMediaEngines();
        super.onDestroy();
    }
}
