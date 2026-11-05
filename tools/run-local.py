#!/usr/bin/env python3
"""Run one entry from an explicit private YAML config without dotenv.

Each named entry declares an absolute command, an absolute working directory
and the complete environment for the child process. The selected entry runs
via os.execve with exactly that environment: no ambient variables are
inherited and no .env file is ever consulted, so credentials reach children
only when declared explicitly in the config. Compose entries must disable
Compose's own implicit .env discovery in their command and environment.
"""
import argparse
import os
from pathlib import Path
import sys

import yaml

REQUIRED_KEYS = ("command", "environment", "working_directory")


def split_forwarded_args(argv):
    """Split runner flags from child arguments at the first standalone --."""
    if "--" in argv:
        separator = argv.index("--")
        return argv[:separator], argv[separator + 1:]
    return argv, []


def load_entries(config_path):
    """Load the private YAML mapping of entries, failing closed and redacted."""
    try:
        mode = config_path.stat().st_mode
    except OSError:
        raise SystemExit(f"Cannot read config file {config_path}")
    if mode & 0o077:
        raise SystemExit("Config contains credentials and must have mode 0600")
    try:
        config = yaml.safe_load(config_path.read_text())
    except (OSError, UnicodeError, yaml.YAMLError):
        raise SystemExit("Invalid config file")
    if not isinstance(config, dict) or not all(
            isinstance(name, str) and name for name in config):
        raise SystemExit("Invalid config file")
    return config


def validate_entry(name, entry):
    """Reject entry shapes that could weaken explicit-environment execution.

    Error messages carry entry and key names only; command strings, paths and
    environment values from the config are never echoed.
    """
    if not isinstance(entry, dict) or not all(isinstance(key, str) for key in entry):
        raise SystemExit(f"Invalid entry {name}")
    for key in REQUIRED_KEYS:
        if key not in entry:
            raise SystemExit(f"Entry {name} is missing {key}")
    command = entry["command"]
    if (not isinstance(command, list) or not command
            or not all(isinstance(part, str) for part in command)):
        raise SystemExit(f"Invalid command for entry {name}")
    executable = Path(command[0])
    if not executable.is_absolute():
        raise SystemExit(f"Executable for entry {name} must be an absolute path")
    if not executable.is_file() or not os.access(executable, os.X_OK):
        raise SystemExit(f"Missing or non-executable executable for entry {name}")
    working_directory = entry["working_directory"]
    if (not isinstance(working_directory, str)
            or not Path(working_directory).is_absolute()
            or not Path(working_directory).is_dir()):
        raise SystemExit(f"Invalid working directory for entry {name}")
    environment = entry["environment"]
    if not isinstance(environment, dict) or not all(
            isinstance(key, str) and isinstance(value, str)
            for key, value in environment.items()):
        raise SystemExit(f"Invalid environment for {name}")


def execute_entry(entry, forwarded_args):
    """Replace this process with the entry: exact environment, no ambient vars."""
    try:
        os.chdir(entry["working_directory"])
        os.execve(entry["command"][0], entry["command"] + forwarded_args,
                  entry["environment"])
    except (OSError, ValueError):
        raise SystemExit("Cannot execute entry")


def main():
    runner_argv, forwarded_args = split_forwarded_args(sys.argv[1:])
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", type=Path, required=True)
    parser.add_argument("--run", required=True, metavar="ENTRY",
                        help="named entry to validate or execute")
    parser.add_argument("--check", action="store_true",
                        help="validate the selected entry and print environment key names only")
    args = parser.parse_args(runner_argv)
    entries = load_entries(args.config)
    if args.run not in entries:
        raise SystemExit(f"Entry {args.run} is not configured")
    entry = entries[args.run]
    validate_entry(args.run, entry)
    if args.check:
        print(f"{args.run}: " + ", ".join(sorted(entry["environment"])))
        return
    execute_entry(entry, forwarded_args)


if __name__ == "__main__":
    main()
