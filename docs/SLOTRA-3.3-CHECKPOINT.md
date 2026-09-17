# Slotra 3.3 checkpoint

Branch: feat/subscription-shortcuts. Draft PR #1. Do not merge main automatically.

## Scope

- Compact Home: today's normalized income, actual cycle profit after the whole cost and before debts, remaining coverage from total cycle revenue.
- Daily allocation stays separate in Reports. Debts have a dedicated tab; status distinguishes paid, reserved for payment, and unfunded.
- More contains plans, reports, settings and backup. Navigation retains per-screen search/scroll state and has a back action.
- New manual registrations, bulk income and new shortcuts use the single cash price. Historical BANK amounts and conversions remain intact; existing bank shortcuts offer an explicit conversion for future uses only.
- Bounded financial snapshot cache runs on Dispatchers.Default, invalidates on data changes, local midnight, timezone changes and future-record eligibility. Status panel releases its Room transaction before computation and reuses the cache.
- App selection loads PackageManager labels on Dispatchers.IO with search, loading and retry. Subscriber filtering accepts Arabic/Persian digits and exposes a ten-minute expiry filter.
- App version 3.3 (7); package IDs unchanged.

## Protected behavior

Do not replace or change SubscriptionAlarms, TextExpanderService, SubscriptionRepository, Rules, Finance, Revenue, Room schema, or backup format. No existing customer data is migrated or deleted by this update.

## Verification

Required gates: testDebugUnitTest, lintDebug, assembleDebug; unsigned assembleRelease also builds in CI.
Tests cover accounting equivalence, 2,000 records over 240 clock updates, edits/deletions/payments, midnight/future timestamps/timezones, legacy conversion, Arabic subscriber search, cash-only bulk entry, plan edit compatibility, navigation state and large Arabic type.

CI must be checked at the final application commit before delivery. No actual-device frame timing has been measured: Robolectric rendering and cache tests do not prove a frame-rate guarantee on the user's Honor phone.

## Delivery

User explicitly accepted the app-debug artifact + backup/uninstall/restore fallback. Current CI generates a new debug signing certificate per run, so do not promise in-place installation of debug APKs. The release application has a separately preserved private signing kit; do not recreate or publish it. This work environment has no terminal/runtime for local signing. Keep private signing materials out of the repository.
