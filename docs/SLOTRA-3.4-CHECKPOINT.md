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
Room schema and JSON backup format advance to v6 with an additive balance_updates table. Existing v2–v5 backups remain importable; no destructive migrations or old transaction rewrites. Initialization sets the new-session threshold to five minutes while preserving existing session snapshots.
Release application ID stays `com.aistudio.starlinkmanager.zbxpq`. Version code 8, version name 3.4.
Use the original private release kit. Signing refuses any certificate other than the recorded public SHA-256 fingerprint. Never commit a signing key or password.
CI debug `.preview` APKs have ephemeral keys and are not update-compatible production releases. A installed preview with a lost key cannot be upgraded in place by creating a new key.

## Verification strategy
Existing stack: Kotlin/Compose, Room, JUnit4, Robolectric, Roborazzi. Existing repository injection already supports fake time and an in-memory Room database; no extra DI or test libraries were added.
Skills used: systematic-debugging, verification-before-completion, ui-ux-pro-max (Compose guidance), and Android testing-setup (existing-stack testing guidance).
Run `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease -PunsignedRelease -Proborazzi.test.record=true --stacktrace`.
New regressions cover next-day redistribution, fixed intraday target, skipped days, correction, full coverage, future entries, last day, outside-cycle and DST boundaries, five-minute pause/resume, midnight/restart recognition, old-session preservation, and panel/dashboard parity. UI tests render phone, larger Arabic text, and dark landscape.
Local Gradle download is unavailable in this environment; GitHub Actions provides the Android toolchain. The initial implementation passed 59 tests, lintDebug, assembleDebug and assembleRelease at commit 5740f485928a439ce2b934d1d3c492738d469dcc (run 35500561444). A first test-only run proved all five adaptive-plan cases fail on the old implementation; all five subsequently passed. The requested balance extension below requires a fresh verification run. No physical-device test has been claimed.

## Added user request: actual cash and bank reconciliation
- Settings offers current cash and bank amounts, reason, immediate equivalent/remaining preview, and the three latest reconciliation records. These are absolute balances, not additional income or a withdrawal amount.
- A reconciliation records cumulative confirmed cash/bank receipts atomically. It includes paid sessions awaiting recognition. Future receipts add once; recognition, resume, cancellation and revenue corrections never generate another physical receipt or an implied refund.
- Reconciliation intentionally replans the current day's target from remaining funding / remaining days. Later receipts reduce shortfall; a new day recalculates using its opening balance. Earlier days derive their own latest eligible reconciliation.
- Main dashboard retains its card design and adds equivalent bank amounts beneath the daily target, shortfall and remaining cycle amount. Cash/bank equivalents are alternatives at the configured premium, not additive demands.
- Bill funding uses reconciled balances when available. Accounting revenue and full-cycle profit stay intact. Settings separately labels balance surplus after the cycle cost.
- After a withdrawal, expense, refund or debt payment, the operator updates actual balances. Existing debt records do not identify a payment wallet, so they cannot safely auto-debit cash or bank.
- Conversion is a planning equivalent, not proof that cash has been exchanged. No claim of actual Starlink bill settlement is made.
