# Slotra 3.4 — adaptive daily plan

Base: `feat/subscription-shortcuts` at `ed693a569428742a7f92a7d3fa3ae4df57bf2e75`.
Work branch: `feat/slotra-daily-plan`. Do not merge automatically.

## Approved behavior
- Day target = ceil(max(cycle cost - recognized income before this day, 0) / remaining local calendar days including today).
- Receipts within a day change its shortfall, not its target. Previous-day corrections and cycle edits recalculate the derived plan; the UI explains this.
- Full-cycle coverage includes all recognized receipts exactly once. Daily surplus is separately labelled and is not spendable cycle profit. Coverage is not a cash reservation or a paid bill.
- Shortcut use confirms payment. New sessions use five counted minutes; pause excludes elapsed time, resume never creates another sale. Existing session price, threshold, conversion and recognition timestamps remain unchanged.
- Recognition belongs to the calendar day the counted threshold is reached, including delayed reconciliation after process absence.
- Household shortcuts remain text-only. Router reading and automatic Wi-Fi disconnection are out of scope.
- Dashboard and status panel derive daily revenue, target, shortfall, remaining cycle cost and cycle profit from FinancialSnapshot.
- Debt payments remain manual and isolated. The prior UI incorrectly disabled payment without automatic allocation; it now accepts an actual payment up to the remaining debt and shows due dates.

## Data and compatibility
Room schema and JSON backup format stay at v5; no destructive migrations or old transaction rewrites. Initialization sets the new-session threshold to five minutes while preserving existing session snapshots.
Release application ID stays `com.aistudio.starlinkmanager.zbxpq`. Version code 8, version name 3.4.
Use the original private release kit. Signing refuses any certificate other than the recorded public SHA-256 fingerprint. Never commit a signing key or password.
CI debug `.preview` APKs have ephemeral keys and are not update-compatible production releases. A installed preview with a lost key cannot be upgraded in place by creating a new key.

## Verification strategy
Existing stack: Kotlin/Compose, Room, JUnit4, Robolectric, Roborazzi. Existing repository injection already supports fake time and an in-memory Room database; no extra DI or test libraries were added.
Skills used: systematic-debugging, verification-before-completion, ui-ux-pro-max (Compose guidance), and Android testing-setup (existing-stack testing guidance).
Run `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease -PunsignedRelease -Proborazzi.test.record=true --stacktrace`.
New regressions cover next-day redistribution, fixed intraday target, skipped days, correction, full coverage, future entries, last day, outside-cycle and DST boundaries, five-minute pause/resume, midnight/restart recognition, old-session preservation, and panel/dashboard parity. UI tests render phone, larger Arabic text, and dark landscape.
Local Gradle download is unavailable in this environment; GitHub Actions provides the Android toolchain. Results and signed artifact verification will be recorded after the build completes. No physical-device test has been claimed.
