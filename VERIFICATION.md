# RR VPN 0.6.2 verification — 2026-11-04

Passed locally before publication:

- Universal APK build with four ZIP-compressed native ABIs, 22 JVM tests and Android lint
  with zero errors. Artifact: 63,342,865 bytes; SHA-256
  `fd32bbae8a44f771348428a3bcfad56148f4df3d06eb2c65a452e2990f3ebdb5`.
  The original signing certificate is retained; no private subscription URL is embedded.
- A fixed destination-IP rule is first in every Xray configuration, including smart-routing
  off mode. It routes private/local, shared carrier, loopback, link-local, multicast and
  limited broadcast ranges directly. Public Antifilter/RU/default precedence is unchanged.
  Prefix boundary tests distinguish local ranges from adjacent nonlocal addresses.
- Five instrumentation scenarios passed on the API 37 / ARM64 emulator: real Xray balancing,
  health filtering/failure/recovery; local-first and public-IP routing with smart routing on/off;
  routing snapshot storage/job configuration and full-list native startup; EN/RU routing-info
  and language persistence; and actual TUN startup with connected-language notification changes.
- The native precedence scenario puts every local prefix into Antifilter deliberately and
  checks local IPv4/IPv6 destinations still choose direct in both modes. Public overlaps use
  the VPN pool, RU-only public ranges choose direct only with smart routing enabled, and public
  neighbours of local ranges stay in the VPN pool. Both test outbounds redirect into local
  responders so routing outcomes are deterministic and require no provider credential.
- The health scenario uses a synthetic nonlocal destination to exercise the pool, proves
  blocked public traffic while all observations are unhealthy, proves local loopback remains
  reachable at that time, and confirms recovery. Its synthetic traffic never reaches the Internet.
- The updated Russian routing-info dialog was visually inspected. Both language resources and
  README now explain the local exception when smart routing is off. The released APK installed
  over the preceding 0.6.1 emulator package using the same signing identity.

These checks verify Xray IP-rule selection plus Android TUN startup; they do not establish
physical LAN discovery, scoped IPv6 link-local connectivity, broadcast/multicast delivery or
access to an actual router/printer through another app. Android VPN routes, DNS handling and
subscription formats are unchanged. On-link devices with globally assigned IPs still follow
public-IP rules. Physical phones, older Android versions, non-ARM64 execution and packet audits
remain untested. The local rule is fixed in the app and does not need periodic list refreshes.

## RR VPN 0.6.1 verification — 2026-11-03

Passed locally before publication:

- Universal APK with four ZIP-compressed native ABIs, original signing identity, 21 JVM tests,
  and Android lint with zero errors. Artifact: 63,342,361 bytes; SHA-256
  `7f0e88e0b16df25f526890b68701d241144b3710a5e456fb57f60e469fe0fd90`.
- Seven UI/subscription emulator scenarios on API 37 / ARM64 passed: ordered supported-language
  selection and English fallback; legacy preference migration and native Android app-language
  reset; tutorial navigation/replay/progress; English↔Russian persistence and routing info;
  default smart routing; subscription migration/removal; and language changes while connected.
  The live-provider scenario was skipped because it is opt-in. No private subscription was used.
- Tutorial progress asserts max 3 and values 1, 2 and 3. Page two asserts the vector power icon.
  Screenshots confirm the visible fill and Settings → Add instruction. The connected-language
  scenario starts the actual native VPN with a synthetic TLS VLESS entry, asserts different
  off/on disk colors and restoration after stop, and checks the translated notification without
  changing the routing generation. This does not establish live provider connectivity.
- Five updater regression scenarios passed with same-key and different-key 0.6.2 fixture APKs:
  APK verification/provider scope; legacy-job cancellation; Wi-Fi default/persistence;
  resumable byte ranges and full-response fallback; corner state, short localized sheets,
  and settings/recreation. Two opt-in live-network scenarios were skipped. Fixtures were not published.
- Tutorial and update-sheet scenarios also passed at 360×640 dp and 130% text size. Guide
  pages, disconnected/connected controls, and compact English/Russian sheets were visually
  inspected. Screenshots in local outputs/ use synthetic fixtures.
- APK contents contain no private subscription URL. Package identity, signing certificate,
  encrypted storage and routing behavior are retained. Android 13+ uses LocaleManager plus
  localeConfig; older Android keeps the local language picker. Older-device execution is untested.

Publication and final package checks:

- GitHub CI passed for source commit a3cf9cf (run 41674351678).
- The signed universal APK installed over the tested build. A subsequent fresh launch with
  the emulator's device language set to Russian displayed the Russian guide automatically;
  its original en-US device language was restored afterwards. Temporary test data was removed.
- v0.6.1 is public. GitHub's asset digest, byte count and public raw update feed match the
  artifact above. Both English/Russian notes are present. All Releases together contain
  exactly one APK: RR-VPN-0.6.1-universal.apk; older APK/checksum assets were removed.

The supplied install screenshot matches Google's **App Scan Recommended** prompt for an APK
Play Protect has not scanned, rather than a malware result. The app cannot suppress Google's
prompt; the recommended action is to scan and follow the result. See
https://developers.google.com/android/play-protect/warning-dev-guidance.
The phone's own Play Protect flow was not reproduced on the emulator.

Physical phones, older Android versions, non-ARM64 execution and packet/throughput audits remain
untested. Live GitHub download/resumption and native installer evidence for the unchanged updater
is recorded under 0.6.0 below.

## RR VPN 0.6.0 verification — 2026-11-03

Passed locally before publication:

- Universal APK build with four compressed native ABIs, Android lint with zero errors, and
  21 JVM tests including legacy feed compatibility, localized notes and oversized-note rejection.
- Ten emulator scenarios on API 37 / ARM64: updater signer/hash/version checks; private
  provider and legacy-job cancellation; default-on/persisted Wi-Fi policy; pause/resume byte
  ranges and server-ignores-Range fallback; corner-progress/ready rendering and short EN/RU
  sheets; tutorial; language persistence; subscription migration/removal; connected VPN
  language change; native IPv4/IPv6 routing precedence.
- A separate test with emulator Wi-Fi disabled selected no download network when Wi-Fi-only
  was enabled, and selected the actual cellular network when disabled. Wi-Fi was restored.
- The update UI scenario repeated at 360×640 dp and 130% text size passed. The ready state
  explicitly has no running animation. Sheets contain version/change notes and controls;
  the previous timing/installer paragraphs and installed-version field are absent.
- Same-key and wrong-key 0.6.1 APK fixtures were used locally, never published. Resumption tests
  pause after saved bytes, then assert a nonzero Range offset and final package verification.
  Renderer screenshots inject a 42% progress state; they are not throughput measurements.
- Normal/compact English/Russian sheets and corner states were visually inspected. APK
  contents were scanned for the private subscription marker; none is embedded.

Live publication verification:

- v0.6.0 is public with one universal APK (63,384,923 bytes) and its checksum. The previous
  release APK/checksum were removed. SHA-256 matches the public feed and GitHub asset digest:
  cb43fe9589a85b4a0ff4f5d3509a40b41a5e300ca5c8dce6a7b6c2a75688cbe9.
- The opt-in test ran the new updater in a temporary lower-version build, observed actual
  download progress, recreated the Activity mid-transfer, verified the published APK, opened
  the short version/changelog sheet and reached Android's real Update confirmation unaccepted.
- A slow connection exceeded the initial two-minute test wait at roughly 58 MB. The transfer
  paused with its partial intact; a rerun reused those bytes and passed in 39 seconds. The
  opt-in network test now allows five minutes and supports reuse_partial=true. This exercised
  real GitHub range resumption, not just the injected-connection regression.
- Live screenshots are local outputs/rr-vpn-0.6.0-progress.png, rr-vpn-0.6.0-update.png and
  rr-vpn-0.6.0-installer.png. They contain no subscription credentials.
- GitHub CI passed for the app implementation. No APKs, signing keys, or private source URLs
  are committed; only the latest universal APK is hosted in Releases.

Physical phones, older Android versions, non-ARM64 execution, and packet-capture/throughput audits remain
untested. The release retains the existing signing identity and encrypted subscription format.

Earlier release evidence follows:

## RR VPN 0.5.0 — 2026-11-03

Passed locally:

- Universal APK build (four compressed native ABIs), 20 JVM tests and Android lint with zero errors.
- Three updater emulator tests on API 37 / ARM64: newer same-key APK acceptance; SHA-256,
  version/minimum-SDK and wrong-signing-key rejection; private provider read-only/path checks;
  persisted 12-hour job and declared installation permission; Android installer resolution;
  English/Russian Updates UI. A temporary 0.5.1 APK and a separately signed copy were used as
  fixtures, not published. Installation permission remains under Android/user control.
- Existing tutorial, language persistence, multi-subscription migration/removal, connected VPN
  language change, and native IPv4/IPv6 routing-precedence scenarios passed with the updater.
- Repository staging inspected for private subscription URLs/tokens, private keys, signing
  keystores, AARs, APKs and machine-specific SDK paths. None are included. Build output and all
  historical local APKs are ignored. Only the latest universal APK is intended for Releases.

The first test pass incorrectly expected the special install permission to be granted merely
because it was declared; the corrected check asserts its manifest declaration and lets Android
control the per-source approval. The three updater tests then passed. A fixture copy command
was corrected and the actual same-key/wrong-key APK checks were rerun with real fixture files.

Publication/live verification:

- Public repository: https://github.com/AndreDon0/rr-vpn
- Published v0.5.0 has one universal APK (63,329,071 bytes) and its 93-byte checksum. No other
  APKs, signing keys or private subscription data are published. GitHub adds source archives.
- The release API digest and updates/stable.json match the local SHA-256:
  57dfd41e6270355b1619080b28484106744aa3d9cbb1717c2425bfc52adac797.
- An opt-in emulator test used a temporary lower-version updater build (0.4.99/code 5),
  fetched the real public feed, automatically downloaded and verified the released universal
  APK, and opened Android's actual “Update this app?” confirmation for RR VPN. Confirmation
  was left unaccepted. The final test passed. Screenshots are in local outputs/.
- A test-only attempt to press Back through Espresso after opening the external installer
  failed because the app was no longer resumed. That cleanup was removed, and the test now
  waits for the actual Update button rather than just the preparatory installer screen.
- GitHub CI initially lacked sdkmanager on PATH, then the setup action tried its obsolete
  default “tools” package. CI now sets up the SDK explicitly and installs platform-tools,
  platform 35 and build-tools 35; no APK artifacts or dependency caches are uploaded.
Physical phones, older Android versions, non-ARM64 execution and a real 12-hour job wait/reboot
remain untested. The unchanged routing scheduler, leak/lifecycle/throughput limitations from
0.4.0 still apply. The APK retains the original development certificate so it updates existing
installations; no claim of Play Protect approval or production/store certification is made.

Prior release evidence:

## RR VPN 0.4.0 — 2026-11-03

Passed:

- Universal and ARM64 APK builds; 17 JVM tests; Android lint with zero errors.
- Nine distinct emulator tests on Android API 37 / ARM64: first-use language fallback and
  explicit override, tutorial navigation/one-time persistence/replay, English↔Russian UI
  recreation and persisted choice, Smart routing info, encrypted legacy subscription
  migration/add/remove, routing default-on/saved-off behavior, native VPN continuity and
  translated notification during a language change, native balancing/failure/recovery,
  IPv4/IPv6 routing precedence/off mode, and routing snapshot validation/persisted jobs.
  (Some tests exercise several of these behaviors.)
- Eight tests ran together at standard size; the language-default check and two UI scenarios
  passed at 360×640 dp with 130% font size. Home, settings, guide and routing-info screenshots
  were inspected. The final ARM64 package separately passed the connected-language test.
- UI tests now wait for encrypted storage/routing loading instead of an arbitrary 400 ms:
  the first compact-screen run clicked Language before initial loading finished. The final
  compact run passed; no production change was needed for that test synchronization issue.
- The connected-language test starts the actual native TUN with a synthetic TLS VLESS entry.
  It checks service phase, unchanged routing generation and the translated notification;
  it does not claim the synthetic endpoint can carry external Internet traffic.
- APK ZIP inspection: compressed native libraries; four ABIs in universal and only ARM64 in
  ARM64. Same valid signing certificate as earlier releases. Version code increases to 5.
- Versions 0.2.1 and 0.3.0 compared for the reported install warning: package, permissions,
  debug build status and signing certificate match. The phone's exact warning trigger is
  not established by this comparison, and changing signing identity was not attempted.

No private subscription was used in the 0.4.0 test run. Live-provider import, merged-provider
pool and VPN lifecycle tests passed in 0.3.0; those unchanged paths were not repeated with
private credentials here. New screenshots use synthetic servers/provider names only.

Not run: physical-phone tests, older Android versions, non-ARM64 native execution, a real
12-hour scheduling wait/reboot, battery/throughput benchmarks, and packet-capture leak audits.
The scheduled downloader and separate-UID HTTPS probe were checked in 0.2.0 and are unchanged.

These remain locally signed development APKs. Compression reduces download size; Android
extracts the native library on installation. App-local language selection does not change
Android's own permission-dialog language. Subscription storage, package identity, signing
certificate, routing precedence and refresh scheduling remain compatible with 0.3.0.
