Test-only head-unit simulator (never packaged): version request, a TLS 1.2 client made
with plain OpenSSL and its own generated identity, AUTH_COMPLETE, a discovery response,
ping, shutdown, and the projection services (channel opens, AV setup, video focus, media
acknowledgements, input binding and events, sensors), with switchable misbehavior (missing
or premature auth, plaintext or absent discovery, TLS records split across frames, silence,
shutdown requests, refused or misrouted channel opens, withheld acknowledgements, a
head unit that only answers focus with NATIVE).
`serve` drives it over a blocking stream. It shares framing and generated message types
with the source, so the Prodigy lab receiver remains the independent peer.
