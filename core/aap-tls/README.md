Source-role TLS (spec §7.3–7.4): a sans-IO TLS 1.2 server over memory buffers using the
pinned OpenSSL 3.5.8 LTS, linked statically (built by ../../tools/openssl-build.sh).
Profiles `tls12-aead` (baseline) and `tls12-cbc-compat` (evidence required); head-unit
certificates judged by SHA-256 pin or armed first use; independent ClientHello decoding;
alert capture and failure classification; record-boundary tracking; the generated
RSA-2048 source identity (`identity`); and an in-process self-test. Tests use plain
OpenSSL clients with their own identities. See ../../docs/references.md.
