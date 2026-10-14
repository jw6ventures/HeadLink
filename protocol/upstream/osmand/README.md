# OsmAnd OBF schema

`OBF.proto` is vendored **unmodified** from OsmAnd-resources:

- Repository: <https://github.com/osmandapp/OsmAnd-resources>
- Commit: `3e2f8efa3abb46af32da310fcfb666f1af8790b7` (2 October 2026); the file itself
  last changed in `0574222798fa28e1372887bdd488ef4718b9e05a` (18 July 2026)
- URL: <https://raw.githubusercontent.com/osmandapp/OsmAnd-resources/3e2f8efa3abb46af32da310fcfb666f1af8790b7/protos/OBF.proto>
- SHA-256: `804428791fe394c0d875de153523147d3dba1c1cc5ba85d958ade27ca090a7cb`

License: GPLv3, per the repository's `LICENSE` ("The code in that repository is mainly
covered by GPLv3"), which points to OsmAnd's own `LICENSE`. Copyright OsmAnd
(Amstelveen, Netherlands) and contributors.

**No code is generated from it.** OsmAnd frames its large messages with its own wire
type 6 (a 4-byte big-endian length, or 8 bytes when the top bit is set), which no protobuf
generator understands, and reads its `shiftTo…` "fixed32" offsets big-endian. The reader,
`app/.../ObfWire.kt`, `ObfFile.kt`, `ObfAddressReader.kt` and `ObfPoiReader.kt`, is
written by hand in Kotlin from this file and from OsmAnd's own Java reader
(`OsmAnd-java/.../binary/BinaryMapIndexReader.java`, `BinaryMapPoiReaderAdapter.java`,
`BinaryMapAddressReaderAdapter.java` at OsmAnd `b75433bc9d3c2a5db714b7a5315dccca47ed4603`),
which was read but not copied. The file is here as the reference the reader was written
against, and so a later change to the format shows up as a diff. Its field names and
comments are not evidence (docs/p11-2-3-results.md records where the files differ from
them: names are text tags, not fields 6 and 7).
