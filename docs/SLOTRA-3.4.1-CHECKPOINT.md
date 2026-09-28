# Slotra 3.4.1 — preserve today's progress after reconciliation

The user reported that today's 17,000 was already inside the absolute cash/bank balance, but both daily target and shortfall displayed 20,466.35. The 3.4 implementation used the reconciliation balance as the day's opening basis and credited only later receipts. This reset daily progress at every reconciliation. Actual wallet balances and total remaining bill were not doubled.

## Correction
- For a reconciliation today, reconstruct the planning opening basis as the entered balance's cash equivalent minus today's confirmed receipts through that reconciliation. This is a calculation only; never subtract from or add to stored actual funds.
- Daily target is the positive unmet cost at that opening basis divided, rounded up, by remaining calendar days including today. The shortfall deducts today's progress once. Later receipts reduce shortfall without moving the target; reconciling the same balance again does not reset progress.
- Preserve a negative planning opening basis when today's receipts were spent; clamping it would hide the funding gap. The actual balance itself remains nonnegative.
- Existing updates from earlier days still use the opening balance at the current local day's start. Yesterday's receipts are not credited again.
- Cash/bank conversion, revenue-recognition rules, stored reconciliation records and Room schema 6 are unchanged. Pending paid receipts remain distinct from recognition; recognizing them cannot add money again.
- Update the explanatory copy in the existing cards and balance form. No visual redesign.

## Reported example
Remaining cost 327,461.54 and today's included receipts 17,000, with 16 days including today:
`ceil((327,461.54 + 17,000) / 16) = 21,528.85` total daily target.
Today's shortfall is `21,528.85 - 17,000 = 4,528.85`; total remaining cost stays 327,461.54. Bank shortfall at the displayed 30% premium is 5,887.51.

## Verification
The test-only commit reproduces this exact failure on the old implementation. Added regressions cover cash/bank receipts already included, repeated updates, later/future receipts, next-day rollover, previous-day exclusions, pending recognition, withdrawals, last-day coverage, dashboard values and notification parity.

Release: 3.4.1, versionCode 9, same application ID and original signing certificate. No new migration or changes to stored data. No physical-device verification is claimed.

### Verified results — 2026-09-20
- Red run [35519144180](https://github.com/mohamdalsadiq/starlinsd/actions/runs/35519144180), test-only commit `1fb630b82fa60c0dbab7126935b0d2798d842900`: 69 tests, exactly one failure in the screenshot regression (`expected 2152885 but was 2046635`). All prior 68 tests passed.
- Green run [35519322728](https://github.com/mohamdalsadiq/starlinsd/actions/runs/35519322728), application commit `4a9c64e080900a16585f49a870c34658dd931f22`: 76 passed, zero failures/errors/skips; `lintDebug`, `assembleDebug`, `assembleRelease` succeeded.
- Reviewed `reconciled-day-progress.png`: revenue 17,000; target 21,528.85; shortfall 4,528.85; remaining cycle cost 327,461.54. The UI test also checks bank shortfall and notification parity.
- Existing income edit, void and restore actions remain available in daily/history review. Repository tests cover corrections on the original day and backup persistence. Income corrections update the accounting ledger; actual wallet balances are explicitly reconciled in Settings.
- Unsigned bundle SHA-256: `b7dc8d1a60c369470cb17dd0b82cc9d75ec7912eee7a43b234658e885fd8207d` (13,216,213 bytes).
- Signed APK SHA-256: `0a8bbe1977fa4379de2f1c9b8ae2d04b5eecc4d4218a5b3214939e6485042535`. Verified APK v2/v3 signatures, 16 KiB ZIP alignment and unchanged application contents.
- Verified the package remains `com.aistudio.starlinkmanager.zbxpq`, non-debuggable, with versionCode increasing from 8 to 9. Certificate SHA-256 remains `ec2716e37b05a51578422eef8ea7f913d06e1f622ad2072b75bd6402d91847f9`.
- Final documentation-only commit does not alter the tested application. PR #7 remains a draft and unmerged.
