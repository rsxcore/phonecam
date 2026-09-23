"""Connects to the phone (protocol v2), records the raw video and reports timing.

Usage: python tests/probe.py HOST[:PORT] SECONDS [out.h264] [--set '{"fps":60}']
Use `adb forward tcp:8080 tcp:8080` and HOST=127.0.0.1 for USB.
"""
import json
import socket
import struct
import sys
import time


def read_exact(s, n):
    buf = bytearray()
    while len(buf) < n:
        chunk = s.recv(n - len(buf))
        if not chunk:
            raise EOFError
        buf += chunk
    return bytes(buf)


def main():
    host, _, port = sys.argv[1].partition(":")
    seconds = float(sys.argv[2])
    out_path = sys.argv[3] if len(sys.argv) > 3 and not sys.argv[3].startswith("--") else None
    control = None
    if "--set" in sys.argv:
        control = json.loads(sys.argv[sys.argv.index("--set") + 1])

    s = socket.create_connection((host, int(port or 8080)), timeout=5)
    s.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
    magic, version, _ = struct.unpack("<4sHH", read_exact(s, 8))
    assert magic == b"PCAM", magic
    print(f"protocol v{version}")
    if control:
        payload = json.dumps({"set": control}).encode()
        s.sendall(struct.pack("<BI", 0x03, len(payload)) + payload)

    out = open(out_path, "wb") if out_path else None
    arrivals, pts, sizes, keys = [], [], [], 0
    start = time.perf_counter()
    last_state = None
    while time.perf_counter() - start < seconds:
        mtype, length = struct.unpack("<BI", read_exact(s, 5))
        payload = read_exact(s, length)
        now = time.perf_counter()
        if mtype == 0x01:
            hello = json.loads(payload)
            print("HELLO", hello["device"], [l["name"] for l in hello["lenses"]])
        elif mtype == 0x02:
            last_state = json.loads(payload)
        elif mtype == 0x10:
            codec, w, h, fps = struct.unpack("<BHHH", payload[:7])
            print(f"CONFIG codec={codec} {w}x{h}@{fps} csd={length - 7}B")
            arrivals, pts, sizes = [], [], []
            if out:
                out.write(payload[7:])
        elif mtype == 0x11:
            flags, p, rot = struct.unpack("<BqH", payload[:11])
            arrivals.append(now)
            pts.append(p)
            sizes.append(length - 11)
            keys += flags & 1
            if out:
                out.write(payload[11:])
    s.close()

    if len(pts) < 3:
        print("too few frames", len(pts))
        return
    span = (pts[-1] - pts[0]) / 1e6
    fps = (len(pts) - 1) / span
    deltas = [(b - a) / 1000 for a, b in zip(pts, pts[1:])]
    gaps = [(b - a) * 1000 for a, b in zip(arrivals, arrivals[1:])]
    nominal = 1000 / fps
    late = sum(1 for d in deltas if d > nominal * 1.5)
    print(f"frames={len(pts)} key={keys} fps(pts)={fps:.2f} rotation={rot}")
    print(f"pts delta ms: min={min(deltas):.1f} max={max(deltas):.1f} skips(>1.5x)={late}")
    print(f"arrival gap ms: p50={sorted(gaps)[len(gaps)//2]:.1f} p99={sorted(gaps)[int(len(gaps)*0.99)]:.1f} max={max(gaps):.1f}")
    print(f"bitrate={sum(sizes) * 8 / span / 1e6:.1f} Mbps, avg frame {sum(sizes)/len(sizes)/1024:.0f} KB")
    if last_state:
        print("state:", json.dumps({k: last_state[k] for k in ("sentFps", "kbps", "dropped", "encoder")}), last_state["live"])


if __name__ == "__main__":
    main()
