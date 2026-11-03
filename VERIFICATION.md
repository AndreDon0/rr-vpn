# RR VPN 0.5.0 verification — 2026-11-03

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
