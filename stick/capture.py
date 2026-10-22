"""Listens to the voice stick over USB: prints its log lines and saves each recording it sends as a WAV.

    ~/.espressif/python_env/idf5.4_py3.14_env/bin/python capture.py [seconds] [folder]
"""
import base64
import re
import sys
import time
import wave

import serial

seconds = float(sys.argv[1]) if len(sys.argv) > 1 else 120
folder = sys.argv[2] if len(sys.argv) > 2 else "."
port = serial.Serial("/dev/cu.usbmodem101", 115200, timeout=0.5)
end, pending, chunks, count = time.time() + seconds, b"", None, 0
while time.time() < end:
    pending += port.read(8192)
    *lines, pending = pending.split(b"\n")
    for raw in lines:
        line = re.sub(r"\x1b\[[0-9;]*m", "", raw.decode("utf-8", "replace")).strip()
        if line.startswith("AUDIO-BEGIN"):
            chunks = []
        elif line.startswith("A:") and chunks is not None:
            try:
                chunks.append(base64.b64decode(line[2:]))
            except ValueError:   # a line mangled on the way: drop this recording
                chunks = None
        elif line.startswith("AUDIO-END") and chunks is not None:
            count += 1
            path = f"{folder}/try{count}.wav"
            with wave.open(path, "wb") as w:
                w.setnchannels(1)
                w.setsampwidth(2)
                w.setframerate(16000)
                w.writeframes(b"".join(chunks))
            print(f"saved {path}", flush=True)
            chunks = None
        elif re.search(r"stick|kasa|E \(|W \(|Guru|abort", line):
            print(line[:200], flush=True)
port.close()
