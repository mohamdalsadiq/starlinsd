# Slotra 3.7.0: owner-session router control trial

## Evidence and scope

The owner's 3.6.0 LAN mutation returned gRPC PERMISSION_DENIED (7); recovery returned `owned_schedule_absent; no_write` and Internet never stopped. Do not retry that unauthenticated write route.

Research reference: https://github.com/DaveyHert/Dishylink at f3cc17c1defe0dc7f14cd9da8aaaa1d611794973, specifically LOCAL-API.md, electron/cloud.ts, cloud/starlinkCloudHandler.ts and core/routerClientUpdate.ts. Its author reports measured authenticated pause/resume on their installation; this is not proof for the owner's firmware. Slotra implements an independent Kotlin wire subset and Android session flow, not an Electron port.

## Trial

1. Install the signed update, keep the official Starlink app available and stay on the main router Wi-Fi.
2. More → Starlink test → Link account. An embedded WebView opens the real HTTPS Starlink account page. Complete the owner's normal sign-in/verification, then select Verify link. Passwords are never read by Kotlin or sent to another server. Login support in Android WebView remains a hardware/service acceptance gate; do not bypass TLS, CAPTCHA, MFA or an embedded-browser rejection.
3. The native client refreshes the session, reads the local router ID and asks the authenticated cloud gateway for that exact router's status. Only an identical router ID permits saving the session. A read establishes account access, not write permission.
4. Read devices, select another test phone and run preflight. Confirm its name, client ID and IP. The management phone cannot be paused.
5. Confirm pause, disable cellular on the target, and try a fresh Internet request. Then select Check restoration and confirm. Check Internet returns and compare the official app. Copy sanitized control diagnostics, or login diagnostics if linking failed.

The gateway is an observed, unofficial interface and may change. There is no AI, external Slotra server, autonomous blocking, background monitoring, financial change, or time-based enforcement in this release.

## Protocol

- HTTPS auth refresh: `api.starlink.com/auth-rp/auth/user`.
- HTTPS gRPC-web gateway: `starlink.com/api/SpaceX.API.Device.Device/Handle`.
- Only `Starlink.Com.Sso` and `Starlink.Com.Access.V1` cookie names are retained. No arbitrary endpoint/headers are accepted; HTTP redirects and automatic transport retries are disabled.
- Router target: Request field 13; cloud read operations 1004, 3002, 3009. Every exchange rechecks the LAN router identity/network.
- Write: Request 3001 → WifiSetConfigRequest.wifi_config field 1. Only client_configs field 74 plus apply_client_configs field 1089=true are included. No SSIDs/passwords/DNS or other apply flags.
- Whole-list mutation: preserve all other client entries and unknown protobuf fields byte-for-byte. Reject ambiguous/missing IDs. Re-read the full list and revision before dispatch; abort on differences. This is a client-side race guard, not an atomic server transaction: concurrent changes from another app remain a limitation. Avoid using other router controls during the trial.
- Add the selected client's `_permanent` weekly schedule, range 0..10080. Reject any pre-existing schedule during pause preflight. Persist a minimal recovery journal before dispatch. On restore, re-read fresh configuration and remove only that selected permanent schedule if its shape still matches. Other client entries and schedules remain intact. Because `_permanent` is shared with the official app, a manually removed and recreated identical permanent schedule cannot be distinguished; do not alter that client's pause state in another app during the trial. Changed schedule shapes are refused and referred to official recovery.
- No mutation retries, no local-write fallback, no bulk stale snapshot restoration. Bounded post-write readback checks schedule, preservation of unrelated entries and explicit blocked state. Absent booleans stay unknown; accepted RPC does not equal working Internet control.
- Legacy 3.6.0 recovery remains readable and uses its original transport/unique marker. New UI tests use cloud mode.

## Security review (OWASP API 2023)

| Category | Implementation / residual limit |
| --- | --- |
| API1/API5 object/function authorization | Exact LAN and authenticated router identity match; server remains authority for account access. No account enumeration or arbitrary targets, reset/reboot calls. Actual write privileges need owner test. |
| API2 authentication | Owner logs into real Starlink HTTPS page. AES-GCM session file under noBackupFilesDir, key in Android Keystore. WebView cookies/storage cleared after login/close, excluded by existing backup allowlists. Local disconnect deletes saved session; it does not revoke all Starlink sessions or undo a pause. |
| API3 property authorization | Fixed minimal protobuf allowlist; all unrelated client fields preserved; no general config editor. |
| API4 resource use | 1 MiB replies, 256 KiB config, 512 clients, 16 KiB/client, network timeouts, 60-second linking / 90-second control budget; serialized operations. |
| API6 sensitive business flows | Manual selection, short-lived confirmation, no expiry automation, durable recovery. No active probing of third-party accounts. |
| API7 SSRF | Fixed HTTPS account endpoints; LAN fixed gateway; no UI URL/host input. Redirects disabled. |
| API8 configuration | System TLS validation; SSL failures cancel. WebView allows HTTPS Starlink main-frame destinations only, disables file/content access, mixed content, popup windows and permissions; no JS bridge or credential scraping. Login dialog blocks screenshots. |
| API9 inventory | Two cloud endpoints and documented protobuf subset; unofficial availability is a known dependency risk. |
| API10 external data | Bounded wire parser, strict status/identity checks, sanitized errors; no raw payloads, cookies, names or IDs in copied diagnostics. |

Automated evidence is fake-transport, parser, recovery, encryption-tamper and UI testing plus CI lint/build. No live credentials, physical router or real login session are available in the build environment. Third-party production scanning is neither required nor performed. Readback cannot prove Internet works; acceptance steps above are mandatory before claiming success.
