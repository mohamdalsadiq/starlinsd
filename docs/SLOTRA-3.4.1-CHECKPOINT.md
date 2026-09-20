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

Release: 3.4.1, versionCode 9, same application ID and original signing certificate. No new migration or changes to stored data. Fresh CI and signed artifact verification will be recorded after completion. No physical-device verification is claimed.
