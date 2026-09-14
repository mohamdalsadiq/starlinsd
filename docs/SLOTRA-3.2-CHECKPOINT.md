# Slotra 3.2 — active release checkpoint

Previous delivered version: Slotra-3.1.apk, versionCode 5, SHA-256 78d7996c0f73ae7a4a8a2de88ee764bbeec5c7850c1ee9b2c37fc68f80b5df01.
User confirmed it installed and restored successfully. Future APKs MUST update this release directly, without deletion or restore.

## Application changes
- VersionCode 6 / versionName 3.2, same release package.
- Silent ongoing status notification, independent low-importance channel, active/near-expiry/ended-in-history/paused counts, corrected income, cycle profit, bill reserve remaining and separately labelled allocation surplus. Household excluded. Immutable action intents, private lock-screen version, manual refresh/open actions.
- Existing deadline events, financial commands, boot/package-replaced/resume and midnight refresh it. No always-running foreground service. Android restrictions can delay background delivery; Android 14+ permits user dismissal.
- Settings toggle and channel settings. Preference survives in-place updates and Android cloud backup; local display toggle is not part of JSON accounting export.
- Profit card directly after today's income. Cycle profit first; exact requested explanation underneath. Coverage details expand on demand. Entry buttons follow profit so the actual cycle profit can appear on the first screen.
- Each debt shows paid / reserved and ready for payment / outstanding without a current reserve, directly on the dashboard. No payment is marked complete automatically.
- Finance.kt, Revenue.kt, accounting repository, Room schema v5 and existing debt allocation rules are unchanged.

## Verification / release pending
Read exact new commit's CI result and inspect native screenshots. Existing CI runs unit tests, lintDebug, assembleDebug and unsigned assembleRelease.
Expected tests: previous 33 + one UI debt-indicator test + four notification-panel tests = 38.
Do not report a passing result before checking it.

The Work execution environment failed its initialize handshake on 2026-09-14. GitHub MCP remains available; edits were recovered from the previous turn and applied directly to the feature branch. Earlier local uncommitted notification edits may still exist when workspace reconnects: compare them to the new remote head before updating the checkout; do not overwrite newer remote UI changes.

## Signing — never replace the existing private key
Recover the owner's existing Slotra-private-signing-kit.zip privately. Public certificate SHA-256: ec2716e37b05a51578422eef8ea7f913d06e1f622ad2072b75bd6402d91847f9.
Never expose private retrieval identifiers, key material or passwords in Git, CI or logs.

After execution returns, retrieve the latest successful unsigned CI bundle, verify transfer SHA256, use scripts/sign_release.py with the existing key, then compare old/new APK package, certificate and increasing versionCode. Room/database schema stays v5. Save Slotra-3.2.apk for download. Until then NO signed 3.2 APK exists; do not send the old 3.1 or CI debug APK as the update.

See SLOTRA-RELEASE.md for read-only artifact transfer and signing procedure. CI must keep contents:read.

Initial 3.2 run 34844304836 passed 37 tests and both APK assembly tasks, but lintDebug caught an API 26 guard issue in StatusPanel.allowed. Added an explicit SDK check and an API 24 regression test. Initial native dashboard screenshot confirms cycle profit on the first screen. Await the follow-up run before signing.
