#!/usr/bin/env python3
import asyncio
import socket
import subprocess
import sys

from bleak import BleakClient, BleakScanner

SERVICE_UUID = "67416741-6741-6741-6741-67416741aa5f"
CHAR_SSID_UUID = "67416741-6741-6741-6741-674167410001"
CHAR_PASS_UUID = "67416741-6741-6741-6741-674167410002"
CHAR_IP_UUID = "67416741-6741-6741-6741-674167410003"
PORT = 6741


async def extract_gatt_credentials():
    print("[*] Scanning for GATT beacons...")

    device = await BleakScanner.find_device_by_filter(
        lambda d, a: a.service_uuids
        and SERVICE_UUID.lower() in [u.lower() for u in a.service_uuids]
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

    out = subprocess.check_output(
        ["nmcli", "-t", "-f", "DEVICE,TYPE", "device"],
        text=True
    )

    iface = next(
        (line.split(":")[0]
         for line in out.splitlines()
         if line.split(":")[1] == "wifi"),
        None
    )

    if iface is None:
        raise RuntimeError("No Wi-Fi adapter found.")

    subprocess.run(
        [
            "nmcli",
            "device",
            "wifi",
            "connect",
            ssid,
            "password",
            password,
        ],
        check=True,
    )


def render(ip):
    print(f"[*] Connecting to {ip}:{PORT}...")

    try:
        sock = socket.create_connection((ip, PORT), timeout=5)
        sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        
        # Enforce read execution timeout bounds during connection transmission phase
        sock.settimeout(3.0)
    except (socket.timeout, ConnectionRefusedError) as e:
        sys.exit(f"[!] Failed to establish TCP connection with streaming endpoint: {e}")

    print("[*] Starting ffplay...")

    cmd = [
        "ffplay",
        "-hide_banner",
        "-loglevel", "warning",

        "-fflags", "nobuffer",
        "-flags", "low_delay",

        # Bumping probe constraints prevents deadlocking when connecting mid-stream
        "-probesize", "500000",
        "-analyzeduration", "100000",

        "-framedrop",
        "-fast",

        "-sync", "video",
        "-vf", "setpts=0",

        "-window_title",
        "AAStream Real-time Sync Monitor",

        "-f", "h264",
        "-"
    ]

    proc = subprocess.Popen(
        cmd,
        stdin=subprocess.PIPE,
    )

    try:
        while proc.poll() is None:
            try:
                data = sock.recv(65536)
            except socket.timeout:
                print("\n[!] Stream data timeout reached. Android side encoder has locked up.")
                break

            if not data:
                print("[!] Remote device closed connection cleanly.")
                break

            try:
                proc.stdin.write(data)
                proc.stdin.flush()
            except BrokenPipeError:
                print("[!] ffplay pipe broken. Application likely closed by user.")
                break

    except KeyboardInterrupt:
        print("\n[*] Stopping sync monitor execution loop...")

    finally:
        sock.close()

        if proc.stdin:
            try:
                proc.stdin.close()
            except BrokenPipeError:
                pass

        if proc.poll() is None:
            proc.terminate()
            proc.wait()
            
        print("[*] Teardown complete. Ready for safe reconnection pipeline.")


async def main():
    ssid, password, ip = await extract_gatt_credentials()
    join_wifi(ssid, password)
    render(ip)


if __name__ == "__main__":
    asyncio.run(main())
