package com.aastream;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.SoftApConfiguration;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.util.Log;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

public class NetworkStreamHub {
    // Heavy tracking telemetry tag
    private static final String TAG = "AAStreamDebug";
    
    public static final UUID SERVICE_UUID   = UUID.fromString("67416741-6741-6741-6741-67416741aa5f");
    public static final UUID CHAR_SSID_UUID = UUID.fromString("67416741-6741-6741-6741-674167410001");
    public static final UUID CHAR_PASS_UUID = UUID.fromString("67416741-6741-6741-6741-674167410002");
    public static final UUID CHAR_IP_UUID   = UUID.fromString("67416741-6741-6741-6741-674167410003");
    private static final int PORT = 6741;

    private final Context context;
    private WifiManager.LocalOnlyHotspotReservation hotspotReservation;
    private BluetoothGattServer gattServer;
    private BluetoothLeAdvertiser advertiser;
    private ServerSocket serverSocket;
    private boolean isRunning = false;

    private String hotspotSSID = "";
    private String hotspotPassword = "";
    private String deviceIP = "0.0.0.0"; 

    public NetworkStreamHub(Context context) {
        this.context = context;
        Log.d(TAG, "[INIT] NetworkStreamHub component instantiated.");
    }

    public boolean isActive() {
        return isRunning;
    }

    @SuppressLint("MissingPermission")
    public void startNetworkPipeline() {
        Log.d(TAG, "[PIPELINE] Requesting startNetworkPipeline. State: isRunning=" + isRunning);
        if (isRunning) {
            Log.w(TAG, "[PIPELINE] Execution rejected: pipeline is already live.");
            return;
        }
        isRunning = true;
        WifiManager wifiManager = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        
        if (wifiManager == null) {
            Log.e(TAG, "[HOTSPOT] WifiManager missing from system context scope!");
            isRunning = false;
            return;
        }

        Log.d(TAG, "[HOTSPOT] Initializing startLocalOnlyHotspot process context...");
        wifiManager.startLocalOnlyHotspot(new WifiManager.LocalOnlyHotspotCallback() {
            @Override
            public void onStarted(WifiManager.LocalOnlyHotspotReservation reservation) {
                super.onStarted(reservation);
                Log.d(TAG, "[HOTSPOT] onStarted callback received from system framework tokens.");
                hotspotReservation = reservation;
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Log.d(TAG, "[HOTSPOT] Parsing Android 11+ SoftApConfiguration rules...");
                    SoftApConfiguration config = reservation.getSoftApConfiguration();
                    if (config != null) {
                        hotspotSSID = config.getSsid();
                        hotspotPassword = config.getPassphrase();
                    }
                } else {
                    Log.d(TAG, "[HOTSPOT] Parsing Legacy platform WifiConfiguration metrics...");
                    WifiConfiguration config = reservation.getWifiConfiguration();
                    if (config != null) {
                        hotspotSSID = config.SSID;
                        hotspotPassword = config.preSharedKey;
                    }
                }
                
                // Track dynamic interface updates
                deviceIP = resolveHotspotGatewayIP();
                Log.i(TAG, "[PROVISION_SUCCESS] SoftAP Dynamic Config Ready -> SSID: " + hotspotSSID + " | PASS: " + hotspotPassword + " | IP: " + deviceIP);
                
                // Force wild-card bind rules to guarantee access regardless of client Wi-Fi overlaps
                startServerSocket();

                Log.d(TAG, "[BT_GATT] Spawning BLE interface profile components...");
                setupBluetoothGattServer();
            }

            @Override
            public void onFailed(int reason) {
                super.onFailed(reason);
                Log.e(TAG, "[HOTSPOT_CRITICAL] Hotspot allocation hook returned error code: " + reason);
                isRunning = false;
            }
        }, new Handler(Looper.getMainLooper()));
    }

	/**
     * Inspects current LinkProperties to pull the dynamic IPv4 interface address assigned to the Hotspot.
     * Incorporates an asynchronous polling mechanism to survive Android Auto DHCP initialization lag.
     */
    private String resolveHotspotGatewayIP() {
        Log.d(TAG, "[IP_RESOLVER] Analyzing active system layout interfaces...");
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return "0.0.0.0";

        // Give the network stack up to 20 attempts (2 seconds total) to bind IP metrics to the interface
        for (int retry = 0; retry < 20; retry++) {
            Network[] networks = cm.getAllNetworks();
            for (Network network : networks) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(network);
                if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    LinkProperties lp = cm.getLinkProperties(network);
                    if (lp != null && lp.getInterfaceName() != null) {
                        String iface = lp.getInterfaceName();
                        
                        // CRUCIAL: Skip wlan0 client interface so we don't accidentally broadcast the wrong route IP
                        if ("wlan0".equalsIgnoreCase(iface)) {
                            continue;
                        }
                        
                        if (iface.contains("wlan") || iface.contains("ap") || iface.contains("local") || iface.contains("p2p")) {
                            for (LinkAddress la : lp.getLinkAddresses()) {
                                InetAddress addr = la.getAddress();
                                if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                                    Log.i(TAG, "[IP_RESOLVER] Successfully resolved AP target IP: " + addr.getHostAddress() + " on interface " + iface + " (Attempt " + (retry + 1) + ")");
                                    return addr.getHostAddress();
                                }
                            }
                        }
                    }
                }
            }
            
            // If we found the interface but no IP was bound yet, yield execution briefly
            try {
                Thread.sleep(100);
            } catch (InterruptedException ignored) {}
        }

        Log.w(TAG, "[IP_RESOLVER] CRITICAL: Post-provisioning interface polling exhausted. Operating system failed to bind IP in time.");
        return "192.168.43.1"; // Hard fallback
    }

	private void startServerSocket() {
	    new Thread(() -> {
	        try {
	            // Bind to the designated real-time streaming port
	            serverSocket = new ServerSocket(PORT);
	            Log.i(TAG, "[SOCKET_ONLINE] TCP socket listener standing by for connections on port " + PORT);
	
	            while (isRunning) {
	                try {
	                    // Block until a remote Python/Sink client establishes a link connection
	                    Socket clientSocket = serverSocket.accept();
	                    Log.i(TAG, "[CLIENT_CONNECTED] Raw link connection accepted from: " + clientSocket.getRemoteSocketAddress());
	
	                    // Hand the socket reference over to DisplayMgr's split-rendering pipeline
	                    com.aastream.car.DisplayMgr.handleNetworkClient(clientSocket);
	
	                } catch (IOException e) {
	                    // Check if the exception occurred due to a deliberate pipeline shutdown sequence
	                    if (!isRunning) {
	                        Log.i(TAG, "[SOCKET_CLEANUP] Server socket closed down during teardown.");
	                        break;
	                    }
	                    Log.e(TAG, "[SOCKET_LOOP_ERROR] Error accepting incoming client connection", e);
	                }
	            }
	        } catch (IOException e) {
	            Log.e(TAG, "[SOCKET_EXCEPTION] Exception encountered inside raw server socket context loop!", e);
	        } finally {
	            Log.w(TAG, "[SOCKET_SHUTDOWN] Server socket stream process terminated.");
	        }
	    }, "AAStreamServerSocketThread").start();
	}

    @SuppressLint("MissingPermission")
    private void setupBluetoothGattServer() {
        Log.d(TAG, "[GATT_SETUP] Registering profile attributes...");
        BluetoothManager bluetoothManager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        if (bluetoothManager == null) return;
        
        BluetoothAdapter adapter = bluetoothManager.getAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            Log.e(TAG, "[GATT_SETUP] Bluetooth stack adapter is closed or missing permissions.");
            return;
        }

        gattServer = bluetoothManager.openGattServer(context, new BluetoothGattServerCallback() {
            @Override
            public void onCharacteristicReadRequest(BluetoothDevice device, int requestId, int offset, BluetoothGattCharacteristic characteristic) {
                Log.d(TAG, "[GATT_READ] Read transaction requested by client on element: " + characteristic.getUuid());
                byte[] val = new byte[0];
                if (characteristic.getUuid().equals(CHAR_SSID_UUID) && hotspotSSID != null) {
                    val = hotspotSSID.getBytes(StandardCharsets.UTF_8);
                } else if (characteristic.getUuid().equals(CHAR_PASS_UUID) && hotspotPassword != null) {
                    val = hotspotPassword.getBytes(StandardCharsets.UTF_8);
                } else if (characteristic.getUuid().equals(CHAR_IP_UUID) && deviceIP != null) {
                    val = deviceIP.getBytes(StandardCharsets.UTF_8);
                }
                if (gattServer != null) {
                    gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, val);
                }
            }
        });

        BluetoothGattService service = new BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY);
        
        service.addCharacteristic(new BluetoothGattCharacteristic(CHAR_SSID_UUID, BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ));
        service.addCharacteristic(new BluetoothGattCharacteristic(CHAR_PASS_UUID, BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ));
        service.addCharacteristic(new BluetoothGattCharacteristic(CHAR_IP_UUID, BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ));
        
        if (gattServer != null) {
            gattServer.addService(service);
        }

        advertiser = adapter.getBluetoothLeAdvertiser();
        if (advertiser == null) return;

        AdvertiseSettings settings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setConnectable(true)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .build();

        AdvertiseData data = new AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .addServiceUuid(new ParcelUuid(SERVICE_UUID))
                .build();

        Log.d(TAG, "[GATT_ADV] Launching outward LE radio advertisement transmissions...");
        advertiser.startAdvertising(settings, data, new AdvertiseCallback() {
            @Override
            public void onStartSuccess(AdvertiseSettings settingsInEffect) {
                Log.i(TAG, "[GATT_ADV_ONLINE] GATT Advertisement running live and discoverable!");
            }

            @Override
            public void onStartFailure(int errorCode) {
                super.onStartFailure(errorCode);
                Log.e(TAG, "[GATT_ADV_FAILED] Advertisement initialization failed. Error Code: " + errorCode);
            }
        });
    }

    @SuppressLint("MissingPermission")
    public void shutdownPipeline() {
        Log.w(TAG, "[TEARDOWN] Stop signal intercept. Clearing streaming context layers...");
        if (!isRunning) return;
        isRunning = false;
        
        if (hotspotReservation != null) {
            hotspotReservation.close();
            hotspotReservation = null;
        }
        if (advertiser != null) {
            try {
                advertiser.stopAdvertising(new AdvertiseCallback() {});
            } catch (Exception ignored) {}
            advertiser = null;
        }
        if (gattServer != null) {
            gattServer.close();
            gattServer = null;
        }
        if (serverSocket != null) {
            try { serverSocket.close(); } catch (Exception ignored) {}
            serverSocket = null;
        }
        deviceIP = "0.0.0.0";
        Log.i(TAG, "[TEARDOWN_COMPLETE] Infrastructure pipeline closed down.");
    }
}
