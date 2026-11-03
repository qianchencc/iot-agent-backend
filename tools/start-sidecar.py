#!/usr/bin/env python3
"""Exec the stateless sidecar with only its own runtime credentials and settings.

Load the backend .env in the invoking shell. Database/admin/encryption settings
are intentionally excluded from the Node process environment.
"""
import os
from pathlib import Path
import shutil
import sys

allowed = {
    "PATH", "LANG", "LC_ALL", "TZ", "SIDECAR_HOST", "SIDECAR_PORT",
    "AGENT_BRIDGE_TOKEN", "JAVA_AGENT_TOOLS_URL", "JAVA_AGENT_SESSIONS_URL",
    "JAVA_AGENT_TOOLS_TOKEN", "MODEL_BASE_URL", "MODEL_ID", "MODEL_API_KEY",
    "JAVA_AGENT_TOOL_TIMEOUT_MS", "SIDECAR_RUN_TIMEOUT_MS", "SIDECAR_HEARTBEAT_MS",
    "AGENT_THINKING_LEVEL", "LANGFUSE_ENABLED", "LANGFUSE_BASE_URL",
    "LANGFUSE_PUBLIC_KEY", "LANGFUSE_SECRET_KEY", "OTEL_SERVICE_NAME",
}
environment = {key: value for key, value in os.environ.items() if key in allowed}
environment["NODE_ENV"] = "production"
node = shutil.which("node")
if not node:
    raise SystemExit("Node 22 is required")
root = Path(__file__).resolve().parents[1]
release = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else root / "agent-sidecar"
os.chdir(release)
os.execve(node, [node, str(release / "dist/server.js")], environment)
