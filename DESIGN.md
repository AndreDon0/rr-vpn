# RR VPN 0.6.0 UI

The Figma file supplied by the user was a sketch, not a strict specification:
https://www.figma.com/design/WgUUAnqAKq3uxoneLA9Fpf/VPN?node-id=6-3

The revised native layout keeps the central power control and settings access, with a neutral
#F5F7FB background, white cards, #172338 text and #2E5FDD accent. Local vector icons replace
the earlier raster power/gear exports. No new runtime design dependency or remote assets.

Home contains connection state, a short action hint, aggregate Download/Upload totals and a
single scrolling content area. Server names come from subscriptions; numbers are actual
cumulative traffic including health probes, not speed or health measurements. No fake status,
latency, flags or server examples are built in. Empty pools have a direct Add subscription action.

Settings use full-screen grouped cards for sources, connection and app preferences. Smart
routing has a separate 48 dp info target explaining priority and automatic refreshes. There is
no manual routing-list refresh control; Refresh only updates subscription sources.

English and Russian resources cover the interface, tutorial, errors and notifications. The
language choice is app-local and persists. Native Android permission prompts use the phone’s
system language. Existing saved subscription/routing settings survive updates and UI recreation.

The guide has three pages: adding links, Android VPN permission, and connection distribution.
It appears once, supports Back/Next/Skip, and can be replayed in settings. Help text stays in
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
