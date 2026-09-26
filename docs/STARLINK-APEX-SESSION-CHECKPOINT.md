# Checkpoint - Starlink apex session capture and real pause/restore

Branch: `agent/starlink-apex-session-v1` (branched from `agent/slotra-auth-control-v1` @ 71c2d61).
Do not merge to `main`. Resume from the tip of this branch.

## Root cause (confirmed in the code, not assumed)

Login inside the WebView really did succeed. The failure was entirely on our side:

- After login, Starlink puts the account session on the **apex** domain
  `starlink.com` itself, as `Starlink.Com.Sso` and `Starlink.Com.Access.V1`.
- `CloudPolicy.SESSION_COOKIE_URLS` listed only `www.`, `auth.`, `api.`, `api2.`,
  and `StarlinkAccountPanel.shouldInterceptRequest` gated capture on
  `host.endsWith(".starlink.com")`, which **excludes the apex**.
- Host-only cookies set on the apex are never returned by
  `CookieManager.getCookie()` for any subdomain URL, so every probe read `[NO]`
  and login reported `session_not_available`.

## Done on this branch

| Commit | Change |
| --- | --- |
| `8f83d62` | `CloudPolicy.ROOT = "https://starlink.com/"`; apex added **first** in `SESSION_COOKIE_URLS` (plus `https://starlink.com/account`); new `sessionHost(host)` accepting the apex and any `.starlink.com` subdomain, reused by `loginUrlAllowed`. |
| `a3936e9` | `StarlinkAccountPanel.shouldInterceptRequest` uses `sessionHost` instead of `endsWith(".starlink.com")`, so apex responses are scanned for `Set-Cookie`. Added tests for apex ordering and lookalike rejection. |
| `6545d76` | `CloudPolicy.HANDLE` moved from `api2.starlink.com` to the same-origin gateway `https://starlink.com/api/SpaceX.API.Device.Device/Handle`, matching the account page CSP (same-origin + `wifi.starlink.com` only). Gateway origin is now also a session origin, so no special case is needed. |

Verified by extracting the real `CloudPolicy` source and running it on the JVM
(26 assertions, all passing):

- apex is present and first in `SESSION_COOKIE_URLS`;
- `sessionHost` accepts `starlink.com`, `STARLINK.COM`, and all subdomains, and
  rejects `starlink.com.evil.test`, `evilstarlink.com`, `notstarlink.com`, `""`;
- `loginUrlAllowed` accepts the apex over https and rejects `http://`, embedded
  credentials, off-ports, `javascript:`, `file://`, and lookalike hosts;
- `cookies()` keeps only the two session cookie names and rejects CRLF injection;
- `sessionDiagnostics` reports presence flags only, never a cookie value;
- `target()` still admits only request codes 1004 / 3002 / 3009 / 3001 and still
  pins field 13 to the router id proven by the LAN read.

## Not done - needs the real hardware

The remaining items cannot be completed from here, because they require a phone
on the target router's Wi-Fi with a signed-in Starlink account. Nothing below
may be reported as done on the strength of a dispatched request: only a
read-back that shows the changed state counts.

1. **Confirm apex capture.** Sign in through the in-app WebView, then read the
   login diagnostics line. Expected: `starlink.com[SSO=YES ACCESS=YES]`. If the
   apex still shows `NO`, dump `CookieManager.getCookie("https://starlink.com/")`
   right after the final navigation to see whether the cookies exist at all.
2. **Confirm the gateway host.** `HANDLE` is now the same-origin path, chosen
   from the CSP, and is **not yet confirmed against a live account**. If
   `exchange` fails with `cloud_http_404` / `cloud_http_403`, capture the exact
   path the account page's own gRPC-Web call uses (DevTools / WebView request
   log) and set `HANDLE` to that, in its own commit.
3. **Real pause / restore cycle**, in this order, each step read back:
   - read router status (1004) and match the router id, cloud vs LAN;
   - PAUSE via 3001;
   - read back (3002 / 3009) and confirm the paused state, and confirm on the
     target device that internet access actually stops;
   - RESTORE via 3001;
   - read back and confirm the restored state, and confirm internet actually
     returns.

## Fixed constraints for whoever continues

- No credential or password scraping; no MFA / CAPTCHA / TLS bypass. The session
  is only ever taken from cookies the user's own successful login produced.
- "Request dispatched", HTTP 200 with a non-zero `grpc-status`, or any
  unsuccessful gRPC code is **not** success.
- Router identity matching (local field 13 against the router id from the read)
  stays exactly as it is.
- One commit per clear step; never merge into `main`.
