# RR VPN 0.6.1 UI

The Figma file supplied by the user was a sketch, not a strict specification:
https://www.figma.com/design/WgUUAnqAKq3uxoneLA9Fpf/VPN?node-id=6-3

The revised native layout keeps the central power control and settings access, with a neutral
#F5F7FB background, white cards, #172338 text and #2E5FDD accent. Local vector icons replace
the earlier raster power/gear exports. No new runtime design dependency or remote assets.
The power disk/halo is gray when disconnected, blue while connecting and green when connected;
connection text and accessible action descriptions remain available alongside the color.

Home contains connection state, a short action hint, aggregate Download/Upload totals and a
single scrolling content area. Server names come from subscriptions; numbers are actual
cumulative traffic including health probes, not speed or health measurements. No fake status,
latency, flags or server examples are built in. Empty pools have a direct Add subscription action.

Settings use full-screen grouped cards for sources, connection and app preferences. Smart
routing has a separate 48 dp info target explaining priority and automatic refreshes. There is
no manual routing-list refresh control; Refresh only updates subscription sources.

English and Russian resources cover the interface, tutorial, errors and notifications. The
language picker includes Follow system, English and Русский. First launch chooses the first
supported language in the phone's ordered list, falling back to English. Android 13's per-app
LocaleManager is authoritative and old saved choices migrate once; older phones keep a local
choice. Native Android permission prompts remain system-owned. Existing saved subscription/routing settings survive updates and UI recreation.

The guide has three pages: adding links explicitly through Settings → Add, Android VPN
permission, and connection distribution. Each uses a vector icon, including the power icon on
page two. The progress bar starts at 1/3, then 2/3 and 3/3, with a localized percentage for
accessibility. The rounded dialog scrolls for small screens/enlarged text. It appears once,
supports Back/Next/Skip, and can be replayed in settings. Help text stays in
the guide and info dialogs; the normal connection screen remains compact.

Screenshots in outputs/rr-vpn-*.png use synthetic fixtures and contain no private subscription
key. Home and settings layouts scroll for small screens and enlarged text.

The header adds a 48 dp update touch target immediately before Settings. Its 34 dp circular
ring fills from actual byte progress around a download arrow. The control is hidden when there
is no new release, pauses without spinning while waiting for Wi-Fi, and stays complete/static
when ready. It does not block connection controls. The transfer is independent of Activity
instances, so rotation and language selection do not reset it.

Tapping the indicator opens a bottom-aligned, rounded white sheet: version title, What’s new,
localized change notes, Later and Update. The Update button is available only after verification.
No installed-version, timing, approval or network-policy paragraphs appear in the sheet. A
Wi-Fi-only switch lives in Settings and defaults on. Legacy scheduled update notifications are
removed. Renderer screenshots use a labeled future-version fixture; live screenshots use the
actual published release.
