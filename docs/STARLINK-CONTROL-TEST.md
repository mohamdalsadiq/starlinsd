# Slotra 3.6.0 — explicit single-device control experiment

## Evidence and scope

The owner tested 3.5.0 on Android 34 / Starlink Gen 2: native router gRPC returned 12 records; a phone disappeared on Wi-Fi disconnect and returned on reconnect. The official Starlink app successfully paused and resumed `realme-C55`, showing a masked MAC and the numeric label `977656928`. That label is only a candidate client ID until compared with the actual wire reply.

3.6.0 exposes uint32 client ID, nullable blocked state and device role. It adds an explicit, manually confirmed pause/resume experiment. It does not change financial records, subscription clocks, Room schema, notifications, or automatically block anyone. Continuous monitoring and accumulated-time subscriptions remain later work, contingent on hardware verification. A paused device can remain Wi-Fi-associated; association alone must never drive billing.

## Protocol basis

- Published schema: https://github.com/Eitol/starlink-client/blob/a9164c8cf24774c1137d9c28680d8247bf6849eb/proto/spacex/api/device/wifi.proto
- Client ID=43, blocked=42, role=14; absent booleans stay unknown.
- Request/response wifi_get_config=3009; response inner wifi_config=1; WifiConfig client_configs=74, incarnation=43, mac_lan=13.
- Targeted request wifi_set_client_given_name=3017, inner client_config=2. ClientConfig ID=1, MAC=2, given_name=3, repeated weekly_block_schedules=5, group_id=6.
- Schedule block_ranges=1, group_id=2; range start_minutes=1/end_minutes=2. The candidate full-week interval is [0,10080).
- This hypothesis is also implemented in https://github.com/keithah/openwrt-starwatch/blob/a997afdca783cd03ea7cc5caf4814a19d83cedcb/router/internal/dish/router_control.go and described in its block-design document. Its fake-server tests do not establish actual Starlink firmware support. Slotra implements its own bounded encoder/controller from the protocol definitions; no third-party controller code was copied.

## Behavior and safeguards

- Read-only preflight, then a second explicit confirmation naming the device, ID and IP. Confirmation expires after 60 seconds and is single-use.
- Native gRPC to the Wi-Fi-bound 192.168.1.1:9000 only, no cloud credentials, no AI API, no retries/fallbacks for a mutation.
- Require a nonzero uint32 client ID, an unambiguous current record, main-LAN IPv4 and router identity. Exclude the management phone's local IPs, controllers and repeaters. Recheck ID/MAC/name/IP, router identity, revision and targeted config immediately before dispatch. This is a best-effort client-side race guard, not a server-side transaction.
- Reject a new test if the target already has a block/schedule or another Slotra test needs recovery.
- Preserve raw unknown fields and non-owned schedules in the targeted ClientConfig. Never send WifiSetConfig or replace the collection. Never request debug/calibration/reset/bypass operations.
- A unique `slotra-<UUID>` marker identifies the test schedule. Before dispatch, durably save a minimal recovery record using AtomicFile in Android's private noBackupFilesDir. No credentials, raw router config or financial data are stored there. Opening the page only reads that record; it never resumes a write automatically.
- Recovery rereads current data and removes only that exact schedule marker. It never restores a stale full config. If the marker is already absent, no mutation is sent. A changed/missing router identity blocks recovery and points the user to the official app.
- At most three post-write readback attempts, never mutation retries. RPC OK alone is insufficient. Both schedule/config preservation and an explicit live blocked field are needed for a fully verified state; missing/default-elided fields are reported as unconfirmed. Real Internet access must also be checked on the test phone with cellular disabled.
- Leaving the screen cancels work; after dispatch the result may be unknown and the recovery record persists. Force-stop, uninstall or loss of Wi-Fi can prevent in-app restoration. The official app remains the manual recovery route. No automatic timeout is promised for the persistent all-week test schedule.
- Full config response exists transiently only while extracting client entries, revision and router identity. Diagnostics contain outcomes/counts/error codes, never names, client/router IDs, MACs, IPs, credentials or raw payloads.

## Owner acceptance test

1. Install as an update without uninstalling. Connect to the main Starlink Wi-Fi. Keep the official app available for recovery.
2. Run the read test and compare the selected phone's ID/name/IP with the official app. If no ID is returned, stop and share the sanitized read diagnostic.
3. Select that test phone and run the control preflight. Confirm only that phone. Do not choose the management phone or a real customer's device.
4. Disable cellular data on the target, check a new Internet request before and after pause. Check the official app's pause indicator. A cached page is not evidence.
5. Use “فحص إعادة الإنترنت”, confirm and verify Internet returns. If anything is uncertain, restore with the official app and reopen the Slotra recovery flow to reconcile its record.
6. Send “نسخ تشخيص التحكم”. Permission denied, unsupported RPC, or ignored schedule are valid experimental outcomes, not success.

No physical-router control has been verified in the build environment. Unit/fake-transport/UI tests prove app behavior and guardrails only.
