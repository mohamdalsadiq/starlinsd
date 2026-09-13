# Slotra 3.0 — continuation and release procedure

Feature branch: `feat/subscription-shortcuts`, PR #1. Do not reset user work or merge main as part of a continuation.

## Implemented, awaiting final CI evidence
- Compact three-day dashboard history, current/previous month calendar, selected-day ledger including manual income.
- Bulk counts × unit prices, cash/bank snapshot conversion, UUID batch deduplication, immediate recognized income without sessions.
- Subscriber pool default 1–50, configurable to 10000; transactions reserve available numbers, respect paused subscriptions, release failed expansions, recycle expired/cancelled subscriptions. History IDs remain immutable.
- Full JSON backup/restore (versions 2, 3, 4), bounded input/strict validation, transactional replacement with in-app confirmation and pre-restore snapshot. Includes allowed apps and legacy data. Native Android cloud/D2D backup rules.
- Slotra name and original generated launcher image. Native Compose theme maintained; no replacement framework.
- Room schema 4 migration preserves earlier data. Generated schema must be captured from CI and committed.

## Stable release signing — mandatory for every future version
Release package: `com.aistudio.starlinkmanager.zbxpq`. Version code 4, version name 3.0.
Debug `.preview` builds are test artifacts with ephemeral keys; NEVER present them as update-compatible production releases.
A private signing kit named `Slotra-private-signing-kit.zip` is stored in the owner's private account. Retrieve that exact existing kit for every release. Do not create a new key if the kit is unavailable: stop the release and recover it. Never copy the key/password into Git, logs, issue text, or a public artifact.
The kit contains PKCS12 key `slotra-release.p12`, alias `upload`, password file, and public certificate. Release instructions use environment/file password arguments; never echo the password.

CI tests/lints/builds debug and unsigned release, and uploads the unsigned APK and SDK signing tools as a standard workflow artifact. GitHub Actions retains contents:read. Download the artifact from a successful run of the reviewed feature head before signing. Auto-review rejected granting contents:write and pushing artifacts to a branch; that approach was removed and must not be reintroduced without specific approval. Use the provided SDK apksigner and zipalign. Verify the signed APK with `apksigner verify --verbose --print-certs`, check certificate fingerprint matches the kit and `zipalign -c -P 16 4`. Save the signed APK for the user. Increase versionCode for every future published APK and keep this package and key unchanged.

Older preview APK certificates were generated afresh in CI; they cannot be recovered from the APK. The new release uses the release package, so it can coexist with the preview. Export JSON from the preview, install Slotra, and restore it. Do not tell the user to uninstall before backing up.

## Verification / checkpoints
Current changes were published for GitHub Actions because local Gradle distribution download is blocked. Read current run statuses and full failures; fix actual errors, then rerun. Do not claim passing checks from earlier 2.1 builds.
New tests cover pool exhaustion/reservation expiry/reuse, bulk income/idempotency, backup restore/corruption, existing accounting behavior, Room upgrade, and native compact-history/calendar screenshots.
Pending: latest CI result, schema capture, rendered UI inspection, unsigned artifact transport, local signing and certificate verification, signed APK save/download, PR/docs update.

## Known operating boundaries
The app tracks registered timers, not router discovery or automatic disconnection. Google Drive file save uses Android's file picker/provider and requires the user to choose a destination. Auto Backup timing depends on Android/Google settings. Profit for days outside a configured billing cycle is explicitly unknown; income is retained. No statement of guaranteed background uptime or zero bugs is justified.
