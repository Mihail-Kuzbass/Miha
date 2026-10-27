#!/usr/bin/env python3
"""Audit GNU gettext .po files for missing Russian translations (stdlib only)."""
from __future__ import annotations

import argparse
import ast
from pathlib import Path
import sys


def parse_po(path: Path) -> list[dict[str, str]]:
    entries: list[dict[str, str]] = []
    entry: dict[str, str] = {}
    active_key: str | None = None

    def finish() -> None:
        nonlocal entry, active_key
        if entry:
            entries.append(entry)
        entry = {}
        active_key = None

    for raw_line in path.read_text(encoding="utf-8-sig").splitlines():
        line = raw_line.strip()
        if not line:
            finish()
            continue
        if line.startswith("#"):
            continue

        if line.startswith("msgid "):
            if "msgid" in entry:
                finish()
            active_key = "msgid"
            entry[active_key] = ast.literal_eval(line[6:])
        elif line.startswith("msgid_plural "):
            active_key = "msgid_plural"
            entry[active_key] = ast.literal_eval(line[len("msgid_plural "):])
        elif line.startswith("msgstr "):
            active_key = "msgstr"
            entry[active_key] = ast.literal_eval(line[7:])
        elif line.startswith("msgstr["):
            key = line.split("]", 1)[0] + "]"
            active_key = key
            entry[key] = ast.literal_eval(line.split(" ", 1)[1])
        elif line.startswith('"') and active_key:
            entry[active_key] += ast.literal_eval(line)
    finish()
    return entries


def audit(path: Path) -> tuple[int, int, list[str]]:
    entries = parse_po(path)
    translatable = [e for e in entries if e.get("msgid", "") != ""]
    missing: list[str] = []
    for entry in translatable:
        translations = [v for k, v in entry.items() if k == "msgstr" or k.startswith("msgstr[")]
        if not translations or any(not value.strip() for value in translations):
            missing.append(entry["msgid"])
    return len(translatable), len(missing), missing


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("po_files", nargs="+", type=Path, help="PO files to audit")
    parser.add_argument("--report", type=Path, help="write missing msgids to a UTF-8 report")
    parser.add_argument("--fail-on-missing", action="store_true", help="return exit code 1 if any untranslated entries are found")
    args = parser.parse_args()

    report_lines: list[str] = []
    total_entries = total_missing = 0
    failed = False
    for path in args.po_files:
        if not path.is_file():
            print(f"ERROR: file not found: {path}", file=sys.stderr)
            return 2
        try:
            entries, missing_count, missing = audit(path)
        except (UnicodeError, SyntaxError, ValueError) as exc:
            print(f"ERROR: could not parse {path}: {exc}", file=sys.stderr)
            return 2
        translated = entries - missing_count
        percent = 100.0 if entries == 0 else translated * 100.0 / entries
        print(f"{path}: {translated}/{entries} translated ({percent:.1f}%); missing: {missing_count}")
        total_entries += entries
        total_missing += missing_count
        failed |= missing_count > 0
        report_lines.append(f"## {path}\n")
        report_lines.extend(f"- {msgid}\n" for msgid in missing)
        report_lines.append("\n")

    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text("".join(report_lines), encoding="utf-8")
        print(f"Report written: {args.report}")
    print(f"TOTAL: {total_entries - total_missing}/{total_entries} translated; missing: {total_missing}")
    return 1 if args.fail_on_missing and failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
