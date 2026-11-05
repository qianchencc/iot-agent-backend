#!/usr/bin/env python3
"""Behavior tests for tools/run-local.py.

Runs the real runner against throwaway private YAML configs. A native env child
captures its exact environment without Python locale coercion; a Python child
records cwd and argv. Everything is isolated: no ports, network or
databases. Command: python3 -m unittest discover -s tools -p 'test_*.py'
"""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

import yaml

RUNNER = Path(__file__).resolve().parent / "run-local.py"

CHILD_SCRIPT = """\
import json, os, sys
with open(sys.argv[1], "w", encoding="utf-8") as handle:
    json.dump({"cwd": os.getcwd(), "argv": sys.argv[1:]}, handle)
"""


class RunLocalBehaviorTest(unittest.TestCase):
    def setUp(self):
        workspace = tempfile.TemporaryDirectory()
        self.addCleanup(workspace.cleanup)
        self.root = Path(workspace.name).resolve()
        self.child_script = self.root / "child.py"
        self.child_script.write_text(CHILD_SCRIPT, encoding="utf-8")
        self.workdir = self.root / "work"
        self.workdir.mkdir()
        self.ambient = {
            "AMBIENT_SECRET": "ambient-secret-value",
            "HOME": str(self.root),
            "PATH": os.environ.get("PATH", "/usr/bin:/bin"),
        }

    def write_config(self, entries, mode=0o600, name="launch.yaml", text=None):
        config = self.root / name
        config.write_text(text if text is not None else yaml.safe_dump(entries),
                          encoding="utf-8")
        os.chmod(config, mode)
        return config

    def dump_entry(self, environment, workdir=None):
        return {
            "command": [sys.executable, str(self.child_script)],
            "working_directory": str(workdir or self.workdir),
            "environment": dict(environment),
        }

    def run_runner(self, config, entry, check=False, forwarded=()):
        command = [sys.executable, str(RUNNER), "--config", str(config), "--run", entry]
        if check:
            command.append("--check")
        if forwarded:
            command += ["--", *forwarded]
        return subprocess.run(command, capture_output=True, text=True,
                              env=self.ambient, cwd=str(self.root))

    def run_dump_child(self, environment, forwarded=()):
        output = self.root / "child-output.json"
        config = self.write_config({"dump": self.dump_entry(environment)})
        result = self.run_runner(config, "dump", forwarded=[str(output), *forwarded])
        self.assertEqual(result.returncode, 0, result.stderr)
        return output, json.loads(output.read_text(encoding="utf-8"))

    def run_environment_child(self, environment):
        entry = self.dump_entry(environment)
        entry["command"] = ["/usr/bin/env", "-0"]
        config = self.write_config({"dump": entry})
        result = self.run_runner(config, "dump")
        self.assertEqual(result.returncode, 0, result.stderr)
        return dict(value.split("=", 1) for value in result.stdout.split("\0") if value)

    def assertNoLeak(self, result, *secrets):
        combined = result.stdout + result.stderr
        for secret in secrets:
            self.assertNotIn(secret, combined)

    def test_child_receives_only_explicit_environment(self):
        environment = {"EXPLICIT_VALUE": "explicit-ok", "EMPTY_VALUE": ""}
        self.assertEqual(self.run_environment_child(environment), environment)

    def test_forwarded_arguments_and_working_directory_apply(self):
        output_path, report = self.run_dump_child(
            {"ONLY_VAR": "1"}, forwarded=["--child-flag=7", "plain-arg"])
        self.assertEqual(report["cwd"], str(self.workdir))
        self.assertEqual(report["argv"],
                         [str(output_path), "--child-flag=7", "plain-arg"])

    def test_dotenv_files_never_supply_configuration(self):
        dotenv_text = "LEAKED_FROM_DOTENV=dotenv-secret-value\n"
        (self.workdir / ".env").write_text(dotenv_text, encoding="utf-8")
        (self.root / ".env").write_text(dotenv_text, encoding="utf-8")
        environment = {"EXPLICIT_VALUE": "explicit-ok"}
        self.assertEqual(self.run_environment_child(environment), environment)

    def test_group_readable_config_is_rejected(self):
        config = self.write_config(
            {"dump": self.dump_entry({"SECRET_TOKEN": "config-secret-value"})},
            mode=0o640)
        result = self.run_runner(config, "dump")
        self.assertNotEqual(result.returncode, 0)
        self.assertNoLeak(result, "config-secret-value")

    def test_malformed_yaml_fails_closed_without_leaking_secrets(self):
        config = self.write_config(
            {}, text="dump:\n  token: supersecret-token-value\n  command:\n\t- /bin/echo\n")
        result = self.run_runner(config, "dump")
        self.assertNotEqual(result.returncode, 0)
        self.assertNoLeak(result, "supersecret-token-value")

    def test_non_mapping_yaml_is_rejected(self):
        config = self.write_config({}, text="- just\n- a\n- list\n")
        result = self.run_runner(config, "dump")
        self.assertNotEqual(result.returncode, 0)

    def test_non_mapping_entry_is_rejected(self):
        config = self.write_config({"dump": ["not", "a", "mapping"]})
        result = self.run_runner(config, "dump")
        self.assertNotEqual(result.returncode, 0)

    def test_entry_missing_required_key_is_rejected(self):
        entry = self.dump_entry({})
        del entry["environment"]
        config = self.write_config({"dump": entry})
        result = self.run_runner(config, "dump")
        self.assertNotEqual(result.returncode, 0)

    def test_non_string_environment_value_is_rejected_without_echo(self):
        entry = self.dump_entry({})
        entry["environment"]["SECRET_NUMBER"] = 123456
        config = self.write_config({"dump": entry})
        result = self.run_runner(config, "dump")
        self.assertNotEqual(result.returncode, 0)
        self.assertNoLeak(result, "123456")

    def test_relative_executable_is_rejected(self):
        entry = self.dump_entry({})
        entry["command"] = ["./relative-tool", "arg"]
        config = self.write_config({"dump": entry})
        result = self.run_runner(config, "dump")
        self.assertNotEqual(result.returncode, 0)
        self.assertNoLeak(result, "relative-tool")

    def test_missing_executable_is_rejected(self):
        entry = self.dump_entry({})
        entry["command"] = [str(self.root / "missing-tool")]
        config = self.write_config({"dump": entry})
        result = self.run_runner(config, "dump")
        self.assertNotEqual(result.returncode, 0)

    def test_missing_working_directory_is_rejected(self):
        entry = self.dump_entry({})
        entry["working_directory"] = str(self.root / "not-created")
        config = self.write_config({"dump": entry})
        result = self.run_runner(config, "dump")
        self.assertNotEqual(result.returncode, 0)

    def test_unknown_entry_is_rejected(self):
        config = self.write_config({"dump": self.dump_entry({})})
        result = self.run_runner(config, "other")
        self.assertNotEqual(result.returncode, 0)

    def test_check_isolates_selected_entry_and_never_exposes_values(self):
        entries = {
            "unconfigured": {
                "command": [str(self.root / "missing-optional-tool")],
                "working_directory": str(self.root),
                "environment": {"OTHER_SECRET": "other-secret-value"},
            },
            "dump": self.dump_entry({"SECRET_TOKEN": "dump-secret-value"}),
        }
        output = self.root / "must-not-be-created.json"
        entries["dump"]["command"].append(str(output))
        config = self.write_config(entries)
        result = self.run_runner(config, "dump", check=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertFalse(output.exists())
        self.assertNoLeak(result, "other-secret-value", "dump-secret-value")

    def test_check_rejects_invalid_entries_without_leaking(self):
        entry = self.dump_entry({})
        entry["environment"]["SECRET_TOKEN"] = {"value": "nested-secret-value"}
        config = self.write_config({"dump": entry})
        result = self.run_runner(config, "dump", check=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertNoLeak(result, "nested-secret-value")


if __name__ == "__main__":
    unittest.main()
