#!/usr/bin/env python3
import asyncio
import socket
import sys
import subprocess
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
    out = subprocess.check_output(["nmcli", "-t", "-f", "DEVICE,TYPE", "device"], text=True)
    iface = next((line.split(":")[0] for line in out.splitlines() if line.split(":")[1] == "wifi"), None)
    if iface is None:
        raise RuntimeError("No Wi-Fi adapter found.")
    subprocess.run(["nmcli", "device", "wifi", "connect", ssid, "password", password], check=True)

import time

def render(ip):
    print(f"[*] Connecting to {ip}:{PORT}...")
    sock = None
    player_proc = None
    
    mpv_cmd = [
        "mpv",
        "-",                         # Read from standard input pipe
        "--profile=low-latency",
        "--untimed",
        "--no-cache",
        "--demuxer-lavf-format=h264", # Explicitly tell mpv this is raw H.264 video
        "--demuxer-lavf-o=probesize=32,analyzeduration=0",
        "--title=AAStream Live (H.264)"
    ]
    
    # Retry loop to wait for Android Auto to trigger onSurfaceAvailable
    max_attempts = 30
    attempt = 0
    while attempt < max_attempts:
        try:
            # Short timeout per attempt to quickly cycle back if server isn't up yet
            sock = socket.create_connection((ip, PORT), timeout=3)
            sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            print("[+] Connected! Initializing low-latency pipeline playback...")
            break
        except (socket.timeout, ConnectionRefusedError, OSError):
            attempt += 1
            print(f"[!] Android app not hosting stream yet (waiting for AA screen). Retrying ({attempt}/{max_attempts})...")
            time.sleep(2)
    else:
        print("[-] Critical Error: Could not connect to the Android stream pipeline. Is Android Auto active?")
        return

    try:
        # Spawn mpv process with stdin redirected to our pipe write loop
        player_proc = subprocess.Popen(mpv_cmd, stdin=subprocess.PIPE)
        
        while True:
            # Continuously ingest the raw stream blocks coming out of MediaCodec
            chunk = sock.recv(8192)
            if not chunk:
                print("[-] Sender terminated the video stream session context.")
                break
                
            player_proc.stdin.write(chunk)
            player_proc.stdin.flush()
            
            # Check if user closed the player window manually
            if player_proc.poll() is not None:
                print("[*] Playback window closed by user.")
                break
                
    except Exception as e:
        print(f"[!] Stream pipeline Error: {e}")
    finally:
        if sock:
            sock.close()
        if player_proc:
            if player_proc.poll() is None:
                player_proc.terminate()
                player_proc.wait()
        print("[*] Stream pipeline safely closed.")

async def main():
    try:
        ssid, password, ip = await extract_gatt_credentials()
        join_wifi(ssid, password)
        render(ip)
    except Exception as e:
        print(f"Error: {e}")

if __name__ == "__main__":
    asyncio.run(main())
