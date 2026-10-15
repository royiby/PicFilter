#!/usr/bin/env bash
# Fetches MobileFaceNet.tflite and verifies it, then installs it where the
# Android build expects it. The binary is deliberately not committed — see
# models/PROVENANCE.md for the licensing reasoning.
set -euo pipefail

URL="https://raw.githubusercontent.com/syaringan357/Android-MobileFaceNet-MTCNN-FaceAntiSpoofing/master/app/src/main/assets/MobileFaceNet.tflite"
SHA256="d8ba40c0127fb8ca9917e8fddc79bbbda063657bc92a496d34da0bc8a760443b"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST="$ROOT/models/MobileFaceNet.tflite"
ASSETS="$ROOT/app/src/main/assets/MobileFaceNet.tflite"

mkdir -p "$ROOT/models" "$ROOT/app/src/main/assets"
echo "fetching MobileFaceNet.tflite ..."
curl -fsSL "$URL" -o "$DEST"

got="$(shasum -a 256 "$DEST" | awk '{print $1}')"
if [ "$got" != "$SHA256" ]; then
  echo "SHA-256 MISMATCH" >&2
  echo "  expected: $SHA256" >&2
  echo "  got:      $got" >&2
  rm -f "$DEST"
  exit 1
fi

# a .tflite flatbuffer carries the identifier "TFL3" at offset 4
if [ "$(dd if="$DEST" bs=1 skip=4 count=4 2>/dev/null)" != "TFL3" ]; then
  echo "not a TFLite flatbuffer (missing TFL3 identifier)" >&2
  rm -f "$DEST"; exit 1
fi

cp "$DEST" "$ASSETS"
echo "ok: $(wc -c < "$DEST") bytes, sha256 verified, installed to app/src/main/assets/"
