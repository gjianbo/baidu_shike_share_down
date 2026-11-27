#!/usr/bin/env bash
set -euo pipefail

: "${GH_REPO:?Missing GitHub repository}"
: "${RELEASE_TAG:?Missing release tag}"
: "${BUILD_TAG_OBJECT:?Missing build tag object}"
[[ "$RELEASE_TAG" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]]
apk=dist/baidu-shike-share-download-arm64.apk
test -s "$apk"

verify_tag() {
    local remote
    remote="$(gh api "repos/$GH_REPO/git/ref/tags/$RELEASE_TAG" --jq '.object.sha')" || return 1
    if [[ "$remote" != "$BUILD_TAG_OBJECT" ]]; then
        printf 'Remote tag %s no longer identifies the build source.\n' "$RELEASE_TAG" >&2
        return 1
    fi
}

# Status 4 is reserved for a confirmed 404. Other lookup failures must stop publication.
lookup_release() {
    local response status_line status gh_status=0
    response="$(gh api --include "repos/$GH_REPO/releases/tags/$RELEASE_TAG")" || gh_status=$?
    response="${response//$'\r'/}"
    status_line="${response%%$'\n'*}"
    if [[ "$status_line" =~ ^HTTP/[^[:space:]]+[[:space:]]+([0-9]{3})([[:space:]]|$) ]]; then
        status="${BASH_REMATCH[1]}"
        if [[ "$status" == 404 ]]; then
            return 4
        fi
        if [[ "$status" == 200 && "$gh_status" == 0 && "$response" == *$'\n\n'* ]]; then
            printf '%s\n' "${response#*$'\n\n'}"
            return 0
        fi
    fi
    printf 'GitHub Release lookup failed for %s (HTTP %s).\n' "$RELEASE_TAG" "${status:-unknown}" >&2
    return 1
}

if release="$(lookup_release)"; then
    jq -e '.draft == false and .prerelease == false and (.assets | type == "array")' <<< "$release" >/dev/null
    asset_state="$(jq -r --arg name "${apk##*/}" '
        first(.assets[] | select(.name == $name) |
            if .state == "uploaded" and .size > 0 then "uploaded" else "incomplete" end) // "missing"
    ' <<< "$release")"
    verify_tag
    case "$asset_state" in
        uploaded) printf 'Release %s already has %s.\n' "$RELEASE_TAG" "${apk##*/}" ;;
        missing) gh release upload "$RELEASE_TAG" "$apk" ;;
        *)
            printf 'Release %s has an incomplete %s attachment. Remove it before retrying.\n' "$RELEASE_TAG" "${apk##*/}" >&2
            exit 1 ;;
    esac
elif [[ "$?" == 4 ]]; then
    verify_tag
    gh release create "$RELEASE_TAG" "$apk" --verify-tag --generate-notes
else
    exit 1
fi
