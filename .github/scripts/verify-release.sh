#!/usr/bin/env bash
set -euo pipefail

tag=${1:?Pass the release tag}
expected_commit=${2:?Pass the source commit}

if [[ ! "$tag" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]] ||
   [[ "$(git rev-parse --verify "refs/tags/${tag}^{commit}")" != "$expected_commit" ]] ||
   [[ "$(git rev-parse --verify HEAD)" != "$expected_commit" ]]; then
    echo "The release tag must be vX.Y.Z and identify the checked-out source." >&2
    exit 1
fi

version_code() {
    sed -nE "s/^[[:space:]]*versionCode[[:space:]]+([1-9][0-9]*)[[:space:]]*$/\1/p"
}
version=$(sed -nE "s/^[[:space:]]*versionName[[:space:]]+'([0-9]+\.[0-9]+\.[0-9]+)'[[:space:]]*$/\1/p" app/build.gradle)
code=$(version_code < app/build.gradle)
[[ "v$version" == "$tag" && "$code" =~ ^[1-9][0-9]*$ ]] || {
    echo "The release tag must match versionName and versionCode must be a positive integer." >&2
    exit 1
}

# Compare both sides so publishing tags out of order cannot reverse Android's upgrade order.
tags=$(git tag -l 'v[0-9]*' --sort=version:refname)
seen_current=false
while IFS= read -r other; do
    [[ "$other" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]] || continue
    if [[ "$other" == "$tag" ]]; then
        seen_current=true
        continue
    fi
    other_code=$(git show "$other:app/build.gradle" | version_code)
    if [[ ! "$other_code" =~ ^[1-9][0-9]*$ ]]; then
        echo "Invalid versionCode in $other." >&2
        exit 1
    fi
    if $seen_current; then
        from=$tag to=$other previous_code=$code next_code=$other_code
    else
        from=$other to=$tag previous_code=$other_code next_code=$code
    fi
    if (( next_code <= previous_code )); then
        echo "versionCode must increase from $from to $to." >&2
        exit 1
    fi
done <<< "$tags"
