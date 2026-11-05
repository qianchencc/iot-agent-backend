#!/usr/bin/env python3
"""Exec the stateless sidecar with only its own runtime settings.

The launcher receives an explicit environment from the deployment process.
It never reads dotenv files and never forwards Java, database, admin, or
configuration-encryption credentials to Node.
"""
import argparse
import os
from pathlib import Path
import shutil

allowed = {
    "PATH", "LANG", "LC_ALL", "TZ", "SIDECAR_HOST", "SIDECAR_PORT",
    "AGENT_BRIDGE_TOKEN", "JAVA_AGENT_TOOLS_URL", "JAVA_AGENT_SESSIONS_URL",
    "JAVA_AGENT_TOOLS_TOKEN", "JAVA_AGENT_TOOL_TIMEOUT_MS",
    "SIDECAR_RUN_TIMEOUT_MS", "SIDECAR_HEARTBEAT_MS", "OTEL_SERVICE_NAME",
}
environment = {key: value for key, value in os.environ.items() if key in allowed}
environment["NODE_ENV"] = "production"
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("release", nargs="?", type=Path,
                    default=Path(__file__).resolve().parents[1] / "agent-sidecar")
parser.add_argument("--node", default=shutil.which("node"))
args = parser.parse_args()
node = args.node
if not node or not Path(node).is_file():
    raise SystemExit("Provide a Node 22 executable with --node or PATH")
release = args.release.resolve()
os.chdir(release)
os.execve(node, [node, str(release / "dist/server.js")], environment)
