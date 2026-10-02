# P9.1 — Live traffic: the client, the settings card, and response parsing: implementation record — 28 September 2026

The first built slice of the [P9 plan](p9-plan.md) (§10): `INTERNET` declared for the one
opt-in integration (§2), the **Live traffic** card on Maps & routing (§3), and
`TrafficClient`, which gates, counts, sends and reads HERE's two requests (§4, §9). The
flow and incident parsing that §10 lists under P9.2 is here too, because the client cannot
return a `TrafficSnapshot` without it. Route boxes and matching (the rest of P9.2) are not.

Status: **implemented and host-tested; checked once against HERE with the real key from
the host JVM. Done: on 28 September 2026 James reported that the Traffic card's Test
button works on the Pixel with his real key**, which is the plan's "done when" for P9.1.
The emulator lab is in docs/p9-lab-results.md.

What the real-key check showed (§2.2):

- A fetch for a 0.05° box in downtown Dallas made exactly two requests, both answered
  `200` and gzipped, 49 KB on the wire together, in 1.2 s. It read 160 flow stretches
  (29 split into sub-segments, 6 closed spans without a speed) and 36 incidents, 21 of
  them closures.
- **Test** with a made-up key said `HERE 401: Unauthorized: apiKey invalid. apiKey not
  found.`, which is HERE's own text, in the second of the plan's two error shapes.
- HERE gzipped the small incidents response this time. P9.0 saw it plain. The client
  reads both.

---

## 1. What was built

### 1.1 `INTERNET`, and what guards it (plan §2)

- `AndroidManifest.xml` declares `android.permission.INTERNET`, with a comment giving
  APP10 as amended: one opt-in integration, off by default, no socket unless it is on with
  a key, HERE's host only, only while a route is followed. The three older comments that
  said "no INTERNET" now say what it is for and that maps and routing never use it.
- `tools/verify-apk.sh` now expects `INTERNET` in the exact permission list. Its other
  checks are unchanged. It gains three:
  - `allowBackup` is `false` and `dataExtractionRules` is set. These already held; they
    are pinned now because the HERE key must never be backed up.
  - `usesCleartextTraffic` is never set.
  - A build that is not debuggable carries no network security config.
- **Cleartext for the lab, in debug builds only.** `app/src/debug/AndroidManifest.xml`
  adds `networkSecurityConfig` to the debug build. `app/src/debug/res/xml/network_security_config.xml`
  permits cleartext to `10.0.2.2`, `127.0.0.1` and `localhost` only (the emulator's host
  alias and the address an `adb reverse` port arrives on). The release manifest has no
  config, so Android's default applies: no cleartext at all.

### 1.2 Settings and status (plan §3) — `TrafficSettings.kt`

| Setting | Default | Stored as |
| --- | --- | --- |
| Live traffic on | off | `traffic_enabled` |
| HERE API key | none | `traffic_api_key` |
| Refresh every | 10 min (5, 10, 15, 30) | `traffic_refresh_minutes` |
| Look ahead | 30 km (15, 30, 50) | `traffic_look_ahead_km` |
| Monthly request limit | 4,500 (2 to 1,000,000) | `traffic_monthly_limit` |
| What it sends has been shown | no | `traffic_disclosed` |
| Base URL override (lab only) | none | `traffic_base_url` |

- **`TrafficSettings`** is a pure data class, like `SavedSettings`: defaults,
  `repaired()`, `toStored()`/`fromStored()`. `repaired()` turns traffic **off whenever
  the key is blank**, so no stored state is on and keyless. It also clears a key that
  cannot be one (spaces, control characters). `toString()` says `apiKey=set`, never the
  key.
- **`TrafficStatus`** holds the month counted (`2026-09`), the requests in it, the last
  fetch's wall-clock time and the last error: numbers and HERE's error text only. It has
  no box, no position and nothing HERE returned. `requestsIn(month)` reads 0 once the
  month has passed, which is the reset on the 1st; nothing needs to run at midnight.
  `describe()` builds the card's Status lines, with "Limit reached until 1 October" at
  the cap.
- **`TrafficPrefs`** keeps both in a preferences file of their own, `traffic`, in the
  app's private storage. It is kept apart from `AppSettings`' `settings` file so the key
  never sits among values the report or the settings test read.
  - **Backup:** the file is excluded, as every file of the app already was.
    `allowBackup="false"` covers the old path. `data_extraction_rules.xml` excludes every
    domain from cloud backup and device transfer, which Android 12 and later use in place
    of `allowBackup`. The rules file's comment now names the key.
  - The count is written with `commit()`, before the request it counts is sent. If the
    write fails, the request is not sent.

### 1.3 The client (plan §4, §9) — `TrafficClient.kt`

- **`TrafficHttp`** is the injected HTTP layer: one `get(url, headers)`. The real one,
  `UrlConnectionHttp`, uses the platform's `HttpURLConnection`, with a 15 s connect
  timeout and a 30 s read timeout. It does not follow redirects (so a request cannot be
  handed on to another host) and uses no cache. It reads error bodies from the error
  stream, because `getInputStream` would throw with the URL, key included, in its
  message.
- **The gate**, checked before anything is built or sent: off → `Skipped(OFF)`; on with
  no usable key → `Skipped(NO_KEY)`; not enough requests left this month →
  `Skipped(LIMIT)`, with "Limit reached until 1 <next month>". Months are UTC calendar
  months (§4, item 4).
- **`fetch(box)`** makes exactly the plan's two GETs, flow first:
  - `…/v7/flow?in=bbox:W,S,E,N&locationReferencing=shape&functionalClasses=1,2,3&apiKey=…`
  - `…/v7/incidents?in=bbox:W,S,E,N&locationReferencing=shape&apiKey=…`
  - Both are sent with `Accept-Encoding: gzip`. A body is decompressed when its
    `Content-Encoding` says gzip or its first two bytes are gzip's magic. A stand-in
    server may not set the header.
  - A fetch starts only when there is room for both requests. Each is counted, and the
    count stored, just before it is sent.
  - If flow fails, incidents are not asked for.
  - The result is `Fetched(TrafficSnapshot(box, elapsedRealtime, flow, incidents))` for
    that box. The client keeps no field holding any response, so nothing can be reused
    for another box or kept past the next (plan §1, 6.4 j).
- **`test()`** is one incidents request for a fixed box of about 1 km in downtown Dallas.
  It is the same for everyone, so it says nothing about where the phone is. It returns
  "works" or the error.
- **Errors** become one short line (240 characters at most) for the status line.
  Anything that looks like the key, or any `apiKey=` parameter, is taken out first.
  - HERE's bad-request shape reads as `HERE 400: Malformed request: maximum width and height of a bounding box can be 1 degree (E608006)`.
  - The 401 shape reads as `HERE 401: Unauthorized: apiKey invalid. apiKey not found.`
  - A body of neither shape gives `HERE 503: no explanation given`.
  - No connection gives `Could not reach HERE: UnknownHostException: …`.
  - A body that is not JSON gives `HERE's answer could not be read (…)`.
- **Base URL override:** `TrafficSettings.baseUrl` is used only when the client is built
  with `debuggable = true`. `TrafficPrefs.client` passes the app's `FLAG_DEBUGGABLE`, so
  the release APK and `app-release-debugsigned.apk` always go to
  `https://data.traffic.hereapi.com`, whatever is stored.

### 1.4 Parsing (plan §4 "What the responses hold") — `TrafficJson.kt`

- **`JsonStream`** is a forward-only pull reader of about 250 lines in plain Kotlin. It
  works like `android.util.JsonReader` (`peek`, `beginObject`, `nextName`, `skipValue`,
  …). It reads straight off the decompressed response stream with an 8 KB buffer, and
  never holds the document (§4, item 1).
- **Flow:** one `FlowStretch` per result.
  - A stretch's `shape.links[].points` are joined in order, dropping the point each link
    repeats from the one before.
  - `functionalClass` is the lowest over the links, or 5 when none gives one.
  - `description` and `length` come from `location`. A missing `length` falls back to
    the sum of the links' lengths.
  - Spans:
    - A stretch without `subSegments` has one span over its whole length.
    - A stretch with them has one span per sub-segment, laid end to end by their own
      `length`s.
    - A sub-segment that leaves out `freeFlow`, `jamFactor` or `traversability` takes
      its stretch's. It never takes the stretch's `speed`, because HERE leaves speed out
      only where the road is closed.
    - A missing `speed` is null.
  - `traversability` reads `open`, `closed` and `reversibleNotRoutable`. A value HERE
    adds later reads as open, so an unknown word cannot close a road to routing.
  - A result with no geometry, or no `currentFlow`, is dropped.
- **Incidents:** `id` (falling back to `originalId`), `type`, `criticality` (or null),
  `roadClosed` (false if missing), and `summary.value`, else `description.value`, as
  `summary`, with the location joined as for flow. An incident with no geometry is
  dropped.
- Unknown fields, including nested objects and arrays, are skipped whole.

### 1.5 The card (plan §3) — `ConnectionActivity.kt`, `res/values/strings_traffic.xml`

A **Live traffic** card on Maps & routing, straight after Route preferences and before
Voice guidance. It is built from `PhoneViews` like its neighbours, and every row has its
ⓘ.

| Row | Control |
| --- | --- |
| caption | "Traffic from HERE, with your own HERE API key, while a route is being followed. With this off, the app makes no network connection at all." |
| **Use live traffic** | switch, ⓘ. Disabled until a key is saved. The first time it is turned on it stays off and shows **What live traffic sends**: the plan's paragraph word for word, then HERE's End User Terms and Privacy Policy as links. Only **Turn on** turns it on. |
| **HERE API key** | masked field (password input, no suggestions, not offered to autofill), **Save key**, **Remove key**, and a line under it: "A key is saved." / "No key saved. Save one to turn live traffic on." / "That is not a key: …". A saved key is never put back in the field or shown in any form. Removing it turns traffic off. |
| **Test** | button, ⓘ, enabled only with traffic on and a key. Runs `test()` on a `traffic-test` thread and shows "Test: works" or "Test: HERE 401: …" in a polite live region. |
| **Refresh every** | 5 / 10 / 15 / 30 min |
| **Look ahead** | 15 km (9 mi) / 30 km (19 mi) / 50 km (31 mi) |
| **Monthly request limit** | a number field, saved as typed whenever it reads as 2 to 1,000,000 (commas allowed). Otherwise it shows "Not saved: …" and the saved limit stands. |
| Status | "Last fetch: …", "Requests this month: 12 of 4,500", "Limit reached until 1 October" at the cap, "Last error: …". Re-read on every render, since the route's refreshes write it from another thread. |
| terms | HERE's End User Terms and Privacy Policy, linked, at the foot of the card (plan §1, 6.2). |

Strings are in a new `strings_traffic.xml`, so this slice does not share `strings.xml`'s
lines with the other P9 agents.

### 1.6 Lab extras (plan §8) — `ConnectionActivity.handleLabIntent`

These work in debug builds only. In a release build they are noted and ignored. They are
written to the `traffic` preferences, as `lab_favorites` writes the favourites, and stay
until changed:

| Extra | Type | Effect |
| --- | --- | --- |
| `lab_traffic` | boolean | on or off; on also marks the disclosure seen |
| `lab_traffic_url` | string | base URL, e.g. `http://10.0.2.2:8765`; `""` for HERE |
| `lab_traffic_key` | string | the key sent (the stand-in's, never a real one) |
| `lab_traffic_limit` | int | monthly request limit |
| `lab_traffic_refresh` | int | refresh every, minutes (5, 10, 15 or 30) |
| `lab_traffic_reset` | boolean | clears the month's count, last fetch and last error |

For example:

```
adb shell am start -n org.openautoandroid.source/.ConnectionActivity \
  --ez lab_traffic true --es lab_traffic_url http://10.0.2.2:8765 --es lab_traffic_key lab
```

### 1.7 APP09

The app has no separate in-app list of integrations. APP09's "offer integrations
individually" is met by each having its own control and ⓘ on the phone's tabs, as media
info, the cluster and the notification tile do. Live traffic is offered the same way: its
own card, what it sends stated on it and shown in full before it is first turned on. The
orchestrator's APP10 amendment says "named in the APP09 list with what it sends". If a
list is wanted, it needs a spec decision about where it lives.

---

## 2. Verification

### 2.1 Host test: `TrafficClientTest`, 146 checks

```
flock ~/.cache/openauto-android/p9-build.lock ~/.cache/openauto-android/p9/p9-1-quick.sh
jni-host-test: ok 146 traffic client and parsing checks
```

The scratch script compiles the release Kotlin, `javac`s every host test and runs this
one. The full `tools/android-build.sh` result is in §2.3. The test covers every plan §9
client item:

- **Off or keyless, no connection.** The integration off, off without a key, on with an
  empty key, on with a blank key, and the defaults: `fetch` and `test` each return
  Skipped for the right reason. The fake HTTP layer is never called, and nothing is
  counted.
- **Exactly the two requests.** Both URLs are checked character for character, and
  `Accept-Encoding: gzip` is sent. The snapshot carries the box asked for, two requests
  are counted, and the last fetch is recorded.
- **Counted before sent.** The fake reads the ledger at the moment of each send: 11, then
  12. A request whose connection fails is still counted, and incidents are not sent
  after it.
- **Parsing.** Hand-written flow and incident bodies in HERE's shape (plan §4), covering:
  - joins dropped and functional class minimum;
  - sub-segments laid end to end, a closed sub-segment with no speed, and inherited
    free flow and jam factor;
  - a closed stretch with no speed;
  - `reversibleNotRoutable`, and an unknown traversability read as open;
  - missing description, functional class, criticality and `roadClosed`;
  - summary preferred to description;
  - `\"` and `é` escapes;
  - unknown and nested fields skipped;
  - results without geometry or flow dropped;
  - a body cut short is an error.
- **Both error shapes**, as text and through a fetch and a Test. Also: a body of neither
  shape, a body that is not JSON, the last error recorded, and no key in any message,
  even when the transport's own exception message contains it.
- **gzip** read by `Content-Encoding`, by magic bytes alone, and plain.
- **Monthly cap.** Two fetches under a limit of 4, then the third refused with "Limit
  reached until 1 October" and nothing sent; Test refused too.
  - Still refused at 23:59:59 UTC on 30 September; fetching resumes at 00:00 on
    1 October, counting from 0.
  - December rolls into January.
  - With room for one request, Test goes and a fetch does not start.
- **No reuse.** A Dallas fetch, then an Austin one with a different answer: each snapshot
  has its own box and only its own data, and the earlier one is untouched. A later
  failure returns a failure, never the earlier answer.
- **Base URL override:** HERE's host in a non-debuggable client even with an override
  stored, and the stand-in in a debuggable one.

### 2.2 Real-key check (from the host JVM, not the phone)

A scratch harness, `~/.cache/openauto-android/p9/realkey/` (not in the repository),
called `TrafficClient` with the real `UrlConnectionHttp`, inside the Android image with
the network on. It read the key from `~/.config/openautoandroid/here-api-key` mounted
read-only, and printed only counts, sizes, timings and error text. **Three requests**
were made in total: one fetch (two requests) and one Test with a made-up key. The
results are at the top of this record. Nothing from them is in the repository.

### 2.3 Full build

See §5: the APKs, lint and `verify-apk.sh` pass. The host tests stop at `javac` on other
agents' non-ASCII test sources. With UTF-8 source encoding, they all pass.

---

## 3. Not verified

- **The Pixel.** James reported on 28 September 2026 that the Test button works on the
  phone with his real key (P9.1's "done when"). That is his word, not a recorded
  capture. Nothing was reported about the card's layout at 360 dp, the disclosure dialog
  or the links opening a browser.
- **`HttpURLConnection` on Android** has not been run. The host JVM's implementation is
  not Android's. In particular, it is assumed that Android does not decompress
  transparently when the app sets `Accept-Encoding` itself (it does not in OkHttp-based
  `HttpURLConnection`), and the magic-byte check covers either case.
- **The debug network security config** has not been exercised against a stand-in
  server; that is the lab agent's `LAB_TRAFFIC` run.
- **HERE's legal URLs.** The card links
  `https://legal.here.com/us-en/terms/here-end-user-terms` and
  `https://legal.here.com/us-en/privacy`. On 28 September 2026 both answered `301` to
  `https://www.here.com/en-us/terms/here-end-user-terms` and
  `https://www.here.com/en-us/privacy`, and both of those answered `200`. The pages are
  script-rendered, so their titles could not be read with `curl`. The URLs are the ones
  HERE's own search listing gives for the End User Terms and the Privacy Policy.
- **Month boundary.** Whether HERE's billing month is UTC is an assumption (§4, item 4).

---

## 4. Deviations from the plan, and choices it left open

1. **No org.json (plan §4 "Transport").** A hand-written streaming reader, `JsonStream`,
   instead, for two reasons:
   - org.json would hold a 9 MB flow body as a string and then a tree, where the plan
     asks for streaming;
   - neither org.json nor `android.util.JsonReader` is on the host tests' classpath, so
     the parsing could not have been host-tested.
   It is still no new library.
2. **`HttpURLConnection`, not `HttpsURLConnection`.** Same platform class family. The
   plain type is what lets the debug build reach the lab's HTTP stand-in. The release
   build can only use HTTPS: its base URL is fixed to HERE's, and it allows no
   cleartext.
3. **Test only with traffic on.** Plan §2's code-level guarantee is that no socket opens
   unless the setting is on and a key is saved. The Test button is held to the same
   rule rather than being an exception to it. The flow on the card is: save the key,
   turn it on (which shows the disclosure the first time), then Test.
4. **Months are UTC calendar months.** HERE presumably bills by UTC month. Counting by
   the phone's zone could let the same evening's requests fall into both HERE's old
   month and our new one. In Texas the count therefore resets at 7 pm CDT on the last
   day of the month. The card still says "until 1 October".
5. **A fetch needs room for both requests.** At limit − 1 a fetch does not start: half
   a snapshot is no use to matching. Test, which needs one, still may.
6. **Every sent request is counted, including one that fails.** HERE may have counted
   it too, and the cap is a cost backstop.
7. **The key is never shown after saving, not even its last characters.** Plan §3 says
   "shown masked". The field is masked while typing, and afterwards the card says only
   whether a key is saved.
8. **The lab extras persist** (§1.6), as `lab_favorites` does. This is a debug build on
   the emulator only; a release build ignores them.
9. **Sub-segment inheritance** (§1.4) is a reading of the plan, not something it
   states: sub-segments take their stretch's free flow, jam factor and traversability
   when they leave them out, and never its speed.

---

## 5. Build

`flock ~/.cache/openauto-android/p9-build.lock tools/android-build.sh` on 28 September 2026:

- **Passed:**
  - the Gradle build of the debug and release APKs;
  - `lintDebug`, with warnings as errors;
  - `verify-apk.sh` on all three APKs, with the new permission set, backup and cleartext
    checks.
  `aapt2` shows the debug APK carrying `networkSecurityConfig` and the release APKs
  without it. The first run failed lint once, on `SetTextI18n`
  (`monthlyLimit.toString()` in `setText`). The field now gets `String.format(Locale.ROOT, "%d", …)`.
- **Stopped at the host tests' `javac`, in files this slice does not own.**
  `jni-host-test.sh` compiles with the image's default encoding, US-ASCII. The other P9
  tests (`TrafficMatchTest`, `NogoRoutingTest`, `DashboardTrafficTest`,
  `TrafficFeedTest`, placeholders included) contain `§`, `°` and `−`, which are 51
  "unmappable character" errors. Every earlier test file is pure ASCII.
  `TrafficClientTest` was made ASCII to match. The fix belongs to the orchestrator:
  either `javac -encoding UTF-8` in `jni-host-test.sh`, or ASCII-only test sources.
- **With `-encoding UTF-8` added** in a scratch copy of the script
  (`~/.cache/openauto-android/p9/jni-host-test-utf8.sh`, same container, same classes),
  every host test passed. That includes `TrafficClientTest` (146 checks),
  `TrafficMatchTest` (227), `AppSettingsTest` (2,646) and the JNI contract test.
