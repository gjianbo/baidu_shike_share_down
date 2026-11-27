#!/usr/bin/env bash
set -euo pipefail

tag=${1:?Pass the release tag (vX.Y.Z)}
shift
if [[ ! "$tag" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    echo "Invalid extension release tag: $tag" >&2
    exit 1
fi
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
cd -- "${EXTENSION_PROJECT_DIR:-$script_dir/../..}"

if [[ "${GITHUB_ACTIONS:-}" == true ]]; then
    : "${KEYSTORE_BASE64:?Configure BDPLAYER_KEYSTORE_BASE64}"
    : "${BDPLAYER_STORE_PASSWORD:?Configure BDPLAYER_STORE_PASSWORD}"
    : "${BDPLAYER_KEY_ALIAS:?Configure BDPLAYER_KEY_ALIAS}"
    : "${BDPLAYER_KEY_PASSWORD:?Configure BDPLAYER_KEY_PASSWORD}"

    BDPLAYER_KEYSTORE="$(mktemp "${RUNNER_TEMP:?}/baidu-shike-extension.XXXXXX")"
    export BDPLAYER_KEYSTORE
    trap 'rm -f -- "$BDPLAYER_KEYSTORE"' EXIT
    printf '%s' "$KEYSTORE_BASE64" | base64 --decode > "$BDPLAYER_KEYSTORE"
    unset KEYSTORE_BASE64
fi

gradle --no-daemon "$@" :app:assembleRelease

release_dir=app/build/outputs/apk/release
output_file="$(jq -er '.elements | select(length == 1) | .[0].outputFile' "$release_dir/output-metadata.json")"
if [[ "$output_file" != app-release.apk ]]; then
    echo "Expected a signed release APK. Check the extension's signing configuration." >&2
    exit 1
fi
apk="$release_dir/$output_file"
bash "$script_dir/verify-release-apk.sh" "$apk" "$tag"

mkdir -p build/releases
output=build/releases/baidu-shike-share-download-arm64.apk
cp -- "$apk" "$output"
echo "APK: $output"
