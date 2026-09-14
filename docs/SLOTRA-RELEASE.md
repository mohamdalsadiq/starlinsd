# Slotra 3.1 — continuation and release procedure

Feature branch: `feat/subscription-shortcuts`, PR #1. Do not reset user work or merge main as part of a continuation.

## Implemented in 3.1
- Household shortcuts expand text only: no subscriber number, session, timer, notification or revenue. Legacy household sessions are cancelled and hidden without erasing history.
- Append-only revenue corrections: edit, exclude or restore today's and historical entries, preserving original dates and price/conversion snapshots.
- Fixed daily bill allocation, daily surplus, debt allocation and available surplus; full-cycle profit is separately labelled. Monthly totals and compact three-day history with current/previous month calendar.
- Debt periods, due-date priority allocation, explicit idempotent actual payments. Income corrections preserve payments and expose any funding gap; paid funds cannot be allocated twice.
- Bulk counts × prices; cash/bank snapshot conversion; subscriber pool 1–50 configurable to 10000; safe transactional slot reservation.
- Room schema 5 with non-destructive upgrades from versions 1–4. JSON backup v5 includes all financial records, settings, shortcuts and allowed apps; restores versions 2–5 with validation and transactional replacement. Android cloud/D2D backup rules included.
- Slotra label on both builds and original generated launcher image. Native Compose layout and evergreen/mint theme.

## Stable release signing — mandatory for every future version
Release package: `com.aistudio.starlinkmanager.zbxpq`. Version code 5, version name 3.1.
Debug `.preview` builds are test artifacts with ephemeral keys; NEVER present them as update-compatible production releases.
A private signing kit named `Slotra-private-signing-kit.zip` is stored in the owner's private account. Retrieve that exact existing kit for every release. Do not create a new key if the kit is unavailable: stop the release and recover it. Never copy the key/password into Git, logs, issue text, or a public artifact.
The kit contains PKCS12 key `slotra-release.p12`, alias `upload`, password file, and public certificate. Release instructions use environment/file password arguments; never echo the password.

CI tests/lints/builds debug and unsigned release, and uploads the unsigned APK and SDK signing tools as a standard workflow artifact. GitHub Actions retains contents:read. Download the artifact from a successful run of the reviewed feature head before signing. Auto-review rejected granting contents:write and pushing artifacts to a branch; that approach was removed and must not be reintroduced without specific approval. Use the provided SDK apksigner and zipalign. Verify the signed APK with `apksigner verify --verbose --print-certs`, check certificate fingerprint matches the kit and `zipalign -c -P 16 4`. Save the signed APK for the user. Increase versionCode for every future published APK and keep this package and key unchanged.

Older preview APK certificates were generated afresh in CI; they cannot be recovered from the APK. The new release uses the release package, so it can coexist with the preview. Export JSON from the preview, install Slotra, and restore it. Do not tell the user to uninstall before backing up.

## Verification / checkpoints
Release application source: `16d29cd4ab4f38b2adb087dffef46386e72bd2b6`.
GitHub Actions run: https://github.com/mohamdalsadiq/starlinsd/actions/runs/34819177111
The final application source passed testDebugUnitTest, lintDebug, assembleDebug and assembleRelease. All 33 tests passed (0 failures/errors), as did lint and both APK builds. The job completed successfully on 2026-09-14 at 07:50 UTC.
Tests cover accounting, fixed daily allocation, actual debt payment protection, revenue corrections, household behavior, slot exhaustion/reuse, bulk idempotency, backup restore/corruption, Room upgrades and native dashboard/calendar UI. Native screenshots were visually inspected at 360 × 800 dp. This is not a physical-device test.

## Known operating boundaries
The app tracks registered timers, not router discovery or automatic disconnection. Google Drive file save uses Android's file picker/provider and requires the user to choose a destination. Auto Backup timing depends on Android/Google settings. Profit for days outside a configured billing cycle is explicitly unknown; income is retained. No statement of guaranteed background uptime or zero bugs is justified.

Public release certificate SHA-256: `ec2716e37b05a51578422eef8ea7f913d06e1f622ad2072b75bd6402d91847f9`. This is public identity information, not a private key.

Use `python scripts/sign_release.py bundle.zip private-kit-directory Slotra-3.1.apk`. The script aligns, signs, verifies the saved certificate, and compares every application ZIP entry with the unsigned build.


## Final signed release — 2026-09-14
- Signed file: `Slotra-3.1.apk` (12,130,755 bytes), saved for the owner.
- APK SHA-256: `78d7996c0f73ae7a4a8a2de88ee764bbeec5c7850c1ee9b2c37fc68f80b5df01`.
- Unsigned CI bundle SHA-256: `91b5fe7366b9d3d80b502f55f7aebf1c0e66f9033ed2a02d8a6699867bf0f685`, verified after transfer.
- APK v2/v3 signatures verified against the saved release certificate; 16 KiB ZIP alignment passed; every application ZIP entry matches the unsigned CI artifact.
- Binary manifest verified: original release package, versionCode 5/versionName 3.1, minSdk 24/targetSdk 36, not debuggable.
- Five native screenshots visually inspected: dashboard, daily budget, cycle profit, compact history and calendar. Physical-device permission/keyboard/provider behavior remains device-dependent.
- Any following completion commit changes documentation only; the compiled application source remains the exact commit listed above.
