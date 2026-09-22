# open-android-auto schema subset

Vendored unmodified from `mrmees/open-android-auto@61eab61c5f9968154ff1a80faa8c0a427b208479`
(the spec §3 pin): the transitive import closure of the messages this project decodes or
encodes — AUTH_COMPLETE, service discovery request/response, ping request/response,
shutdown request, ByeByeResponse, WireConfig, status and message-ID enums, and, for the
projection demonstration, channel open/close, AV setup/start/stop/ack, video focus, input
binding and events, the sensor subscription, now-playing, and the audio focus exchange.
123 `.proto` files; nothing else from that repository (no captures, documentation, or
decompiled material) is included.

License: GPL-3.0-or-later per the files' SPDX headers, and the repository's GPLv3
`LICENSE` copied here. `oaa/control/ChannelDescriptorData.proto` has no SPDX header and
is covered by that `LICENSE`. Upstream credits: open-android-auto contributors; original
aasdk definitions copyright (C) 2018 f1x.studio (Michal Szwaj), GPLv3.

The Rust types in `core/aap-proto/src/generated/` are produced from these files by
`tools/proto-gen.sh`; see `docs/licenses.md`. Byte-identical copies of this subset are in
the receiver's proto gitlink `open-android-auto@5ff4aa2`, so the lab receiver and the
source share the same schemas.
