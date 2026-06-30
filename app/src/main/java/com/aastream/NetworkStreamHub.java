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
    private volatile boolean isRunning = false;

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
        if (isRunning) return;
        isRunning = true;
        WifiManager wifiManager = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        
        if (wifiManager == null) {
            isRunning = false;
            return;
        }

        wifiManager.startLocalOnlyHotspot(new WifiManager.LocalOnlyHotspotCallback() {
            @Override
            public void onStarted(WifiManager.LocalOnlyHotspotReservation reservation) {
                super.onStarted(reservation);
                hotspotReservation = reservation;
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    SoftApConfiguration config = reservation.getSoftApConfiguration();
                    if (config != null) {
                        hotspotSSID = config.getSsid();
                        hotspotPassword = config.getPassphrase();
                    }
                } else {
                    WifiConfiguration config = reservation.getWifiConfiguration();
                    if (config != null) {
                        hotspotSSID = config.SSID;
                        hotspotPassword = config.preSharedKey;
                    }
                }
                
                deviceIP = resolveHotspotGatewayIP();
                startServerSocket();
                setupBluetoothGattServer();
            }

            @Override
            public void onFailed(int reason) {
                super.onFailed(reason);
                Log.e(TAG, "[HOTSPOT_CRITICAL] Hotspot allocation failed: " + reason);
                isRunning = false;
            }
        }, new Handler(Looper.getMainLooper()));
    }

    private String resolveHotspotGatewayIP() {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return "0.0.0.0";

        for (int retry = 0; retry < 20; retry++) {
            Network[] networks = cm.getAllNetworks();
            for (Network network : networks) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(network);
                if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    LinkProperties lp = cm.getLinkProperties(network);
                    if (lp != null && lp.getInterfaceName() != null) {
                        String iface = lp.getInterfaceName();
                        if ("wlan0".equalsIgnoreCase(iface)) continue;
                        
                        if (iface.contains("wlan") || iface.contains("ap") || iface.contains("local") || iface.contains("p2p")) {
                            for (LinkAddress la : lp.getLinkAddresses()) {
                                InetAddress addr = la.getAddress();
                                if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                                    return addr.getHostAddress();
                                }
                            }
                        }
                    }
                }
            }
            try { Thread.sleep(100); } catch (InterruptedException ignored) {}
        }
        return "192.168.43.1";
    }

    private void startServerSocket() {
        new Thread(() -> {
            try {
                serverSocket = new ServerSocket(PORT);
                while (isRunning) {
                    try {
                        Socket clientSocket = serverSocket.accept();
                        clientSocket.setTcpNoDelay(true); // Disable Nagle's algorithm for instant streaming chunks
                        com.aastream.car.DisplayMgr.handleNetworkClient(clientSocket);
                    } catch (IOException e) {
                        if (!isRunning) break;
                    }
                }
            } catch (IOException e) {
                Log.e(TAG, "[SOCKET_EXCEPTION] Exception in socket context loop", e);
            }
        }, "AAStreamServerSocketThread").start();
    }

    @SuppressLint("MissingPermission")
    private void setupBluetoothGattServer() {
        BluetoothManager bluetoothManager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        if (bluetoothManager == null) return;
        
        BluetoothAdapter adapter = bluetoothManager.getAdapter();
        if (adapter == null || !adapter.isEnabled()) return;

        gattServer = bluetoothManager.openGattServer(context, new BluetoothGattServerCallback() {
            @Override
            public void onCharacteristicReadRequest(BluetoothDevice device, int requestId, int offset, BluetoothGattCharacteristic characteristic) {
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
        
        if (gattServer != null) gattServer.addService(service);

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

        advertiser.startAdvertising(settings, data, new AdvertiseCallback() {
            @Override public void onStartSuccess(AdvertiseSettings settingsInEffect) {}
            @Override public void onStartFailure(int errorCode) {}
        });
    }

    @SuppressLint("MissingPermission")
    public void shutdownPipeline() {
        if (!isRunning) return;
        isRunning = false;
        
        if (hotspotReservation != null) {
            hotspotReservation.close();
            hotspotReservation = null;
        }
        if (advertiser != null) {
            try { advertiser.stopAdvertising(new AdvertiseCallback() {}); } catch (Exception ignored) {}
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
        com.aastream.car.DisplayMgr.stopAllMediaEngines();
    }
}
