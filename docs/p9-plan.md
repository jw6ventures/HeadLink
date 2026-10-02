# P9 plan — live traffic — 28 September 2026

The terms questions and pricing in §1 were settled by James on 28 September 2026, and so
were all the decisions in §11.

**Status, 28 September 2026:** P9.1–P9.6 are built, host-tested and lab-verified (all five
`LAB_TRAFFIC` checks of §8 pass in the emulator). APP10 is amended in spec.md. P9.1's Test
button works on the Pixel with the real key (James). Not yet done: release-build timings
on the Pixel, and P9.7 in the truck. Records: `docs/p9-1-results.md`, `p9-2-results.md`,
`p9-5-routing-results.md`, `p9-6-dashboard-results.md`, `p9-4-5-runtime-results.md`,
`p9-lab-results.md`.

James wants to know about congestion **before arriving in it**, on the map, in the
arrival time and in the route. P9 adds live traffic from **HERE's Traffic API v7**,
reached over the network only when the operator turns it on and supplies their own HERE
key. It lifts the "Traffic … and anything needing a network" exclusion of P6 §0, and
needs APP10 amended (§2).

The source was chosen on 28 September 2026 after ruling out the alternatives:

| Source | Why not |
| --- | --- |
| TomTom Traffic API | Its terms (clause 7) forbid "Automotive Usage" and "Navigation Functionality" without a separate agreement, and define Automotive Usage to include screen replication, naming Ford Sync. Also, the free tier is for evaluation only. |
| RoadEagle / TraFF | Open and clean, but covers Poland and Lithuania only; closures, not congestion. Still worth doing later for TxDOT WZDx closures (§11). |
| Houston TranStar | Free, sensor speeds, but Houston freeways only. James is not in Houston. |
| Waze for Cities | Government partners only. |
| OpenTraffic | Defunct since 2018; never had Texas data. |

---

## 0. Scope, and what is deliberately outside it

In scope:

- An opt-in **Traffic** integration: off by default, operator's own HERE API key, a
  monthly request cap, and a status line saying what it has fetched.
- Fetching **flow** (speed and jam factor on each road stretch) and **incidents**
  (closures, crashes, roadworks) for the next ~30 km of the route being followed.
- **Colouring the route line** by congestion ahead on the projected map, with HERE's
  attribution.
- **Arrival time with traffic**: the delay HERE reports on the road still ahead, added to
  the estimate.
- **Routing around it**: closures as hard BRouter nogos, jams as weighted nogos, and a
  faster way round offered in a pop-up that divides the turn card, with the map zoomed
  out to show both routes.

Out of scope, stated so it is not assumed:

- **The HERE SDK** (Android navigation SDK). REST calls only: no new dependency, no SDK
  terms, no GMS (APP02).
- **Traffic with no route up.** Nothing is fetched while just driving (§4 explains why;
  decision 5).
- **Learning speeds from HERE data.** HERE's terms forbid predictive analytics and data
  extrapolation on its data (§1, 6.4 f). "Learn my pace" (`PaceTracker`) stays on the
  drive's own fixes only.
- **Sharing traffic with other apps** (a TraFF broadcast, say). HERE's terms forbid
  giving results to anyone else (6.4 h).
- **Committing captured HERE responses to the repo** (§1). Test fixtures are hand-written
  in HERE's shape.
- **Speed cameras, hazards, weather, parking.**

## 1. HERE's terms — what they allow, and the conditions they impose

Read on 28 September 2026 from the HERE Platform Terms (effective 18 September 2023,
legal.here.com/en-gb/terms/here-platform-terms) and the Base Plan usage restrictions
(here.com/get-started/pricing/base-plan-restrictions). I am not a lawyer; this is a
careful reading, not advice.

**What makes HERE possible where TomTom was not** — 6.3 a forbids integrating HERE
materials into a vehicle system or an in-vehicle display, then carves out projection:

> "For clarification, a mobile device that is connected to a vehicle for the purpose of
> projecting on the vehicle's windshield or display screen the visual information that is
> displayed on the mobile device screen is not an integration of HERE Materials"

**Combining with BRouter and OSM is allowed with attribution** — 6.4 a forbids using
non-HERE datasets with HERE results, except to (i) layer them for display and (ii)
"combine … for the purpose of delivering derived location responses, such as a route",
provided the origin of each can be told apart and attributed correctly. A BRouter route
weighted by HERE traffic, on a Mapsforge map, is case (ii); the overlay is case (i).

Conditions the design must meet:

| Clause | Says | P9's answer |
| --- | --- | --- |
| 6.4 a | Origins distinguishable, correct attribution | "Traffic © HERE" on the map whenever traffic is drawn or has shaped the route or time (§6). |
| 6.4 b | Don't expose HERE materials to open-source licences in a way that would force their disclosure or relicensing | HERE data is never written into the repo, the map files, the rd5 tiles, or anything we distribute. It lives in memory only. |
| 6.4 c, e | Don't modify HERE content; no derivative works | Displayed as received; turned into nogo costs and a delay, which are "derived location responses" under 6.4 a (ii). |
| 6.4 f | No ML/AI, "predictive analytics", "data extrapolation" | No learning from HERE data; `PaceTracker` untouched (§0). |
| 6.4 h | Don't provide results to another person or entity | No sharing, no TraFF broadcast, no fixtures from real responses in git. |
| 6.4 j; Exhibit 2 §1.3 | No storing beyond 30 days; no pre-fetching or caching except as the HTTP cache headers allow | Memory only. HERE sends no cache headers, so each response is used until the next replaces it (§4). Fetch only for the route being followed. |
| 6.2 | Make HERE's End User Terms and Privacy Policy available to users | Links on the Traffic settings card. |
| Exhibit 2 §1.2 | Holder of the key is liable for its use | The key is the operator's, entered on the phone, never in the APK or repo (§3). |

**Three readings were uncertain; James settled them on 28 September 2026.** HERE is not
being asked.

1. **Our projected display is not a mirror.** The carve-out says "the visual information
   that is displayed on the mobile device screen". Ours renders to a virtual display the
   phone's own screen does not show. **Decided: acceptable.** The intent, a phone
   projecting onto the car's screen, is what we do.
2. **The instrument cluster.** Guidance reaches Ford's cluster as navigation messages
   over the navigation channel (ch11), not as projected pixels. **Decided: not a
   concern.** James has never seen the cluster show a time estimate. Our ch11 messages
   do carry seconds remaining (`destination_seconds` in `core/aap-session/src/navigation.rs`;
   no arrival-time string), but SYNC 4 does not show them, so the cluster gets whatever
   the dashboard uses (§6).
3. **"Asset Management" is excluded from the Base Plan.** It includes "provision of route
   calculations" for an "Asset", and "Asset" includes "the user of your application" and
   "a vehicle … actively managed using HERE's service". **Decided: fine.** This is one
   person's own truck, not a fleet.

**Pricing, confirmed by James:** the Traffic API gives 5,000 free transactions, then
$2.50 per 1,000.

## 2. APP10, amended

Proposed text, for James to apply to spec.md (governed; not edited by this plan):

> Require no network access for the handshake or projection demonstration. From P5.5 on,
> map and routing data are likewise never fetched by the app; the operator supplies them
> (§2.2, §5.2). **Amended 28 September 2026:** the app declares `INTERNET` for one opt-in
> integration, live traffic (P9). It is off by default; with it off, the app makes no
> network connection. With it on, it contacts only the traffic provider's API host, with
> the operator's own key, only while a route is being followed, and stores nothing it
> receives beyond the response's cache lifetime. The integration is named in the APP09
> list with what it sends.

`INTERNET` is an install-time permission on Android and cannot be revoked by the user on
stock Android. On GrapheneOS the Network toggle can revoke it. The code-level guarantee is
that no socket opens unless the setting is on and a key is saved. A host test pins that
(§9).

## 3. The Traffic integration (settings card)

A new card under Routing, beside the route preferences:

- **Live traffic** — off (default) / on. Turning it on for the first time shows what it
  sends (below) and HERE's End User Terms and Privacy Policy links. It needs a key before
  it can be on.
- **HERE API key** — entered by hand or pasted; stored in the app's private preferences,
  excluded from Android backup. Shown masked. A **Test** button makes one incidents request
  for a small fixed box and says "works" or HERE's error.
- **Refresh every** — 5, 10 (default), 15 or 30 minutes (decision 2).
- **Look ahead** — how much of the route ahead to ask about: 15, 30 (default) or 50 km
  (decision 1, §4).
- **Monthly request limit** — default **4,500**, adjustable (decision 2). At the limit,
  fetching stops until the next calendar month and the card says so. The count is ours,
  kept by month, and counts only numbers, never areas.
- **Status** — last fetch time, requests this month, and the last error.

What it sends, as the card states it: "While a route is being followed, every 10 minutes
(or as set), the rectangle around the next 30 km of the route, and your key. That shows
HERE roughly where you are. HERE also sees your IP address and when you asked. It does not
receive your destination or the route itself."

## 4. What is fetched, and when

**Only while a route is being followed.** Fetching without a route would be pre-fetching,
which Exhibit 2 §1.3 forbids, and it would say where the truck is when no trip is planned.

**The area is a box around the next ~30 km of the route** (decision 1; the Look ahead
setting). It is the box around the route from the vehicle to 30 km along it (or to the
destination if nearer), padded by 2 km. It is one request each for flow and incidents,
because 30 km never nears HERE's box-size limit of **1° by 1°** (measured in P9.0: a larger box is refused with `400`, code `E608006`, "maximum
width and height of a bounding box can be 1 degree"). Each fetch asks for:

- `GET https://data.traffic.hereapi.com/v7/flow?in=bbox:W,S,E,N&locationReferencing=shape&functionalClasses=1,2,3`
- `GET https://data.traffic.hereapi.com/v7/incidents?in=bbox:W,S,E,N&locationReferencing=shape`

**What P9.0 measured** (Dallas, 28 September 2026, about 2 pm local, gzip on):

| Request | Results | Sent (gzip) | Raw JSON | Time |
| --- | --- | --- | --- | --- |
| Flow, 0.09° × 0.08° downtown box, all roads | 1,410 | not gzipped: 2.4 MB | 2.4 MB | 11.2 s |
| Flow, same box, classes 1–3 | 207 | 50 KB | 300 KB | 0.5 s |
| Incidents, same box | 45 | 89 KB, not gzipped | — | 0.5 s |
| Flow, full 1° DFW cell, classes 1–3 | 3,929 | 1.3 MB | 8.8 MB | 2.8 s |
| Incidents, full 1° DFW cell | 690 | 172 KB | 1.2 MB | 0.6 s |

- **`functionalClasses=1,2,3` is needed.** Without it the response is mostly class 4–5
  side streets (15,000 of 17,000 links in the small box), and 50 times the size.
- **A whole 1° cell is too much for a metro area.** 1.5 MB every 5 minutes is ~36 MB for a
  two-hour drive, and 10 MB of JSON to parse on the phone each time. A box that small
  shrinks the cost roughly in proportion to its area, so decision 1 now has to weigh data
  against privacy (§11).
- **There are no cache headers at all.** Neither response sends `Cache-Control` or
  `Expires`, so Exhibit 2 §1.3 allows no caching. Each response is used until the next
  one replaces it and is never kept beyond that. It is never reused for a different box.

**What the responses hold**, from the captures, not the documentation:

- Flow: `sourceUpdated`, then `results[]`, each with `location` (`description`, `length`
  in metres, `shape.links[]` of `points[{lat,lng}]`, `length` and `functionalClass`) and
  `currentFlow`:
  - `speed`, `speedUncapped` and `freeFlow`, all **in m/s**
  - `jamFactor` from 0 to 10
  - `confidence`
  - `traversability`: `open`, `closed`, or `reversibleNotRoutable`
  - sometimes `jamTendency` and `junctionTraversability`
  - `subSegments[]`, each with its own `length` and the same fields, on about 1 result
    in 8. They split one stretch into faster and slower parts, and matching must use them.
  - `speed` is missing on 27 of 1,410 results, all of them closed.
- Incidents: `results[]`, each with `location` in the same shape and `incidentDetails`:
  - `id`, `type` (seen: `roadClosure`, `construction`, `congestion`, `plannedEvent`)
  - `criticality` (seen: `critical`, `major`, `minor`), `roadClosed`
  - `startTime` and `endTime`, `description.value`, `summary.value`, `codes[]`
  - `junctionTraversability`
- Errors come in **two shapes**:
  - A bad request gives `{title, status, code, cause, action, correlationId}`.
  - A bad key gives `401` and `{error, error_description}`.

  The client reads both.

**Privacy, as decided:** each fetch shows HERE roughly where the truck is, within the
next 30 km. James chose this over a box around the whole route (which would show only the
trip's area) because the whole-route box costs ~1 GB of mobile data a month in a metro
area. A corridor along the route was rejected because it gives the route away.

**Data:** a 30 km box is about 0.3° square, roughly a tenth of the full DFW cell measured
above: on the order of 150 KB per refresh in Dallas, less outside cities. At 10 minutes
that is about 2 MB for a two-hour drive.

**Cadence:** a first fetch when a route starts being followed, then every 10 minutes by
default (decision 2), and one more after a re-route. Flow and incidents are two requests,
so a refresh costs 2: 12 an hour at 10 minutes. Two hours of driving every day is about
720 a month, well under the 4,500 cap. At 5 minutes it is about 1,440.

**Transport:** `HttpsURLConnection` with `Accept-Encoding: gzip` (flow is 6–7 times smaller
compressed), and `org.json`, both in the platform: no new library. Parsing streams the
response rather than holding 9 MB of text. The base URL is overridable in debug builds
only, for the lab's stand-in server (§8).

## 5. Matching HERE's roads to our route

HERE describes each stretch by its own geometry, and our route is BRouter's OSM
geometry. Matching is by shape, not by ID:

- For each route segment, find HERE flow stretches whose polyline passes within **15 m**
  and whose **heading agrees within 30°**. Direction matters: Texas interstates are two
  one-way carriageways 20–50 m apart, plus frontage roads alongside. Heading is what
  keeps a jam northbound off the southbound route and off the frontage road.
- A route segment takes the speed of the closest stretch that agrees. Where none does, it
  has no traffic, which is not the same as free-flowing. It is drawn as the route's
  normal colour.
- Incidents are matched the same way. An incident that matches no part of the route is
  kept for routing (§7), but not shown as "on your route".

Pure and host-tested, with a real Texas route from the rd5 tiles and a hand-written HERE
response laid along it, including a divided highway, a frontage road and an overpass.

## 6. On the map, and the arrival time

**Route colouring.** The route line ahead is drawn in segments: normal where HERE says
free-flowing or has no data, amber for slow (jam factor 4–8), red for queuing (above 8),
dark red and dashed for closed. It uses the existing `PathLayer`s, one per colour run. The
line behind the vehicle is unchanged.

**Attribution.** "Traffic © HERE" in the map's corner whenever a traffic colour is shown,
or when the arrival time includes traffic.

**Arrival time.** `Guide.remaining()` today is BRouter's seconds still to go, scaled by
"Learn my pace". With traffic, the remaining time becomes:

> paced(BRouter's time ahead) + Σ over matched stretches ahead of
> (length / HERE speed − BRouter's time for that stretch), counting only positive delays.

The route line shows it as "+N min traffic" after the arrival. `PaceTracker` keeps
measuring against BRouter's own estimate, so the pace factor and the traffic delay do not
count the same jam twice. Once the vehicle is in the jam, the pace factor sees it and the
jam falls behind.

**The cluster** gets the same seconds remaining as the dashboard, traffic included, so the
two never disagree. SYNC 4 has not been seen to display them anyway (§1, item 2).

## 7. Routing around it

BRouter already has what this needs, in the vendored tree unchanged:
`RoutingContext.nogopoints`, a list of `OsmNodeNamed` with a `radius` and a `nogoWeight`.

- A `nogoWeight` of **NaN is a hard block**. It is used for closures (`roadClosed`, or
  traversability `closed`).
- A finite weight adds **cost = distance inside the circle × weight**
  (`RoutingContext` line ~496), and is used for jams.

`RouteRequest` gains `nogos: List<Nogo>` (lat, lon, radius, weight or null for hard);
`Router.computeAt` hands them to `context.nogopoints`.

**Where the points go.** Point nogos, not polylines. A BRouter polyline nogo charges any
segment that *crosses* it, which would charge every road under an overpass and every
cross-street. Points are placed:

- along the jammed stretch every 150 m,
- **radius 8 m**, so a point on one carriageway does not reach the other or the frontage
  road,
- **not within 30 m of either end of a HERE stretch**, where junctions are, so a
  cross-street passing the end is not charged.

The lab and a host test both check an overpass and a frontage road (§8, §9).

**How heavy.** A jam's weight is set so the points on a stretch sum to roughly its delay
in BRouter cost. BRouter's cost is not seconds. P9.5 first measures `car-vario`'s cost per
second on real Texas routes (track cost over track time, by road class) and then fixes the
conversion, rather than guessing it.

**Finding the way round.** Each traffic update re-runs the alternatives search that
`LiveAlternatives` already does, now with the nogos. It compares each alternative against
the followed route *with* its traffic delay. An alternative is offered when it saves at
least 3 minutes and starts ahead of the vehicle. Nothing re-routes by itself (decision 3).
**A closure ahead on the route** is the exception: it starts a re-route at once, as leaving
the route does, because the route can no longer be driven.

**The offer** (decision 3, James, 28 September 2026):

- **The turn card at the top of the map divides in two.** The next turn keeps the left
  part: icon and distance, with the street name dropped if there is no room. The right
  part is the offer: "Faster: −7 min", the reason ("Queue on I-35E"), and two targets,
  **Take** and **Keep**. Guidance never disappears while the offer is up. Whether the
  split is side by side or stacked is for the lab render to settle (§11).
- **The map zooms out to show both routes whole:** the rest of the followed route and the
  proposed one, from the vehicle to the destination, with margins clear of the card. The
  proposal is drawn in the alternative style, and the jam that caused it is in its
  traffic colour. This uses `MapControl`'s existing "not following" state, so the
  camera code gains only a fit-to-bounds.
- **Take** switches to the new route, as tapping a live-alternative bubble does now.
  **Keep** dismisses the offer, and the same alternative is not offered again this trip
  unless its saving grows by 3 more minutes. Either way the card becomes whole again and
  the map returns to following.
- **The offer withdraws itself** when the vehicle passes the point where the new route
  leaves the old one, or when a later traffic update removes the saving. The map then
  returns to following.
- The existing "+N min" bubbles still show slower alternatives as now. The pop-up is only
  for a faster one found because of traffic.

**New routes** started while traffic is on include the nogos from the start.

## 8. The lab

- A stand-in HERE server on the lab host, serving hand-written flow and incident
  responses shaped like the real ones P9.0 captured. The debug build points at it (§4).
- **`LAB_TRAFFIC`** in `tools/emulator-lab.sh`: the lab route with a jam laid on a
  stretch ahead and a closure on a side road. It checks:
  1. the route line changes colour there,
  2. the arrival time grows by the jam's delay ± 1 min,
  3. the offer appears in the divided turn card, the map frames both routes, **Take**
     switches to the new route, and **Keep** returns the map to following,
  4. with the closure moved onto the route, a re-route follows,
  5. with the setting off, the lab host sees **no** connection from the app at all.
- The report gains `traffic_*` fields: enabled, cells, requests, last fetch, matched
  metres, delay, nogos, cap state.

## 9. Host tests

- The integration off, or on with no key, opens no connection. The HTTP layer is
  injected and must never be called.
- Parsing the hand-written flow and incidents responses, including missing optional
  fields and HERE's error body.
- No reuse: a response is used only for the box it was fetched for, and is replaced, not kept, by the next.
- Both error shapes (§4), and `speed` missing on closed stretches.
- Monthly cap: stops at the limit and resets on the 1st.
- Route boxes: padding, cell splitting at the size limit.
- Matching (§5): divided highway, frontage road, overpass, no-data stretch.
- Delay (§6): positive delays only; no double count with the pace factor.
- Nogo placement (§7): 8 m radius, 30 m end margin, 150 m spacing, hard vs weighted.

## 10. Slices

| Slice | What | Done when |
| --- | --- | --- |
| **P9.0** | James opens a HERE account and a key. Real flow and incident responses are captured for his area and **kept in `~/.cache`, not git**. The box-size limit is read from the account. | Fixtures are hand-written from what the captures showed. |
| **P9.1** | APP10 amendment applied; `INTERNET` declared; Traffic card; `TrafficClient` with key, cap, cache lifetime; APP09 entry. | Host tests in §9 for the client pass; the **Test** button works on the Pixel with the real key. |
| **P9.2** | Flow and incident parsing; route boxes; matching to the route. | Matching host tests pass on a real Texas route. |
| **P9.3** | Coloured route line, attribution. | `LAB_TRAFFIC` checks 1 and 5. |
| **P9.4** | Arrival time with traffic; the "+N min traffic" label. | `LAB_TRAFFIC` check 2; the lab receiver's ch11 seconds match the dashboard. |
| **P9.5** | Cost calibration; nogos in `RouteRequest`/`Router`; alternatives re-costed with traffic; re-route on a closure ahead. | `LAB_TRAFFIC` check 4; overpass and frontage host tests. |
| **P9.6** | The offer: divided turn card, Take/Keep, zoom to both routes, withdrawal. | `LAB_TRAFFIC` check 3, and lab renders of the divided card at the truck's pane size for James to judge. |
| **P9.7** | Pixel then truck: a real drive into real congestion with traffic on. | James sees the colour and time change before reaching a jam, and whether the offered way round was worth taking. |

## 11. Decisions — 28 September 2026

| # | Question | Decision |
| --- | --- | --- |
| 1 | What area to ask HERE for | **The next ~30 km of route** (§4). Look ahead is a setting: 15, 30 or 50 km. |
| 2 | Refresh interval and monthly cap | **Both settings in the app. Defaults: 10 minutes, 4,500 requests a month** (§3). |
| 3 | Re-route for a jam automatically, or offer it | **Offer it**, in a pop-up that divides the turn card at the top of the map, with the map zoomed out to show the rest of the route and the proposed one (§7). Closures on the route re-route automatically. |
| 4 | Traffic-adjusted time on the cluster | **Not applicable**: SYNC 4 does not show a time. The cluster gets the dashboard's figure (§6). |
| 5 | Traffic with no route up | **Not in scope.** |
| 6 | Colour all roads in view | **Scoped separately, not in this plan** (§13). |
| 7 | Plan numbering | **P9.** |

Still to settle during the work, not a blocker: whether the divided card is side by side
or stacked, from lab renders at the truck's pane size (P9.6).

## 12. Risks

- **HERE can change its terms and prices** on notice (8.2, 9.4). The card's status line
  shows HERE's errors plainly. With traffic off, everything works exactly as now.
- **Matching quality.** HERE's geometry and OSM's differ. The 15 m / 30° tolerances are
  first guesses for the lab and the drive to settle.
- **Nogo side effects.** A weighted nogo that reaches a road it should not charge will
  push routes oddly. The 8 m radius and end margins are the defence; the lab's overpass
  and frontage cases are the test.
- **Coverage is HERE's.** Rural Texas roads may have no flow data. "No data" is drawn as
  normal, never as free-flowing green.
- **Cost.** The 30 km box keeps each refresh to two requests on any route. Dense metro
  boxes are still the heaviest (§4). The cap is the backstop, and the card shows the
  count.

## 13. Later, not in P9

- **Traffic on all roads in view** (decision 6, scoped here only). Colour every main road
  on the map, not just the route. What it would take:
  - **Area:** the map's visible box, not the route's. At street zoom that is smaller than
    the 30 km box and adds nothing new; zoomed out, or with no route up, it is new
    requests. It is also *pre-fetching* under HERE's Exhibit 2 §1.3 unless it is fetched
    only for what is on screen, when it is on screen.
  - **Requests:** with a route up, reuse the 30 km box's response when the view lies
    inside it (it is the same response, used once, not a cache), and fetch only when the
    view leaves it. With no route up, it is a new source of requests and of position,
    which decision 5 put out of scope. Traffic with no route would have to be decided
    first.
  - **Drawing:** a `PathLayer` per colour for maybe 200–4,000 stretches (§4's counts), with
    free-flowing roads left undrawn so only slow and closed ones add layers. The pane
    already struggled with tile loads (the `MapReadGuard` work), so this needs a frame-time
    check on the Pixel.
  - **Size:** about one slice, after P9.7, if P9's route-only view proves not to be
    enough on real drives.

- **TxDOT WZDx closures** through a TraFF source (RoadEagle does not cover the US yet).
  Clean terms and no key, and they would also reach other open apps.
- **Learning typical speeds from the truck's own drives** (on the phone, never from HERE
  data), for the arrival time when traffic is off.

Sources for §1: HERE Platform Terms (legal.here.com/en-gb/terms/here-platform-terms), HERE
Base Plan usage restrictions (here.com/get-started/pricing/base-plan-restrictions), TomTom
Developer terms (2025 archive of developer.tomtom.com/terms-and-conditions).
