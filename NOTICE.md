# Native dependency

RR VPN uses the unmodified AndroidLibXrayLite v26.10.31 Android archive.

- Source: https://github.com/2dust/AndroidLibXrayLite/tree/2ffe5f794a32ba15b122e177b5acffc6f11c4959
- License: LGPL-3.0; full texts in `licenses/`.
- Native engine: Xray-core commit `a4a3dff5b20c`, MPL-2.0.
- Engine source: https://github.com/XTLS/Xray-core/tree/a4a3dff5b20c
- Transitive native dependencies and versions: the pinned wrapper's `go.mod` and `go.sum`.
- Archive SHA-256: `2efdf731b1ed624eb1cb0d5a4d0aa95c0672e89f9702226d1ba2dc90308630b6`.

The complete application source and Gradle wrapper are supplied. To relink with a modified
compatible library, replace `app/libs/libv2ray.aar` and rebuild. There is no proprietary
signing or installation restriction: a locally generated Android debug key can sign your build.
Use the wrapper repository's build workflow for rebuilding the native archive from source.

This app is a separate implementation using the library's public Java API. The v2rayNG
repository was consulted as integration documentation; its application source is not copied
into this application. No HEV library is used: Xray's native TUN inbound handles packets.
