#!/usr/bin/env python3
"""Loopback-only preview server; never log URLs, query strings or credentials."""
import argparse
from functools import partial
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import sys


class PreviewHandler(SimpleHTTPRequestHandler):
    def log_request(self, code="-", size="-"):
        self._event("request", str(code))

    def log_error(self, format, *args):
        self._event("error")

    def log_message(self, format, *args):
        self._event("server")

    def _event(self, event, status=None):
        # Base handlers include raw request lines in format/args. Do not format them.
        print(json.dumps({"event": event, "status": status}), file=sys.stderr, flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--directory", type=Path, required=True)
    parser.add_argument("--port", type=int, default=9419)
    args = parser.parse_args()
    directory = args.directory.resolve(strict=True)
    if not directory.is_dir():
        parser.error("--directory must be a directory")
    handler = partial(PreviewHandler, directory=str(directory))
    with ThreadingHTTPServer(("127.0.0.1", args.port), handler) as server:
        print(f"Business preview player ready on 127.0.0.1:{server.server_port}", flush=True)
        server.serve_forever()


if __name__ == "__main__":
    main()
