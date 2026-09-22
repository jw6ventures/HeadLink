Shared source-role session engine (sans-IO `Session`): version (`legacy-1.5`), TLS in
0x0003 messages, AUTH_COMPLETE, one encrypted service discovery request and validated
capabilities (`discovery`), keepalive, the bounded discovery-only hold, and shutdown,
with `stop_after` limits. With `stop_after=projection` it continues into the
demonstration (`projection`, `media`, `input` modules): channel opens on the discovered
video and input channels, AV setup, video focus, the start indication, encoder output
within the head unit's acknowledgement window with IDR-based congestion recovery, head-unit
touch and buttons mapped into the video content rectangle, an opt-in sensor subscription,
and a graceful stop. Also the ordered, cancellable `transport::run` poll loop for
pollable descriptors, and `accessory::run`, blocking reader/writer threads with
signal-based cancellation for the Android accessory descriptor (which cannot be polled).
Accessory cancellation is host-tested only; protocol behavior is tested against
../../tools/hu-sim and, in the lab, the Prodigy receiver. See ../../docs/references.md.
