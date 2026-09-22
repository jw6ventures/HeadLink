# BRouter, vendored

Offline routing engine for P6.3. **Vendored source, held unmodified.** See
[docs/p6-plan.md](../../docs/p6-plan.md) §4 for the decision and the alternatives that
were rejected.

## Pin

| | |
| --- | --- |
| Upstream | https://github.com/abrensch/brouter |
| Commit | `4d2639af77ea5ed9c30d3e400764eb6f9e8522da` |
| Tag | `v1.7.10`, 17 July 2026 |
| Licence | MIT (`LICENSE` in this directory, copied verbatim) |
| Fetched | `codeload.github.com` tar.gz of that commit, SHA-256 `6009528b1fea5bcdcd7c490f3403c43e03c39d4526e4a80d6a2e721a1a0b1fee`, 20 September 2026 |

The tarball checksum records how this tree was obtained; it is not a stable identifier,
because GitHub does not guarantee byte-identical archive generation over time. The commit
SHA is the pin. The authoritative copy of what is vendored is this directory.

## Why vendored rather than resolved

BRouter publishes **no Maven Central artifact at all** — a search returns nothing, and
`btools/`, `com/github/abrensch/` and `org/btools/` are all 404 under `repo1.maven.org`.
Distribution is a GitHub release zip plus JitPack. P5.5's answer for VTM — pin the last
Maven Central release and let `--write-verification-metadata sha256` handle it — has no
equivalent here, so there is nothing for `gradle/verification-metadata.xml` to pin.

Vendoring the source is the only one of the three options where "the build is reproducible
offline" needs no argument. MIT into this GPL-3.0-only work is allowed.

## What was taken

The five runtime modules, complete and unmodified — **101 files, 603,183 bytes**:

| Upstream path | Here | Files |
| --- | --- | --- |
| `brouter-core/src/main/java/btools/router` | `src/main/java/btools/router` | 33 |
| `brouter-mapaccess/src/main/java/btools/mapaccess` | `src/main/java/btools/mapaccess` | 20 |
| `brouter-util/src/main/java/btools/util` | `src/main/java/btools/util` | 27 |
| `brouter-expressions/src/main/java/btools/expressions` | `src/main/java/btools/expressions` | 10 |
| `brouter-codec/src/main/java/btools/codec` | `src/main/java/btools/codec` | 11 |

Five Gradle modules collapse into one here because the split buys nothing in a single
consuming application, and because keeping one source set makes a re-sync a directory
copy rather than a merge.

**Not taken**, because none of it runs on the phone: `brouter-routing-app` (upstream's own
Android application), `brouter-map-creator` (offline `.rd5` production), `brouter-server`
(the HTTP service), `buildSrc`, `misc` beyond the two profile files below, and every
`src/test` tree.

### Profile data

`src/main/resources/btools/profiles/` holds two files from upstream `misc/profiles2`,
byte-identical to their originals:

- `lookups.dat` (31,604 bytes) — the tag dictionary every profile is evaluated against.
  Routing cannot start without it.
- `car-vario.brf` (11,891 bytes) — the car profile. BRouter's twenty-one profiles are
  mostly bicycle and foot; **this one is not yet validated for turn-by-turn** and that is
  an open question in the plan.

They are **relocated** from `misc/profiles2` into the library's resources so they travel
with the code through the classloader, the same route `MapAssets` uses for VTM's shaders.
The relocation is the only change: no byte of either file differs.

## No modifications

Nothing in `src/main/java` is edited. Re-syncing to a later BRouter is a delete and a
re-copy of the five directories, followed by the checks below.

Two consequences of holding it unmodified, both deliberate:

- `Rd5DiffTool` and `StackSampler` carry `main()` methods, and `Rd5DiffTool` calls
  `System.exit(1)`. They are command-line tools that happen to live in runtime modules.
  Nothing in the routing path reaches them, and deleting them would be a modification
  that a re-sync would have to repeat.
- Warnings from this tree are suppressed in `build.gradle.kts` rather than fixed.

## Constraints on the consuming build

- **`Class.forName` is load-bearing.** `RoutingContext.setModel` instantiates the path
  model named as a string in the `.brf` profile (`btools.router.KinematicModel` and
  friends). The app sets `isMinifyEnabled = false`, so this works today. **If minification
  is ever turned on, `btools.router.*Model` and `OsmPathModel` subclasses need keep
  rules**, and the failure mode is a `RuntimeException("Cannot create path-model: …")` at
  the first route rather than a build error.
- **No Android API may enter this tree.** It is compiled as a plain `java-library`
  precisely so that one would fail here rather than on a device. Verified at vendoring
  time: the whole tree imports nothing but `btools.*` and `java.*` — no `javax`, no
  `java.nio.file`, no AWT.
- **No native library and no new ABI.** BRouter is pure Java; `verify-apk.sh`'s native set
  is unchanged.
- **Still no `INTERNET` permission.** `.rd5` segment files are operator-supplied into
  app-private storage exactly as `.map` files are.
