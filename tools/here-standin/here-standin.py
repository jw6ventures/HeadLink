#!/usr/bin/env python3
"""A stand-in for HERE's Traffic API v7 and TomTom's Orbis traffic API, for the emulator lab's
LAB_TRAFFIC runs (P9).

It answers exactly the two requests the app's TrafficClient makes of HERE:

    GET /v7/flow?in=bbox:W,S,E,N&locationReferencing=shape&functionalClasses=1,2,3&apiKey=K
    GET /v7/incidents?in=bbox:W,S,E,N&locationReferencing=shape&apiKey=K

with the hand-written responses of one scenario directory (flow.json, incidents.json,
written by tools/jni-host-test/LabTraffic.java in HERE's shape; nothing in them came from
HERE). Which scenario is served is read from the control file at every request, so the lab
can move the closure onto the route while the app runs. Anything else gets one of HERE's
two error shapes: a wrong key is 401 with {error, error_description}; a malformed request is
400 with {title, status, code, cause, action, correlationId}. Bodies are gzipped when the
request accepts it, as HERE's flow is, so the app's gzip path runs.

It also answers the two requests the app's TomTomClient makes of TomTom, the key, version and
attributes in headers (TomTom-Api-Key, TomTom-Api-Version: 2, Attributes):

    GET /maps/orbis/traffic/flow/vector/tile/12/X/Y
    GET /maps/orbis/traffic/incidents/details?bbox=W,S,E,N&timeValidity=present

converting the same scenario's HERE files on each request: the flow into a Mapbox Vector Tile
(layer "Traffic flow", one linestring per HERE result, with road_category, relative_speed,
absolute_speed and road_closure), the incidents into TomTom's {"incidents": [...]} GeoJSON.
A wrong key is 403 and a malformed request 400, both {"detailedError": {code, message}}.
An empty --tomtom-key refuses every TomTom request.

It counts every TCP connection it accepts, not only requests: the check that the app makes
no connection at all with traffic off is the connection count staying 0.
The counts go to <state>/stats.txt after every event, and one line per request to
<state>/requests.log, the key replaced by its length.

    here-standin.py --port 8765 --scenarios <dir> --state <dir> --key <key> [--tomtom-key <key>]
        [--scenario a]

Python's standard library only; it runs in the emulator container, inside the lab pod whose
only interface is loopback, where the emulator reaches it as http://10.0.2.2:<port>.
"""
import argparse
import gzip
import http.server
import json
import math
import pathlib
import re
import socketserver
import struct
import sys
import threading
import time
import urllib.parse

BBOX = re.compile(r"^bbox:(-?\d+(\.\d+)?),(-?\d+(\.\d+)?),(-?\d+(\.\d+)?),(-?\d+(\.\d+)?)$")

# TomTom: the app's TomTomClient constants, copied. Every request must carry the Attributes
# header exactly as TomTomClient.FLOW_ATTRIBUTES (tiles) or TomTomClient.INCIDENT_ATTRIBUTES
# (incidents) spells it, and tiles are asked for at TomTomClient.FLOW_ZOOM only.
TOMTOM_PREFIX = "/maps/orbis/traffic/"
TOMTOM_TILES = "/maps/orbis/traffic/flow/vector/tile/"
TOMTOM_INCIDENTS = "/maps/orbis/traffic/incidents/details"
FLOW_ATTRIBUTES = ("tags(road_category,relative_speed,absolute_speed,road_closure),"
                   "roadCategories(motorway,motorway_link,trunk,trunk_link,primary,primary_link,"
                   "secondary,secondary_link)")
INCIDENT_ATTRIBUTES = ("incidents(type,geometry(type,coordinates),"
                       "properties(id,iconCategory,magnitudeOfDelay,events(description)))")
FLOW_ZOOM = 12
MAX_INCIDENT_KM2 = 10_000.0  # TomTom's own bound; the app keeps under 9,000
LAYER = "Traffic flow"
EXTENT = 4096
MARGIN = 410  # TomTom's 10% margin round each tile; the app clips it off
CATEGORIES = {1: "motorway", 2: "trunk", 3: "primary", 4: "secondary"}
REQUESTED = set(re.search(r"roadCategories\(([^)]*)\)", FLOW_ATTRIBUTES).group(1).split(","))
ICONS = {"construction": "roadWorks", "congestion": "jam", "accident": "accident"}
MAGNITUDES = {"critical": "major", "major": "moderate", "minor": "minor"}


def area_km2(w: float, s: float, e: float, n: float) -> float:
    """The app's TomTomClient.incidentBoxes area: the box's width at its equatorward edge."""
    equatorward = 0.0 if s < 0 < n else min(abs(s), abs(n))
    return (n - s) * 111.195 * (e - w) * 111.195 * math.cos(math.radians(equatorward))


def bbox_problem(box: str):
    """What is wrong with an incidents bbox (minLon,minLat,maxLon,maxLat), or None."""
    try:
        w, s, e, n = (float(v) for v in box.split(","))
    except ValueError:
        return "bbox must be four numbers, minLon,minLat,maxLon,maxLat"
    if not all(math.isfinite(v) for v in (w, s, e, n)):
        return "bbox must be finite"
    if not (-180 <= w <= 180 and -180 <= e <= 180 and -90 <= s <= 90 and -90 <= n <= 90):
        return "bbox must lie within longitude -180..180 and latitude -90..90"
    if not (w < e and s < n):
        return "bbox minimum must be below its maximum"
    if area_km2(w, s, e, n) > MAX_INCIDENT_KM2:
        return "bbox area must be at most 10000 km2"
    return None


def joined(location: dict) -> list:
    """A HERE location's points, its links joined, the point each join repeats dropped."""
    points = []
    for link in location.get("shape", {}).get("links", []):
        for p in link.get("points", []):
            point = (p["lat"], p["lng"])
            if not points or points[-1] != point:
                points.append(point)
    return points


def varint(v: int) -> bytes:
    out = bytearray()
    while True:
        b = v & 0x7F
        v >>= 7
        if v:
            out.append(b | 0x80)
        else:
            out.append(b)
            return bytes(out)


def field(number: int, wire: int) -> bytes:
    return varint(number << 3 | wire)


def delimited(number: int, payload: bytes) -> bytes:
    return field(number, 2) + varint(len(payload)) + payload


def zigzag(v: int) -> int:
    return v << 1 if v >= 0 else (-v << 1) - 1


def value(v) -> bytes:
    """An MVT Value: string=1, double=3, bool=7."""
    if isinstance(v, bool):
        return field(7, 0) + varint(int(v))
    if isinstance(v, float):
        return field(3, 1) + struct.pack("<d", v)
    return delimited(1, v.encode())


def pixel(lat: float, lon: float, x: int, y: int) -> tuple:
    """Web Mercator at FLOW_ZOOM into tile (x, y)'s units, extent 4096, rounded."""
    n = 1 << FLOW_ZOOM
    phi = math.radians(max(-85.05112878, min(85.05112878, lat)))
    wx = (lon + 180.0) / 360.0 * n
    wy = (1.0 - math.log(math.tan(phi) + 1.0 / math.cos(phi)) / math.pi) / 2.0 * n
    return round((wx - x) * EXTENT), round((wy - y) * EXTENT)


def flow_tile(flow: dict, x: int, y: int) -> bytes:
    """A scenario's HERE flow as TomTom's vector tile (x, y): one feature per HERE result that
    reaches the tile with its margin, every point of it encoded (the app clips)."""
    keys, values, features = {}, {}, []

    def index(table: dict, k) -> int:
        return table.setdefault(k, len(table))

    for result in flow.get("results", []):
        figures = result.get("currentFlow", {})
        if figures.get("traversability") == "reversibleNotRoutable":
            continue
        location = result.get("location", {})
        classes = [link.get("functionalClass", 5) for link in location.get("shape", {}).get("links", [])]
        category = CATEGORIES.get(min(classes, default=5), "street")
        if category not in REQUESTED:
            continue
        line = []
        for lat, lon in joined(location):
            point = pixel(lat, lon, x, y)
            if not line or line[-1] != point:
                line.append(point)
        if len(line) < 2:
            continue
        xs, ys = [p[0] for p in line], [p[1] for p in line]
        if max(xs) < -MARGIN or min(xs) > EXTENT + MARGIN or max(ys) < -MARGIN or min(ys) > EXTENT + MARGIN:
            continue
        tags = {"road_category": category}
        speed, free = figures.get("speed"), figures.get("freeFlow")
        if speed is not None:
            if free:
                tags["relative_speed"] = float(min(1.0, speed / free))
            tags["absolute_speed"] = float(speed * 3.6)
        tags["road_closure"] = figures.get("traversability") == "closed"
        packed = b"".join(varint(index(keys, k)) + varint(index(values, (type(v).__name__, v)))
                          for k, v in tags.items())
        geometry = [1 | 1 << 3, zigzag(line[0][0]), zigzag(line[0][1]), 2 | (len(line) - 1) << 3]
        for (px, py), (qx, qy) in zip(line, line[1:]):
            geometry += [zigzag(qx - px), zigzag(qy - py)]
        features.append(delimited(2, packed) + field(3, 0) + varint(2)
                        + delimited(4, b"".join(varint(g) for g in geometry)))
    layer = (field(15, 0) + varint(2) + delimited(1, LAYER.encode())
             + b"".join(delimited(2, f) for f in features)
             + b"".join(delimited(3, k.encode()) for k in keys)
             + b"".join(delimited(4, value(v)) for _, v in values)
             + field(5, 0) + varint(EXTENT))
    return delimited(3, layer)


def tomtom_incidents(incidents: dict) -> dict:
    """A scenario's HERE incidents in TomTom's Incident Details shape."""
    out = []
    for result in incidents.get("results", []):
        details = result.get("incidentDetails", {})
        points = [[lon, lat] for lat, lon in joined(result.get("location", {}))]
        if not points:
            continue
        geometry = ({"type": "Point", "coordinates": points[0]} if len(points) == 1
                    else {"type": "LineString", "coordinates": points})
        kind = details.get("type")
        icon = ("roadClosed" if details.get("roadClosed") or kind == "roadClosure"
                else ICONS.get(kind, "unknown"))
        summary = (details.get("summary") or {}).get("value") or ""
        description = (details.get("description") or {}).get("value") or ""
        text = summary if summary.strip() else description
        out.append({"type": "Feature", "geometry": geometry, "properties": {
            "id": details.get("id", ""),
            "iconCategory": icon,
            "magnitudeOfDelay": MAGNITUDES.get(details.get("criticality"), "unknown"),
            "events": [{"description": text, "code": 0, "iconCategory": icon}],
        }})
    return {"incidents": out}


class Stats:
    def __init__(self, state: pathlib.Path):
        self.state = state
        self.lock = threading.Lock()
        self.counts = {"connections": 0, "requests": 0, "flow": 0, "incidents": 0, "refused": 0,
                       "tomtom_tiles": 0, "tomtom_incidents": 0}

    def bump(self, *names: str) -> None:
        with self.lock:
            for name in names:
                self.counts[name] += 1
            text = " ".join(f"standin_{k}={v}" for k, v in self.counts.items())
            (self.state / "stats.txt").write_text(text + "\n")

    def log(self, line: str) -> None:
        with self.lock:
            with (self.state / "requests.log").open("a") as out:
                out.write(line + "\n")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--scenarios", required=True, type=pathlib.Path)
    parser.add_argument("--state", required=True, type=pathlib.Path)
    parser.add_argument("--key", required=True)
    parser.add_argument("--tomtom-key", default="", help="TomTom's key; empty refuses every TomTom request")
    parser.add_argument("--scenario", default="a", help="served until <state>/scenario says otherwise")
    args = parser.parse_args()
    args.state.mkdir(parents=True, exist_ok=True)
    control = args.state / "scenario"
    if not control.exists():
        control.write_text(args.scenario + "\n")
    stats = Stats(args.state)
    stats.bump()  # writes the zero counts, so "no connection" is a file saying 0, not a missing one
    started = time.monotonic()

    class Handler(http.server.BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"
        server_version = "here-standin/1"

        def log_message(self, fmt, *a):  # the request log below replaces stderr noise
            pass

        def send_json(self, status: int, body: bytes, scenario: str, what: str,
                      content_type: str = "application/json") -> None:
            gz = "gzip" in (self.headers.get("Accept-Encoding") or "")
            payload = gzip.compress(body) if gz else body
            self.send_response(status)
            self.send_header("Content-Type", content_type)
            if gz:
                self.send_header("Content-Encoding", "gzip")
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)
            query = re.sub(r"apiKey=[^&]*", lambda m: f"apiKey=<{len(m.group(0)) - 7} chars>", self.path)
            stats.log(f"t={time.monotonic() - started:.1f}s {what} status={status} scenario={scenario} "
                      f"bytes={len(payload)} gzip={gz} {query}")

        def do_GET(self):  # noqa: N802 (the standard library's name)
            stats.bump("requests")
            url = urllib.parse.urlsplit(self.path)
            query = urllib.parse.parse_qs(url.query)
            scenario = control.read_text().strip() or args.scenario
            if url.path.startswith(TOMTOM_PREFIX):
                self.tomtom(url, query, scenario)
                return
            key = query.get("apiKey", [""])[0]
            if key != args.key:
                stats.bump("refused")
                body = {"error": "Unauthorized", "error_description": "apiKey invalid. apiKey not found."}
                self.send_json(401, json.dumps(body).encode(), scenario, "refused")
                return
            box = query.get("in", [""])[0]
            wanted = {"/v7/flow": "flow", "/v7/incidents": "incidents"}.get(url.path)
            problem = None
            if wanted is None:
                problem = f"unknown path {url.path}"
            elif not BBOX.match(box):
                problem = "in must be bbox:W,S,E,N"
            elif query.get("locationReferencing") != ["shape"]:
                problem = "locationReferencing must be shape"
            elif wanted == "flow" and query.get("functionalClasses") != ["1,2,3"]:
                problem = "functionalClasses must be 1,2,3"
            else:
                w, s, e, n = (float(v) for v in box[5:].split(","))
                if not (w < e and s < n and e - w <= 1.0 and n - s <= 1.0):
                    problem = "maximum width and height of a bounding box can be 1 degree"
            if problem is not None:
                stats.bump("refused")
                body = {"title": "Malformed request", "status": 400, "code": "E608006", "cause": problem,
                        "action": "", "correlationId": "lab"}
                self.send_json(400, json.dumps(body).encode(), scenario, "refused")
                return
            path = args.scenarios / scenario / f"{wanted}.json"
            stats.bump(wanted)
            self.send_json(200, path.read_bytes(), scenario, wanted)

        def tomtom(self, url, query: dict, scenario: str) -> None:
            def refuse(status: int, code: str, message: str) -> None:
                stats.bump("refused")
                body = {"detailedError": {"code": code, "message": message}}
                self.send_json(status, json.dumps(body).encode(), scenario, "tomtom_refused")

            key = self.headers.get("TomTom-Api-Key") or ""
            if not args.tomtom_key or key != args.tomtom_key:
                refuse(403, "FORBIDDEN", "Invalid API key")
                return
            tile = url.path.startswith(TOMTOM_TILES)
            problem = None
            if self.headers.get("TomTom-Api-Version") != "2":
                problem = "TomTom-Api-Version must be 2"
            elif not tile and url.path != TOMTOM_INCIDENTS:
                problem = f"unknown path {url.path}"
            elif self.headers.get("Attributes") != (FLOW_ATTRIBUTES if tile else INCIDENT_ATTRIBUTES):
                problem = "Attributes is not the app's"
            elif tile:
                parts = url.path[len(TOMTOM_TILES):].split("/")
                try:
                    z, x, y = (int(v) for v in parts)
                except ValueError:
                    problem = "tile path must be z/x/y, three integers"
                else:
                    if z != FLOW_ZOOM:
                        problem = f"zoom must be {FLOW_ZOOM}"
                    elif not (0 <= x < 1 << FLOW_ZOOM and 0 <= y < 1 << FLOW_ZOOM):
                        problem = "tile x and y must be within the zoom level"
            else:
                problem = bbox_problem(query.get("bbox", [""])[0])
                if problem is None and query.get("timeValidity") != ["present"]:
                    problem = "timeValidity must be present"
            if problem is not None:
                refuse(400, "INVALID_REQUEST", problem)
                return
            folder = args.scenarios / scenario
            if tile:
                body = flow_tile(json.loads((folder / "flow.json").read_text()), x, y)
                stats.bump("tomtom_tiles")
                self.send_json(200, body, scenario, "tomtom_tiles", "application/vnd.mapbox-vector-tile")
            else:
                body = json.dumps(tomtom_incidents(json.loads((folder / "incidents.json").read_text())))
                stats.bump("tomtom_incidents")
                self.send_json(200, body.encode(), scenario, "tomtom_incidents")

    class Server(socketserver.ThreadingMixIn, http.server.HTTPServer):
        daemon_threads = True
        allow_reuse_address = True

        def verify_request(self, request, client_address):
            stats.bump("connections")
            return True

    server = Server(("127.0.0.1", args.port), Handler)
    print(f"here-standin: listening on 127.0.0.1:{args.port}, scenario {control.read_text().strip()}", flush=True)
    server.serve_forever()
    return 0


if __name__ == "__main__":
    sys.exit(main())
