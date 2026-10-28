#!/usr/bin/env python3
"""A stand-in for HERE's Traffic API v7, for the emulator lab's LAB_TRAFFIC runs (P9).

It answers exactly the two requests the app's TrafficClient makes:

    GET /v7/flow?in=bbox:W,S,E,N&locationReferencing=shape&functionalClasses=1,2,3&apiKey=K
    GET /v7/incidents?in=bbox:W,S,E,N&locationReferencing=shape&apiKey=K

with the hand-written responses of one scenario directory (flow.json, incidents.json,
written by tools/jni-host-test/LabTraffic.java in HERE's shape; nothing in them came from
HERE). Which scenario is served is read from the control file at every request, so the lab
can move the closure onto the route while the app runs. Anything else gets one of HERE's
two error shapes: a wrong key is 401 with {error, error_description}; a malformed request is
400 with {title, status, code, cause, action, correlationId}. Bodies are gzipped when the
request accepts it, as HERE's flow is, so the app's gzip path runs.

It counts every TCP connection it accepts, not only requests: the check that the app makes
no connection at all with traffic off is the connection count staying 0.
The counts go to <state>/stats.txt after every event, and one line per request to
<state>/requests.log, the key replaced by its length.

    here-standin.py --port 8765 --scenarios <dir> --state <dir> --key <key> [--scenario a]

Python's standard library only; it runs in the emulator container, inside the lab pod whose
only interface is loopback, where the emulator reaches it as http://10.0.2.2:<port>.
"""
import argparse
import gzip
import http.server
import json
import pathlib
import re
import socketserver
import sys
import threading
import time
import urllib.parse

BBOX = re.compile(r"^bbox:(-?\d+(\.\d+)?),(-?\d+(\.\d+)?),(-?\d+(\.\d+)?),(-?\d+(\.\d+)?)$")


class Stats:
    def __init__(self, state: pathlib.Path):
        self.state = state
        self.lock = threading.Lock()
        self.counts = {"connections": 0, "requests": 0, "flow": 0, "incidents": 0, "refused": 0}

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

        def send_json(self, status: int, body: bytes, scenario: str, what: str) -> None:
            gz = "gzip" in (self.headers.get("Accept-Encoding") or "")
            payload = gzip.compress(body) if gz else body
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
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
