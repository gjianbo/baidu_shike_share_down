#!/usr/bin/env python3
"""Rebuild FFmpeg's bundled WebP libraries and check all ARM64 ELF alignments."""

import argparse
import hashlib
import io
from pathlib import Path
import struct
import subprocess
import tarfile
import urllib.request
import zipfile

WEBP_VERSION = "1.6.0"
WEBP_SHA256 = "e4ab7009bf0629fd11982d4c2aa83964cf244cffba7347ecd39019a9e38c4564"
WEBP_LIBS = ("libwebp.so", "libwebpdecoder.so", "libwebpdemux.so", "libwebpmux.so")
FFMPEG_ARCHIVE = "libffmpeg.zip.so"
PAGE_SIZE = 16384


def check_elf(name, data):
    if data[:4] != b"\x7fELF":
        return
    if data[4:6] != b"\x02\x01" or struct.unpack_from("<H", data, 18)[0] != 183:
        raise ValueError(f"{name}: expected a little-endian ARM64 ELF file")
    offset = struct.unpack_from("<Q", data, 32)[0]
    size, count = struct.unpack_from("<HH", data, 54)
    for index in range(count):
        header = offset + index * size
        kind, _, file_offset, vaddr, _, _, _, alignment = struct.unpack_from("<II6Q", data, header)
        if kind == 1 and (alignment < PAGE_SIZE or (vaddr - file_offset) % PAGE_SIZE):
            raise ValueError(f"{name}: invalid 16 KB PT_LOAD alignment ({alignment})")


def require_elf(archive, name):
    data = archive.read(name)
    if not data.startswith(b"\x7fELF"):
        raise ValueError(f"{name}: missing native ELF data")


def check_archive(archive, prefix):
    for executable in ("libffmpeg.so", "libffprobe.so"):
        require_elf(archive, prefix + executable)
    archive.getinfo(prefix + FFMPEG_ARCHIVE)
    for entry in archive.infolist():
        if entry.is_dir() or not entry.filename.startswith(prefix):
            continue
        data = archive.read(entry)
        check_elf(entry.filename, data)
        if entry.filename.endswith(".zip.so"):
            with zipfile.ZipFile(io.BytesIO(data)) as nested:
                if entry.filename == prefix + FFMPEG_ARCHIVE:
                    for library in (*WEBP_LIBS, "libsharpyuv.so"):
                        require_elf(nested, "usr/lib/" + library)
                for member in nested.infolist():
                    if not member.is_dir():
                        check_elf(f"{entry.filename}/{member.filename}", nested.read(member))


def replace_webp(data, build):
    output = io.BytesIO()
    replaced = set()
    with zipfile.ZipFile(io.BytesIO(data)) as original, zipfile.ZipFile(output, "w") as revised:
        for entry in original.infolist():
            name = Path(entry.filename).name
            if name in WEBP_LIBS:
                revised.writestr(entry, (build / name).read_bytes())
                replaced.add(name)
            elif entry.filename != "usr/lib/libsharpyuv.so":
                revised.writestr(entry, original.read(entry))
        # libwebp 1.6.0 builds SharpYUV as a separate library in shared builds.
        revised.write(build / "libsharpyuv.so", "usr/lib/libsharpyuv.so", zipfile.ZIP_DEFLATED)
    if replaced != set(WEBP_LIBS):
        raise ValueError(f"FFmpeg archive is missing expected WebP libraries: {set(WEBP_LIBS) - replaced}")
    return output.getvalue()


def add_webp_notices(data, source):
    output = io.BytesIO()
    with zipfile.ZipFile(io.BytesIO(data)) as original, zipfile.ZipFile(output, "w") as revised:
        for entry in original.infolist():
            revised.writestr(entry, original.read(entry))
        for name in ("COPYING", "PATENTS"):
            revised.write(source / name, f"META-INF/libwebp/{name}.txt")
    return output.getvalue()


def build_aar(source_aar, output, work, ndk):
    work.mkdir(parents=True, exist_ok=True)
    name = f"libwebp-{WEBP_VERSION}"
    source_tar = work / f"{name}.tar.gz"
    if not source_tar.is_file():
        url = f"https://storage.googleapis.com/downloads.webmproject.org/releases/webp/{name}.tar.gz"
        with urllib.request.urlopen(url, timeout=30) as response:
            data = response.read()
        if hashlib.sha256(data).hexdigest() != WEBP_SHA256:
            raise ValueError("WebP source archive SHA-256 mismatch")
        source_tar.write_bytes(data)
    if hashlib.sha256(source_tar.read_bytes()).hexdigest() != WEBP_SHA256:
        raise ValueError("Cached WebP source archive SHA-256 mismatch")
    with tarfile.open(source_tar) as archive:
        archive.extractall(work, filter="data")

    build = work / "webp-build"
    subprocess.run([
        "cmake", "-G", "Ninja", "-S", str(work / name), "-B", str(build),
        f"-DCMAKE_TOOLCHAIN_FILE={ndk / 'build/cmake/android.toolchain.cmake'}",
        "-DANDROID_ABI=arm64-v8a", "-DANDROID_PLATFORM=android-29", "-DCMAKE_BUILD_TYPE=Release",
        "-DBUILD_SHARED_LIBS=ON", "-DCMAKE_SHARED_LINKER_FLAGS=-Wl,-s,-z,max-page-size=16384",
        *[f"-DWEBP_BUILD_{target}=OFF" for target in
          ("CWEBP", "DWEBP", "GIF2WEBP", "IMG2WEBP", "VWEBP", "WEBPINFO", "WEBPMUX", "EXTRAS")],
    ], check=True)
    subprocess.run([
        "cmake", "--build", str(build), "--target", "webp", "webpdecoder", "webpdemux", "libwebpmux",
    ], check=True)

    output.parent.mkdir(parents=True, exist_ok=True)
    staged = output.with_suffix(".tmp")
    try:
        with zipfile.ZipFile(source_aar) as original, zipfile.ZipFile(staged, "w") as revised:
            target = f"jni/arm64-v8a/{FFMPEG_ARCHIVE}"
            if target not in original.namelist():
                raise ValueError(f"FFmpeg AAR is missing {target}")
            for entry in original.infolist():
                data = original.read(entry)
                if entry.filename == target:
                    data = replace_webp(data, build)
                elif entry.filename == "classes.jar":
                    data = add_webp_notices(data, work / name)
                revised.writestr(entry, data)
        with zipfile.ZipFile(staged) as revised:
            check_archive(revised, "jni/arm64-v8a/")
        staged.replace(output)
    finally:
        staged.unlink(missing_ok=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    build = commands.add_parser("build")
    for param in ("source", "output", "work", "ndk"):
        build.add_argument(param, type=Path)
    verify = commands.add_parser("verify")
    verify.add_argument("apk", type=Path)
    args = parser.parse_args()
    if args.command == "build":
        build_aar(args.source, args.output, args.work, args.ndk)
    else:
        with zipfile.ZipFile(args.apk) as apk:
            check_archive(apk, "lib/arm64-v8a/")
        print("ARM64 APK and nested native libraries support 16 KB ELF alignment")


if __name__ == "__main__":
    main()
