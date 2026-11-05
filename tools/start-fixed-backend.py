#!/usr/bin/env python3
"""Start the fixed development backend in detached tmux, without loading dotenv."""
import argparse
import os
from pathlib import Path
import shutil
import socket
import subprocess
import sys
import time
from urllib.error import URLError
from urllib.parse import urlsplit, urlunsplit
from urllib.request import urlopen

import yaml

ROOT = Path(__file__).resolve().parents[1]
SOCKET = "iot-agent-backend"
SESSION = "fixed-backend"
PORTS = {"java": 3381, "node": 3382, "console": 5185}


def wait_for_java(environment):
    """Start the dependent engine only after Java can serve its configuration."""
    target = urlsplit(environment["JAVA_AGENT_TOOLS_URL"])
    health = urlunsplit((target.scheme, target.netloc, "/healthz", "", ""))
    deadline = time.monotonic() + 45
    while time.monotonic() < deadline:
        try:
            with urlopen(health, timeout=2) as response:
                if response.status == 200:
                    return
        except (URLError, TimeoutError, OSError):
            pass
        time.sleep(0.25)
    raise SystemExit("Java did not become ready; sidecar was not started")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", type=Path, default=ROOT / "var/fixed-backend/launch.yaml")
    parser.add_argument("--run", choices=PORTS, help=argparse.SUPPRESS)
    args = parser.parse_args()
    config_path = args.config.resolve()
    if config_path.stat().st_mode & 0o077:
        raise SystemExit("Startup YAML contains credentials and must have mode 0600")
    config = yaml.safe_load(config_path.read_text())
    if not isinstance(config, dict):
        raise SystemExit("Invalid startup YAML")
    components = ["java", "node"] + (["console"] if "console" in config else [])
    for component in components:
        entry = config[component]
        if not Path(entry["executable"]).is_file() or not Path(entry["artifact"]).is_file():
            raise SystemExit(f"Missing executable or frozen artifact for {component}")
        if not isinstance(entry["environment"], dict) or not all(
            isinstance(key, str) and isinstance(value, str) for key, value in entry["environment"].items()
        ):
            raise SystemExit(f"Invalid environment for {component}")
    if args.run:
        if args.run not in components:
            raise SystemExit(f"Component {args.run} is not configured")
        entry = config[args.run]
        if args.run == "node":
            wait_for_java(entry["environment"])
        os.chdir(entry["working_directory"])
        # Frozen releases use their explicit component environment, not a mutable worktree wrapper.
        command = ([entry["executable"], "-jar", entry["artifact"]] if args.run == "java" else
                   [entry["executable"], entry["artifact"]])
        os.execve(command[0], command, entry["environment"])
    tmux = shutil.which("tmux")
    if not tmux:
        raise SystemExit("tmux is required")
    # The tmux server itself receives no database/admin/model credentials.
    server_env = {key: os.environ[key] for key in ("PATH", "HOME", "LANG", "LC_ALL", "TMPDIR", "SHELL")
                  if key in os.environ}
    prefix = [tmux, "-L", SOCKET]
    exists = subprocess.run([*prefix, "has-session", "-t", SESSION], env=server_env,
                            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode == 0
    panes = {}
    if exists:
        listing = subprocess.run(
            [*prefix, "list-panes", "-s", "-t", SESSION, "-F", "#{window_name}\t#{pane_index}\t#{pane_dead}"],
            env=server_env, capture_output=True, text=True, check=True)
        for line in listing.stdout.splitlines():
            name, index, dead = line.split("\t")
            if index == "0":
                panes[name] = dead
    pending = []
    for component in components:
        present = component in panes
        if present and panes[component] == "0":
            continue
        with socket.socket() as connection:
            connection.settimeout(1)
            if connection.connect_ex(("127.0.0.1", PORTS[component])) == 0:
                raise SystemExit(f"Port {PORTS[component]} is occupied; refusing to replace {component}")
        pending.append((component, present))
    for component, present in pending:
        if not exists:
            operation = ["new-session", "-d", "-s", SESSION, "-n", component]
        elif present:
            operation = ["respawn-pane", "-t", f"{SESSION}:{component}.0"]
        else:
            operation = ["new-window", "-d", "-t", SESSION, "-n", component]
        subprocess.run([*prefix, *operation, "-c", str(ROOT),
                        sys.executable, str(Path(__file__).resolve()), "--config", str(config_path),
                        "--run", component], env=server_env, check=True)
        exists = True
        subprocess.run([*prefix, "set-option", "-t", SESSION, "remain-on-exit", "on"],
                       env=server_env, check=True)
    subprocess.run([*prefix, "list-panes", "-s", "-t", SESSION, "-F",
                    "#{session_name}:#{window_name} pid=#{pane_pid} exited=#{pane_dead}"],
                   env=server_env, check=True)
    print(f"Attach: {tmux} -L {SOCKET} attach -t {SESSION}")


if __name__ == "__main__":
    main()
