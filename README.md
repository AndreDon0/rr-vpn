# RR VPN for Android

RR VPN 0.6.0 combines servers from multiple VLESS subscriptions into one shared Xray
round-robin VPN pool. The native Android UI has neutral surfaces, a blue connection button,
clear status, aggregate traffic cards, and a scrolling server list. Settings contain subscriptions,
Smart routing with an info button, a language picker, and a replayable quick guide.

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

There are no scheduled update downloads or update notifications in 0.6.0. The old 0.5.0 job and
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

1. Open **RR VPN**. A three-step guide appears once; it can be skipped or replayed from Settings.
   Tap the gear, then **Add**.
2. Paste HTTPS VLESS subscription URLs, **one link per line**, and tap **Add**.
3. Close settings and tap the large power button. Approve Android's VPN permission prompt.
4. Every unique server from all saved subscriptions enters the same pool. No provider selection
   is needed. Adding an already saved URL updates that source instead of duplicating it.
5. In settings, **Refresh** updates every source; **×** beside a source removes only that
   source. Servers also present in another source stay in the pool. Disconnect before changing
   subscriptions. The Smart routing switch can be changed while connected.

Choose **Language → English / Русский** in Settings. On first use the app follows Russian
for a Russian device locale and English otherwise. An explicit choice persists across restarts.
Changing language recreates the UI without restarting the VPN or modifying subscriptions.
The running VPN notification updates its language on the next two-second status poll.
The routing info button explains Antifilter precedence, direct Russian ranges, the off mode,
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
Other protocols/transports, unencrypted connections and insecure certificate options are rejected.

## Connection behavior

Android permits one active VPN per profile. RR VPN owns that interface and routes each new
TCP/UDP connection through the next available server. Multiple connections can use different
servers simultaneously. One connection stays with one server: this is not bandwidth bonding
or packet duplication, and interrupted connections cannot migrate between servers.

Xray probes all servers concurrently through HTTPS to `https://www.gstatic.com/generate_204`,
waiting 15 seconds between completed batches. A probe can take about five seconds. Unknown
servers are initially considered available, and failure/recovery detection is periodic.
A successful probe establishes connectivity to that destination, not every website or capacity.

Traffic cards and rows show cumulative download/upload since connection startup, including
health checks. They are totals, not speeds. No unmeasured latency or health badges are shown.
Provider-supplied names/flag emoji remain dynamic.

## Smart routing and automatic list updates

Smart routing is **on by default** when no choice has been saved. A saved off/on choice is
preserved across app updates.

Switch **off**: every destination uses the VPN pool.

Switch **on**, first matching rule wins:

1. Antifilter IPs → **VPN pool**.
2. Other Russian IPv4/IPv6 ranges → **direct**.
3. All remaining IPs → **VPN pool**.

An IP in both lists therefore uses the VPN. Russian IPs absent from Antifilter remain direct
even if every VPN server is unavailable. Other traffic uses the pool's blackhole fallback.

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
snapshot. Disabling Smart routing cancels its pending update jobs. Feed downloads use the
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
