#!/usr/bin/env python3
import asyncio
import socket
import sys
import subprocess
import struct
import time
import threading
import queue
import cv2
import av
from bleak import BleakClient, BleakScanner

# --- CONFIGURATION & UUIDS ---
SERVICE_UUID = "67416741-6741-6741-6741-67416741aa5f"
CHAR_SSID_UUID = "67416741-6741-6741-6741-674167410001"
CHAR_PASS_UUID = "67416741-6741-6741-6741-674167410002"
CHAR_IP_UUID = "67416741-6741-6741-6741-674167410003"
PORT = 6741

# --- PIPELINE CONFIGURATION ---
# Mode 1: Respect Android timestamps (Timed-smooth presentation)
# Mode 2: Ultra-low latency mode (Drop old frames, display instantly)
DISPLAY_MODE = 2 

# Bounded queue sizes to strictly prevent memory inflation and bufferbloat
PACKET_QUEUE_MAXSIZE = 4
FRAME_QUEUE_MAXSIZE = 2

# Global termination flag
running = True

# --- GATT & WI-FI INITIALIZATION ---
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

# --- THREAD 1: TCP RECEIVE ---
def tcp_receiver_thread(ip, packet_queue):
    global running
    print(f"[*] Connecting to {ip}:{PORT}...")
    
    max_attempts = 30
    attempt = 0
    sock = None
    
    while attempt < max_attempts and running:
        try:
            sock = socket.create_connection((ip, PORT), timeout=3)
            sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            print("[+] Connected to stream pipeline server!")
            break
        except (socket.timeout, ConnectionRefusedError, OSError):
            attempt += 1
            print(f"[!] Server not active yet. Retrying ({attempt}/{max_attempts})...")
            time.sleep(2)
    else:
        print("[-] Critical Error: Could not connect to the Android stream pipeline.")
        running = False
        return

    try:
        header_struct = struct.Struct("!IQi") # Pre-compiled structural format parser
        
        while running:
            # Read 16-byte custom framing header
            header_bytes = b""
            while len(header_bytes) < 16 and running:
                packet = sock.recv(16 - len(header_bytes))
                if not packet:
                    break
                header_bytes += packet
            
            if len(header_bytes) < 16:
                print("[-] Sender terminated stream or sent malformed header.")
                break
                
            payload_length, presentation_time_us, flags = header_struct.unpack(header_bytes)
            
            # Read payload_length bytes of raw H.264 Annex-B data
            video_data = bytearray(payload_length)
            view = memoryview(video_data)
            bytes_read = 0
            
            while bytes_read < payload_length and running:
                packet = sock.recv_into(view[bytes_read:], payload_length - bytes_read)
                if not packet:
                    break
                bytes_read += packet
                
            if bytes_read < payload_length:
                print("[-] Dropped connection mid-frame payload.")
                break
            
            # Wrap in mutable/sharable package state for the decoder thread
            packet_item = (video_data, presentation_time_us, flags)
            
            try:
                # Use non-blocking put or catch full queue to prune bufferbloat early
                packet_queue.put(packet_item, timeout=0.1)
            except queue.Full:
                try:
                    packet_queue.get_nowait() # Drop oldest packet to make space
                    packet_queue.put_nowait(packet_item)
                except queue.Empty:
                    pass

    except Exception as e:
        print(f"[!] Receiver Thread Error: {e}")
    finally:
        if sock:
            sock.close()
        running = False
        print("[*] Receiver Thread Safely Shutdown.")

# --- THREAD 2: PACKET DECODE ---
# --- THREAD 2: PACKET DECODE ---
def packet_decoder_thread(packet_queue, frame_queue):
    global running
    
    # Initialize PyAV H264 low-latency decoder configurations
    codec = av.CodecContext.create("h264", "r")
    
    # Direct FFmpeg internal optimization flags for real-time streams
    codec.options = {
        "flags": "low_delay",
        "fflags": "nobuffer",
        "threads": "auto"
    }

    try:
        while running:
            try:
                video_data, presentation_time_us, flags = packet_queue.get(timeout=0.1)
            except queue.Empty:
                continue

            # Instantiate the raw PyAV packet wrapping the raw byte data
            packet = av.Packet(video_data)
            
            # Pass our microsecond timestamps directly into the packet metadata.
            # PyAV will carry these untouched straight through to the decoded Frame objects.
            packet.pts = presentation_time_us
            packet.dts = presentation_time_us
            
            try:
                # High performance decoding pass yielding zero-copy Frame objects
                frames = codec.decode(packet)
                for frame in frames:
                    # Convert YUV420p frame natively to BGR for OpenCV processing
                    bgr_img = frame.to_ndarray(format="bgr24")
                    
                    # frame.pts preserves the exact microsecond integer we passed in
                    frame_item = (bgr_img, frame.pts)
                    
                    try:
                        frame_queue.put(frame_item, timeout=0.05)
                    except queue.Full:
                        if DISPLAY_MODE == 2:
                            # Mode 2 Drop Execution: Flush oldest frame in queue to keep display completely fresh
                            try:
                                frame_queue.get_nowait()
                                frame_queue.put_nowait(frame_item)
                            except queue.Empty:
                                pass
                        else:
                            # Mode 1: Block briefly until space clears to avoid skipping frames
                            frame_queue.put(frame_item)
            except av.FFmpegError as decode_err:
                print(f"[!] PyAV Decoding Exception encountered: {decode_err}")
                continue

        # Flush decoder buffer on teardown
        try:
            codec.decode(None)
        except Exception:
            pass

    except Exception as e:
        print(f"[!] Decoder Thread Error: {e}")
    finally:
        running = False
        print("[*] Decoder Thread Safely Shutdown.")
# --- THREAD 3: DISPLAY ENGINE ---
def display_engine(frame_queue):
    global running
    
    cv2.namedWindow("AAStream Live", cv2.WINDOW_AUTOSIZE)
    
    start_wall_time = None
    start_pts_time = None

    try:
        while running:
            try:
                # Mode 2 optimizations: clear out any latent queue backlog and jump immediately to the newest frame
                if DISPLAY_MODE == 2:
                    bgr_img, pts = None, None
                    while True:
                        try:
                            bgr_img, pts = frame_queue.get_nowait()
                        except queue.Empty:
                            break
                    if bgr_img is None:
                        # If queue was empty, block natively until a new one arrives
                        bgr_img, pts = frame_queue.get(timeout=0.05)
                else:
                    # Mode 1 behavior: Sequentially read frames to maintain target timestamp sync
                    bgr_img, pts = frame_queue.get(timeout=0.05)
            except queue.Empty:
                if cv2.waitKey(1) & 0xFF == 27: # Check for ESC key
                    break
                continue

            # --- MODE 1: TIMING SYNCHRONIZATION ---
            if DISPLAY_MODE == 1:
                if start_wall_time == None:
                    start_wall_time = time.perf_counter()
                    start_pts_time = pts / 1000000.0  # Convert µs to seconds
                
                current_wall_elapsed = time.perf_counter() - start_wall_time
                frame_pts_elapsed = (pts / 1000000.0) - start_pts_time
                time_difference = frame_pts_elapsed - current_wall_elapsed
                
                if time_difference > 0.001:
                    # Frame arrived early: sleep off the calculated duration delta
                    time.sleep(time_difference)
                # If late (time_difference <= 0), drop straight through to display immediately

            # Direct display rendering via OpenCV
            cv2.imshow("AAStream Live", bgr_img)
            
            # Essential UI event loop pump (also intercepts keyboard escape commands)
            if cv2.waitKey(1) & 0xFF == 27:
                print("[*] Stream presentation closed by user action.")
                break

    except Exception as e:
        print(f"[!] Display Engine Error: {e}")
    finally:
        running = False
        cv2.destroyAllWindows()
        print("[*] Display Engine Safely Closed.")

# --- MAIN EXECUTION APPLICATION INTERFACE ---
async def main():
    global running
    try:
        ssid, password, ip = await extract_gatt_credentials()
        join_wifi(ssid, password)
        
        # Configure tightly restricted thread queues to enforce low latency bounds
        packet_queue = queue.Queue(maxsize=PACKET_QUEUE_MAXSIZE)
        frame_queue = queue.Queue(maxsize=FRAME_QUEUE_MAXSIZE)
        
        # Build independent thread loops
        receiver_t = threading.Thread(target=tcp_receiver_thread, args=(ip, packet_queue), name="ReceiverThread")
        decoder_t = threading.Thread(target=packet_decoder_thread, args=(packet_queue, frame_queue), name="DecoderThread")
        
        receiver_t.start()
        decoder_t.start()
        
        # The main thread locks into running the UI/Display Engine (Required for UI stability on certain OS environments)
        display_engine(frame_queue)
        
        # Synchronize clean pipeline shutdown
        running = False
        receiver_t.join(timeout=1.0)
        decoder_t.join(timeout=1.0)
        
    except Exception as e:
        print(f"Main Application Execution Fault: {e}")
        running = False

if __name__ == "__main__":
    asyncio.run(main())
