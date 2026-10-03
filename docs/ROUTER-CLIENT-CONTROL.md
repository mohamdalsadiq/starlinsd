# Slotra — Starlink router client-identity prototype (`feat/router-client-control-v1`)

Experimental branch. Read-only feasibility only. No production behaviour is touched: subscription
timers, pause/resume logic, revenue, Room schema, HOME registry, daily confirmation, notifications,
shortcuts, recovery and backup/restore are unchanged. The prototype adds one isolated package and
one diagnostic panel reachable from the existing «اختبار Starlink» screen.

## Question

Can Slotra identify devices directly through the Starlink router, independently of the unstable
Starlink `clientId`, and can the router also pause/block a client?

## Scope of the prototype

- Package `com.example.network.router` (new, isolated):
  - `RouterClient` — diagnostic record keeping every useful field the firmware returns.
  - `RouterClientIdentity` — MAC/IP normalization and stable-identity selection.
  - `RouterClientCodec` — full-field read-only decoder for `wifi_get_clients` (3002) / `wifi_get_status` (3004).
  - `RouterDiscoveryEngine` / `RouterDiscoveryService` — bounded read-only discovery on the current Wi-Fi.
  - `RouterIdentityComparison` — pure A→G identity-stability comparison (connect, record, disconnect,
    reconnect, record, compare). It is free of Android types, so unit tests exercise the exact same
    pairing/verdict logic the hardware test uses.
- UI: `RouterPrototypePanel` inside «المزيد → اختبار Starlink», below the existing control panel.
- Transport: the existing bounded native gRPC transport (`StarlinkProbe.exchange`) to the single
  literal endpoint `192.168.1.1:9000`. No new dependency, no new scheduler, no polling, no
  WorkManager, no ForegroundService, no cloud.

## Published-schema evidence (no invented RPCs)

Source: community reverse-engineered definitions
`proto/spacex/api/device/wifi.proto`, `wifi_config.proto`, `device.proto`
(https://github.com/Eitol/starlink-client, commit `a9164c8`).

`WifiClient` fields decoded by this prototype:

| Field | # | Used as |
|---|---|---|
| name | 1 | display fallback |
| mac_address | 2 | identity candidate (normalized) |
| ip_address | 3 | CURRENT connection only |
| signal_strength | 4 | (available, not yet surfaced) |
| associated_time_s | 7 | current |
| iface | 9 | current |
| upstream_mac_address | 13 | identity candidate |
| role | 14 | current (0/1/2/3) |
| device_id | 15 | identity candidate |
| domain | 22 | current |
| iface_name | 26 | current |
| given_name | 31 | display preference |
| hardware_version / software_version | 37 / 38 | current |
| api_version | 39 | current |
| ipv6_addresses | 41 | current |
| blocked | 42 | control-state field |
| client_id | 43 | identity candidate (unstable) |
| no_data_idle_s | 45 | current |
| dhcp lease fields | 46–49 | current |
| captive_client_id / captive_state | 53 / 56 | identity candidate / current |
| upload_mac / download | 54 / 55 | current |
| sandbox_state | 57 | control-state field |
| active | 58 | connection state |

Candidate control operations found in `device.proto` (Request oneof):

| RPC | Request | Response | Supported by current firmware | Tested on real router | Result |
|---|---|---|---|---|---|
| `wifi_set_client_given_name` | 3017 `WifiSetClientGivenNameRequest` (ClientConfig) | `WifiSetClientGivenNameResponse` | UNKNOWN | NOT TESTED | NOT VERIFIED |
| `wifi_set_config` (whole config) | 3001 `WifiSetConfigRequest` | `WifiSetConfigResponse` | UNKNOWN | NOT TESTED | NOT VERIFIED |
| `wifi_client_sandbox` | 3031 `WifiClientSandboxRequest` | `WifiClientSandboxResponse` | UNKNOWN | NOT TESTED | NOT VERIFIED |
| `wifi_get_config` (read) | 3009 | `WifiGetConfigResponse` | read-only probe | NOT TESTED | read-only evidence only |
| `wifi_get_clients` (read) | 3002 | `WifiGetClientsResponse` | read-only | NOT TESTED | read-only evidence only |

No pause/resume/block/unblock RPC exists as a dedicated operation in the published schema. A block is
carried by `ClientConfig.weekly_block_schedules` (field 5, `WeeklyBlockSchedule` with
`BlockRange.start_minutes`/`end_minutes`) inside the `client_configs` collection (74) — the same
`slotra-<uuid>` marker approach already implemented (and still unverified on hardware) in
`docs/STARLINK-CONTROL-TEST.md`. This prototype performs **no** write and claims no support.

## Manual real-router procedure (owner, hardware required)

Not automatable in the build environment; run on the Galaxy A23 connected to the main Starlink Wi-Fi.

1. Install the debug APK and open «المزيد → اختبار Starlink». Confirm the gateway is `192.168.1.1`.
2. Press **لقطة ١**. Record: `clients`, per-client MAC/IP/deviceId/clientId, router hw/sw.
3. Keep the same phone or laptop of interest connected; compare the panel with the official
   Starlink app at the same moment.
4. Disconnect that device from Wi-Fi (disable Wi-Fi) and wait for it to disappear from the router
   list (optionally verify with a third snapshot from the official app).
5. Reconnect the device. If possible force a natural IP change by leaving it off through a DHCP
   lease boundary; otherwise record the IP as-is and note it in the report.
6. Press **لقطة ٢ · مقارنة**. The panel shows MAC / IP / clientId / deviceId verdicts, plus any
   other identifier that survived the round trip.
7. Copy both diagnostics («نسخ تشخيص القراءة» and «نسخ تشخيص المقارنة») and send them back. The
   diagnostics contain counts and verdicts only — never names, MACs, IPs or clientId values.
8. If the device reappears with a full (unmasked) MAC that survived disconnect/reconnect while the
   IP changed, MAC is the stable identity. If the MAC is masked or the MAC itself changed, the
   router cannot currently provide a permanent identity and the finding must be reported as such.

## Honest status

- `ROUTER CONNECTION` — NOT PERFORMED (no Starlink router reachable from this build environment).
- `CLIENT DISCOVERY` — implemented and unit/fake-transport tested; not yet run against real firmware.
- `REAL DEVICE TEST` — NOT PERFORMED (no Starlink router / Galaxy A23 attached to this environment).
- MAC / IP / CLIENT_ID stability — UNKNOWN until the procedure above is run on hardware.