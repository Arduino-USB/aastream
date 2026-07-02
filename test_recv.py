#!/usr/bin/env python3
import asyncio
import socket
import sys
import cv2
import numpy as np
from bleak import BleakClient, BleakScanner

SERVICE_UUID = "67416741-6741-6741-6741-67416741aa5f"
CHAR_SSID_UUID = "67416741-6741-6741-6741-674167410001"
CHAR_PASS_UUID = "67416741-6741-6741-6741-674167410002"
CHAR_IP_UUID = "67416741-6741-6741-6741-674167410003"
PORT = 6741

async def extract_gatt_credentials():
    print("[*] Scanning for GATT beacons...")
    device = await BleakScanner.find_device_by_filter(
        lambda d, a: a.service_uuids and SERVICE_UUID.lower() in [u.lower() for u in a.service_uuids]
    )
    if not device:
        sys.exit("Target device not found.")
    
    async with BleakClient(device) as client:
        ssid = (await client.read_gatt_char(CHAR_SSID_UUID)).decode().strip('"')
        password = (await client.read_gatt_char(CHAR_PASS_UUID)).decode()
        ip = (await client.read_gatt_char(CHAR_IP_UUID)).decode()
    return ssid, password, ip

def join_wifi(ssid, password):
    print(f"[*] Connecting to Wi-Fi '{ssid}'...")
    import subprocess
    out = subprocess.check_output(["nmcli", "-t", "-f", "DEVICE,TYPE", "device"], text=True)
    iface = next((line.split(":")[0] for line in out.splitlines() if line.split(":")[1] == "wifi"), None)
    if iface is None:
        raise RuntimeError("No Wi-Fi adapter found.")
    subprocess.run(["nmcli", "device", "wifi", "connect", ssid, "password", password], check=True)
def render(ip):
    print(f"[*] Connecting to {ip}:{PORT}...")
    sock = None
    window_name = "AAStream Live"
    cv2.namedWindow(window_name, cv2.WINDOW_NORMAL)
    
    try:
        sock = socket.create_connection((ip, PORT), timeout=10)
        sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        print("[+] Connected. Receiving live stream...")
        
        while True:
            # Read size
            size_data = b''
            while len(size_data) < 4:
                chunk = sock.recv(4 - len(size_data))
                if not chunk:
                    print("[-] Sender closed connection")
                    return
                size_data += chunk
            
            size = int.from_bytes(size_data, byteorder='big')
            if size > 2_000_000:  # safety limit
                print(f"[!] Suspicious frame size: {size}")
                continue
            
            # Read JPEG data
            data = b''
            while len(data) < size:
                chunk = sock.recv(min(65536, size - len(data)))
                if not chunk:
                    print("[-] Connection broken during frame")
                    return
                data += chunk
            
            # Decode and display
            try:
                nparr = np.frombuffer(data, np.uint8)
                frame = cv2.imdecode(nparr, cv2.IMREAD_COLOR)
                if frame is not None:
                    cv2.imshow(window_name, frame)
                    if cv2.waitKey(1) & 0xFF == ord('q'):
                        break
                    print(f"✓ Frame {len(data)/1024:.1f} KB")
                else:
                    print("Failed to decode JPEG")
            except Exception as e:
                print(f"Decode error: {e}")
                
    except Exception as e:
        print(f"[!] Error: {e}")
    finally:
        if sock:
            sock.close()
        cv2.destroyAllWindows()
async def main():
    try:
        ssid, password, ip = await extract_gatt_credentials()
        join_wifi(ssid, password)
        render(ip)
    except Exception as e:
        print(f"Error: {e}")
if __name__ == "__main__":
    asyncio.run(main())
