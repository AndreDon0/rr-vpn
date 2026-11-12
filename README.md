# RR VPN for Android

RR VPN 0.8.2 combines servers from multiple VLESS subscriptions into one shared Xray
round-robin VPN pool. The native Android UI has neutral surfaces, a connection button that is gray when off, blue while connecting and green when on,
clear status, aggregate traffic cards, and a scrolling server list. Settings contain subscriptions,
Smart IP routing with an info button, a language picker, and a replayable quick guide.

## Automatic updates

While RR VPN is open, it checks the public
[`updates/stable.json`](https://raw.githubusercontent.com/AndreDon0/rr-vpn/main/updates/stable.json)
feed and downloads newer versions without a blocking dialog. A download arrow beside Settings
has a determinate progress ring. Once the APK is verified, the full ring stays still until you
choose to update. Tapping it opens a rounded sheet containing only the new version and localized
release notes, with Later and Update controls. Settings → Updates can also check for a version.

**Download updates only over Wi-Fi** defaults to on and persists. This checks the actual
Wi-Fi transport, including metered Wi-Fi; unmetered cellular does not qualify. Turning it off
allows other connected networks, including cellular. APK connections bind to the selected
network. Transfers pause when the app is backgrounded or an allowed network disappears, and
resume from saved bytes when the app/network returns if the server supports HTTP ranges.
Rotation and language changes share the same process-owned transfer. A cleared cache simply
requires downloading again. Small metadata checks may use any available connection.

There are no scheduled update downloads or update notifications. The old 0.5.0 job and
notice are canceled on startup. Checks run on foreground entry and while the app remains open,
with a five-minute metadata cache. Downloads, hashing and package checks stay off the UI thread.

Only HTTPS URLs in this repository's versioned Releases are accepted. Downloads must match the
feed size/SHA-256, package name, version code/name, minimum Android version and installed signer.
Partials are keyed by the expected hash; HTTP ranges are validated before appending. Completed
APKs are verified again before handing one read-only URI to Android's installer. No GitHub token,
subscription key, silent installer, device administrator or accessibility service is involved.
Installation still uses Android's own approval flow; the app's release-note sheet contains no
scheduling or installer explanation. Updating stops the VPN; reconnect afterwards.

## Install and use

Install over the previous Drift Pool build to retain your subscription and routing settings.
The Android package and signing key are unchanged; the launcher/notification name is RR VPN.

- [Latest universal APK](https://github.com/AndreDon0/rr-vpn/releases/latest): one compressed package for all four supported CPU architectures. Only the latest APK is retained in Releases; APKs are excluded from Git history.
- Android 8.0 or newer is required. These are locally signed development APKs, not store releases.

1. Open **RR VPN**. With no subscriptions saved, **Add subscription** opens the link field
   from the first guide page or from Home. You can also open Settings and tap **Add**.
2. Paste HTTPS VLESS subscription URLs, **one link per line**, and tap **Add**.
3. Return to Home and tap the large power button. Approve Android's VPN permission prompt.
4. Every unique server from all saved subscriptions enters the same pool. No provider selection
   is needed. Adding an already saved URL updates that source instead of duplicating it.
5. In settings, **Refresh** updates every source; **×** beside a source removes only that
   source. Servers also present in another source stay in the pool. Disconnect before changing
   subscriptions. The Smart IP routing switch can be changed while connected.

Choose **Language → Follow system / English / Русский** in Settings. On first use the app
uses the first English or Russian entry in the phone's ordered language list, falling back to
English when neither is present. An explicit choice persists across restarts. Android 13 and
newer also expose these choices in Android's per-app language settings; those settings and the
in-app picker stay synchronized. Existing language selections migrate automatically.
Changing language recreates the UI without restarting the VPN or modifying subscriptions.
The running VPN notification updates when the app or system language changes.
The routing info button explains local-IP priority, Antifilter precedence, direct Russian ranges, the off mode,
and automatic list refreshes; there is no manual routing-list refresh button.

A failed source download or parse keeps its preceding saved servers. Successful sources from
that refresh still update. The final subscription collection is encrypted and saved atomically.
The old single-subscription encrypted format is read automatically; adding/updating sources
writes the new multi-source format without losing the original source.

Subscription URLs are hidden after input, encrypted on the device with Android Keystore,
excluded from backups, and absent from the delivered APK/source. Settings show only the source
hostname and server count. Uninstalling the app removes its saved credentials.

Limits: 20 subscriptions, 128 servers per source, 1,024 unique servers in the combined pool,
and 2 MiB per downloaded source. Oversized/unsupported sources fail without silently truncating.
Plaintext or Base64 VLESS lists with TCP/raw, Reality/TLS, Vision, gRPC and WebSocket are supported.
RR VPN also imports TCP REALITY outbounds with fragmentation from DriftVPN's Xray profiles.
To replace a previously saved DriftVPN list with those profiles, disconnect and tap **Refresh**
under **Settings → Subscriptions**.
Other protocols/transports, unencrypted connections and insecure certificate options are rejected.

### Play Protect scan prompt

The **App scan recommended** prompt means Google Play Protect has not scanned this APK before;
it is not itself a malware finding. Choose **Scan app** (扫描应用 in the supplied Chinese screenshot)
and follow the scan result. RR VPN cannot suppress this system-owned prompt. Keeping the same
signing certificate supports normal upgrades but does not guarantee that Play Protect will stop
requesting scans. See [Google's warning guidance](https://developers.google.com/android/play-protect/warning-dev-guidance).

## Subscription traffic limits

Settings show provider-reported usage and limits separately for each subscription:
used upload + download bytes, total allowance, a usage bar for finite limits, and an expiry
date when supplied. These are provider account totals, which may include other devices;
they are independent of RR VPN's connection-session Download/Upload counters.

A reported `total=0` means **Unlimited**. Missing or invalid limits display **Limit unavailable**;
missing usage is never shown as zero. Over-limit bars stop at full while the actual used count
remains visible. Expiry and exceeded-limit displays are informational and do not change routing.

The app reads `Subscription-Userinfo` response headers, or `#subscription-userinfo:` comment
lines in plain-text/Base64 subscription bodies. Usage metadata is encrypted with the saved
subscription. Older saved subscriptions remain readable without reimporting.

Add/Refresh collects the latest figures with the server list. While Settings is open, stale
figures refresh about every 15 minutes without restarting the VPN or changing its servers.
Refresh tries HEAD first and uses a bounded GET when HEAD is unavailable or omits metadata.
Failed requests retain the previous values; older snapshots display their last update time.
Refreshes are capped at four concurrent providers and do not run a new background job.
A canceled refresh can retry after reopening the app.

Provider format references: [Happ subscription status](https://github.com/HappDev/happ_su/blob/main/dev-docs/app-management.md)
and [s-ui subscription headers](https://github.com/alireza0/s-ui/wiki/Subscription-Service).

## Connection behavior

Android permits one active VPN per profile. RR VPN owns that interface and routes each new
TCP/UDP connection through the next available server. Multiple connections can use different
servers simultaneously. One connection stays with one server: this is not bandwidth bonding
or packet duplication, and interrupted connections cannot migrate between servers.

Xray probes all servers concurrently through HTTPS to `https://www.gstatic.com/generate_204`,
waiting 60 seconds between completed batches to reduce idle network activity. The first batch
runs at startup. A probe can take about five seconds. Unknown servers are initially considered
available. Server failure and recovery can take about a minute to detect.
The app and notification report **Connected** after an HTTPS request succeeds through the
active pool. RR VPN repeats this check while the tunnel is active and shows **VPN connection
unavailable** after a failed check. The request verifies reachability to the probe destination.

Traffic cards and rows show cumulative download/upload since connection startup, including
health checks. The app reads the counters while visible and before a routing reload. A light
service check runs about once a minute while connected.
Provider-supplied names/flag emoji remain dynamic.

## Smart IP routing and automatic list updates

**Smart per-app routing** is on by default. Android sends matching apps directly before
Xray sees their traffic. The first match wins.

1. The installed app label contains a letter from the Russian alphabet.
2. Android reports RuStore (`ru.vk.store`) as the installer.
3. The package is in the built-in list of Google Play publishers with a Russian address.
4. All other apps follow the IP rules below.

The publisher list is based on the public Play listings for [2GIS](https://play.google.com/store/apps/details?id=ru.dublgis.dgismobile),
[2GIS beta](https://play.google.com/store/apps/details?id=ru.dublgis.dgismobile4preview),
[Cian](https://play.google.com/store/apps/details?id=ru.cian.main),
[HeadHunter](https://play.google.com/store/apps/details?id=ru.hh.android),
[Ozon](https://play.google.com/store/apps/details?id=ru.ozon.app.android), and
[Wildberries](https://play.google.com/store/apps/details?id=com.wildberries.ru).
It covers these packages only. RR VPN reads app information locally and sends no app list
to a server. Reconnect after installing apps to scan again. Changing the switch while
connected briefly restarts connections. Android's VPN lockdown blocks excluded apps, and
packages that share a UID with an unmatched app follow the IP rules together.

Smart IP routing is **on by default** when no choice has been saved. A saved off/on choice is
preserved across app updates.

Switch **off**: local/private destinations connect directly; all other destinations use the VPN pool.

Switch **on**, first matching rule wins:

1. Local/private IPv4/IPv6 destinations → **direct**.
2. Antifilter IPs → **VPN pool**.
3. Other Russian IPv4/IPv6 ranges → **direct**.
4. All remaining IPs → **VPN pool**.

Local destinations always win, even if a downloaded list contains them. For public IPs,
Antifilter still overrides the RU list. Direct destinations remain direct if every VPN server
is unavailable; VPN-routed traffic uses the pool's blackhole fallback.

The fixed local rule covers private IPv4 LANs, shared carrier space, loopback, link-local,
multicast and limited broadcast, plus IPv6 unspecified/loopback, unique-local, link-local and
multicast ranges. It does not depend on downloads or the smart-routing switch:

```text
0.0.0.0/8       10.0.0.0/8        100.64.0.0/10   127.0.0.0/8
169.254.0.0/16  172.16.0.0/12     192.168.0.0/16  224.0.0.0/4
255.255.255.255/32
::/128         ::1/128           fc00::/7        fe80::/10     ff00::/8
```

These explicit prefixes avoid broadly bypassing other special-purpose/public address space.
Local classification follows the [IANA IPv4 registry](https://www.iana.org/assignments/iana-ipv4-special-registry/)
and [IANA IPv6 registry](https://www.iana.org/assignments/iana-ipv6-special-registry/).
This is an Xray destination-IP routing rule; it does not change Android's VPN interface or
add local-network discovery. On-link devices with globally assigned IPs are still subject to
the public-IP rules.

Sources:

- [IPdeny RU IPv4](https://www.ipdeny.com/ipblocks/data/aggregated/ru-aggregated.zone)
- [IPdeny RU IPv6](https://www.ipdeny.com/ipv6/ipaddresses/aggregated/ru-aggregated.zone)
- [Antifilter allyouneed.lst](https://antifilter.download/list/allyouneed.lst)

The bundled snapshot fetched on 2026-11-03 contains 8,664 RU IPv4, 2,167 RU IPv6 and
18,088 Antifilter ranges. While smart routing is enabled, Android JobScheduler refreshes
the three lists about every 12 hours when network/battery conditions permit, with a persisted
job and a stale-cache catch-up job. There is **no routing-list refresh button**.

Every response must validate before one atomic cache replacement. Bad/empty/HTML responses,
domains, invalid CIDRs and `/0` routes are rejected. Failures retain the complete preceding
snapshot. Disabling Smart IP routing cancels its pending update jobs. Feed downloads use the
VPN app's excluded underlying network; an inaccessible feed leaves the old lists active.

Changed ranges or a switch change automatically reload Xray while retaining Android's VPN
interface. Existing connections may briefly restart. Identical refreshed ranges update the
check time without a reload. For blocking if Android stops the app, enable **Always-on VPN**
and **Block connections without VPN** in Android's VPN settings.

Both IPv4 and IPv6 enter the TUN. DNS to 1.1.1.1/1.0.0.1 follows routing rules through the pool.
The VPN app itself is excluded to prevent proxy/bootstrap sockets looping through their TUN.
Different exit IPs can affect sites that expect one IP per login. Provider session limits and
battery/traffic costs of probing many servers still apply.

## Publishing a new version

Keep the original signing keystore private and backed up. Rebuilding on a new computer with
a different debug key will produce an APK that installed copies correctly reject. No keystore
or GitHub token belongs in this repository or release assets. The first public releases retain
the original development signing identity for compatibility; this is not a Play Store release.

Increase `versionCode` and `versionName` in `app/build.gradle.kts`, run the checks, build the
universal APK (no `targetAbis` override), and commit/push source to main. With GitHub CLI
installed/authenticated and `ANDROID_HOME` pointing at the SDK:

```sh
sh ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
cp app/build/outputs/apk/debug/app-debug.apk outputs/RR-VPN-VERSION-universal.apk
python3 scripts/publish-release.py outputs/RR-VPN-VERSION-universal.apk --notes-file /path/to/release-notes-en.md --notes-ru-file /path/to/release-notes-ru.md
```

The publisher verifies identity/ABIs, uploads a draft APK and checksum, publishes the release,
then commits the update feed with English and Russian change notes. Only after that does it remove APK/checksum assets from older
releases. Source tags/notes remain. This keeps only **one universal APK** hosted; binaries and
native AARs are never committed. CI runs unit/lint/build checks without uploading APK artifacts
or signing keys. Release creation/uploads require owner GitHub authentication locally.

## APK compression

Native libraries are ZIP-compressed in the universal release APK. All four CPU engines are included. Compression reduces APK transfer size; Android extracts the native library
on installation, so installed storage is larger than the compressed file. The native engine
and its capabilities are unchanged. GeoIP/GeoSite assets remain included in this release.

## Build

Requires JDK 17, Android SDK platform 35 and internet for build dependencies.

```sh
sh scripts/fetch-core.sh
# Set ANDROID_HOME, or create local.properties containing sdk.dir=/absolute/path/to/sdk.
sh ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
# Smaller ARM64 build:
sh ./gradlew :app:assembleDebug -PtargetAbis=arm64-v8a
```

Build output is `app/build/outputs/apk/debug/app-debug.apk`. Copy it before building another
ABI selection because the filename is reused. The default includes ARM64, ARMv7, x86 and x86_64.

```sh
sh ./gradlew :app:connectedDebugAndroidTest
```

The live updater test is separately opt-in with `-e live_updates true` and requires installing
a temporary lower-version build of the updater (`-PappVersionCode=6 -PappVersionName=0.5.99`)
on the emulator. It checks the published APK and opens Android's confirmation without accepting
installation. A slow transfer can use `-e reuse_partial true` to retain its previously downloaded
bytes; the opt-in test permits up to five minutes for the network transfer. Do not publish that test build or downgrade a real user's installation.

Live-subscription tests are opt-in via the instrumentation `subscription` argument and require
Android VPN permission on the test device. Keys are never embedded in test source. The optional
`test-probe` package has a separate UID to check real TUN-routed HTTPS requests. It is not part
of the delivered VPN APK. See `VERIFICATION.md` for executed checks and limitations.

See `NOTICE.md` and `licenses/` for the native library sources and licenses. To use a modified
compatible engine, rebuild the pinned wrapper source, replace `app/libs/libv2ray.aar` and rebuild.

## Install warning and signing

These remain development APKs signed with the same debug certificate as 0.1–0.3 so they can
update the existing installation. Versions 0.2.1 and 0.3.0 had identical package identity,
permissions and certificate; 0.3.0 did not add a signing key or permission change. An
“unknown app / developer not recognized” warning alone does not establish a malware finding.
The exact device-specific trigger is unverified. Release signing and developer registration
are separate distribution work and do not guarantee removal of installation warnings.
