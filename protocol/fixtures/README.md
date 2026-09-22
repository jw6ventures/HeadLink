Synthetic complete packets copied from spec.md §7.2, independently of the encoder. Hex text is read by tests; no vehicle captures or secrets. See ../../docs/licenses.md.

`ford-sync4-discovery-response.hex` is the one sanitized vehicle-derived fixture: the
decrypted ServiceDiscoveryResponse message (ID 0x0006 and protobuf, 581 bytes) from
report c2407cff (Ford SYNC 4.0, software 25221_PRODUCT.2025, 17 September 2026), captured
with the opt-in `capture_discovery` diagnostic. The car serial and vehicle ID
(`REDACTED-SERIAL0`) and the Bluetooth adapter and Wi-Fi BSSID addresses
(`00:00:00:00:00:00`) were replaced by placeholders of the same length, so every protobuf
length is unchanged. Nothing else was altered. It is a regression input for discovery
validation and projection selection, not evidence that any other unit behaves alike.
