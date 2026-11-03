#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
mkdir -p app/libs
core_tmp=$(mktemp "app/libs/core.XXXXXX")
trap 'rm -f "$core_tmp"' EXIT HUP INT TERM
curl --fail --location --proto '=https' --tlsv1.2 \
  'https://github.com/2dust/AndroidLibXrayLite/releases/download/v26.10.31/libv2ray.aar' \
  --output "$core_tmp"
core_hash=$(shasum -a 256 "$core_tmp" | cut -d ' ' -f 1)
if [ "$core_hash" != '2efdf731b1ed624eb1cb0d5a4d0aa95c0672e89f9702226d1ba2dc90308630b6' ]; then
  echo 'Xray library checksum mismatch' >&2
  exit 1
fi
mv "$core_tmp" app/libs/libv2ray.aar
