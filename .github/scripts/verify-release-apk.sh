#!/usr/bin/env bash
set -euo pipefail

apk=${1:?Pass the APK path}
tag=${2:?Pass the release tag}
tools="${ANDROID_HOME:?Missing Android SDK}/build-tools/35.0.0"
# Certificate of the published 拾课 main APK. Both applications must have this signer.
expected_cert=75ec2ca0dfe78311b851b0ec4f91362011c0857b6e9d13cb9843e50f35496055
certificates=$("$tools/apksigner" verify --print-certs "$apk")
actual_cert=$(sed -nE 's/^Signer #[0-9]+ certificate SHA-256 digest: ([[:xdigit:]]+)$/\1/p' <<< "$certificates")
if [[ "$actual_cert" != "$expected_cert" ]]; then
    echo "The extension APK must use the published main application's signing certificate." >&2
    exit 1
fi

badging=$("$tools/aapt2" dump badging "$apk")
grep -F "package: name='com.hnwd.bdplayer_ext_share'" <<< "$badging"
grep -F "versionName='${tag#v}'" <<< "$badging" >/dev/null
grep -Fx "native-code: 'arm64-v8a'" <<< "$badging"
