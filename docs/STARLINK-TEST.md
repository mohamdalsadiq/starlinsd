# Slotra 3.5.0 — read-only local Starlink experiment

The owner cancelled commercialization and requested a personal-use test of router client discovery. No advertising, payment, cloud account or router mutation is included.

## Verified protocol sources

These are community reverse-engineered wire definitions, not a supported Starlink public API. Hardware/firmware compatibility is unknown until the owner runs the APK on the actual LAN.

- https://github.com/starlink-community/starlink-cli/blob/main/README.md documents router `192.168.1.1:9000` and dish `192.168.100.1:9200`.
- https://github.com/Eitol/starlink-client/blob/a9164c8cf24774c1137d9c28680d8247bf6849eb/proto/spacex/api/device/device.proto verifies Request.get_status=1004, Request.wifi_get_clients=3002; Response.status=2, dish_get_status=2004, wifi_get_clients=3002, wifi_get_status=3004.
- https://github.com/Eitol/starlink-client/blob/a9164c8cf24774c1137d9c28680d8247bf6849eb/proto/spacex/api/device/wifi.proto verifies clients field 1, WifiClient name=1, mac=2, ip=3, given_name=31, active=58. Modern WifiGetStatus clients=3000; historical field 2 from starlink-community/starlink-grpc-go wifi.pb.go is also accepted.
- https://github.com/grpc/grpc/blob/master/doc/PROTOCOL-HTTP2.md and PROTOCOL-WEB.md specify framing and status handling.

## Scope and safeguards

- More > اختبار Starlink starts a bounded manual test. No startup/background scans.
- Bind every socket to a currently available non-VPN Wi-Fi network. No fallback to cellular, proxy, cloud, arbitrary host or subnet ping.
- Only two fixed local endpoints. Test native HTTP/2 gRPC on router port 9000, then gRPC-Web HTTP/1 on the SAME port as an explicitly experimental compatibility attempt. Never claim the fallback is firmware-supported before a real reply. Dish status is an independent native gRPC check on 9200.
- At most five calls, six-second call deadlines, cancellable calls, one-MiB response cap, bounded fields/client counts. Leaving the screen cancels the coroutine; rotating the screen resets this disposable test. Cleartext permitted only for the two literal IPs; redirects/retries disabled.
- Reuses OkHttp 4.10.0 already declared in the dependency catalog. No generated protobuf/gRPC runtime needed for these two tiny read messages. Strict parser rejects invalid frames/status, preserves unknown-field compatibility, distinguishes router error, missing list and explicit empty list.
- Router entries can include historic clients or mesh nodes. Unknown active status remains unknown, not fabricated as online. The screen states snapshot timing and lack of completeness guarantees. No automatic linking to subscribers, timers or revenue.
- Router status may contain sensitive settings; raw replies/config/serial IDs are never logged, persisted, exported or displayed. Only allowed device display fields and hardware/software descriptions are decoded. Copied diagnostics exclude client identifiers and raw messages.
- Room schema 6 and all business data stay unchanged. Existing signed release identity retained, versionCode 10.

## Owner test

Connect Samsung A23 directly to the main Starlink Wi-Fi (not guest/repeater/third-party router), open More > اختبار Starlink, press بدء اختبار القراءة. Compare received entries with the official Starlink app at the same time. Copy the diagnostic summary for follow-up. Permission denial, timeout or successful dish status alone are not evidence that router clients are readable or absent.

If this firmware requires authenticated/cloud access, this experiment reports failure and does not attempt to bypass authentication. No account password is requested.
