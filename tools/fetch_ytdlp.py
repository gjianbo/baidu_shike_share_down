#!/usr/bin/env python3
"""Fetch the pinned yt-dlp zipapp from its official GitHub release."""

import hashlib
from pathlib import Path
import sys
from urllib.request import urlopen
from zipfile import ZipFile

version, expected, destination = sys.argv[1:]
output = Path(destination)
if output.is_file() and hashlib.sha256(output.read_bytes()).hexdigest() == expected:
    sys.exit(0)

url = f"https://github.com/yt-dlp/yt-dlp/releases/download/{version}/yt-dlp"
with urlopen(url, timeout=45) as response:
    data = response.read()
if hashlib.sha256(data).hexdigest() != expected:
    raise ValueError("yt-dlp release SHA-256 mismatch")
output.parent.mkdir(parents=True, exist_ok=True)
temporary = output.with_suffix(".tmp")
try:
    temporary.write_bytes(data)
    with ZipFile(temporary) as bundle:
        bundle.getinfo("yt_dlp/extractor/xiaohongshu.py")
        bundle.getinfo("yt_dlp_ejs/yt/solver/core.min.js")
    temporary.replace(output)
finally:
    temporary.unlink(missing_ok=True)
