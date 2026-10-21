import json
import os
import sys
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_SOURCE = Path(
    "/var/folders/j0/68bggzcx5q5fvg_j9ht1c90c0000gn/T/opencode/wanggaolang-http1"
)
SOURCE_DIR = Path(os.environ.get("LTT_RULES_SRC", str(DEFAULT_SOURCE)))
OUTPUT = ROOT / "app" / "src" / "main" / "assets" / "rules.json"

SOURCES = [
    ("AllRules.json", "all"),
    ("ExtendedRules.json", "extended"),
    ("BasicRules.json", "basic"),
]

SCALARS = {
    "click_way_popup": "clickWayPopup",
    "times": "times",
    "delay_popup": "delayPopup",
    "unite_popup_rules": "unitePopupRules",
    "ltt_service": "lttService",
    "search_times_popup": "searchTimesPopup",
}

BOOL_SCALARS = {"clickWayPopup", "unitePopupRules", "lttService"}


def strip_trailing_commas(text):
    out = []
    in_string = False
    escaped = False
    removed = 0
    index = 0
    length = len(text)
    while index < length:
        char = text[index]
        if in_string:
            out.append(char)
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == '"':
                in_string = False
            index += 1
            continue
        if char == '"':
            in_string = True
            out.append(char)
            index += 1
            continue
        if char == ",":
            cursor = index + 1
            while cursor < length and text[cursor] in " \t\r\n":
                cursor += 1
            if cursor < length and text[cursor] in "]}":
                removed += 1
                index += 1
                continue
        out.append(char)
        index += 1
    return "".join(out), removed


def loads_tolerant(text):
    try:
        return json.loads(text), 0
    except json.JSONDecodeError:
        stripped, removed = strip_trailing_commas(text)
        return json.loads(stripped), max(removed, 1)


def top_elements(data):
    if isinstance(data, list):
        return data
    if isinstance(data, dict):
        inner = data.get("list")
        if isinstance(inner, list):
            return inner
        return [data]
    return [data]


def to_int(value):
    if isinstance(value, bool):
        return int(value)
    if isinstance(value, int):
        return value
    try:
        return int(float(value))
    except (TypeError, ValueError):
        return 0


def to_bool(value):
    if isinstance(value, bool):
        return value
    if isinstance(value, str):
        lowered = value.strip().lower()
        if lowered in ("true", "1"):
            return True
        if lowered in ("false", "0"):
            return False
        return bool(lowered)
    return bool(value)


def normalize(parsed):
    result = {"popupRules": []}
    raw_rules = []
    scalars = {}
    if isinstance(parsed, list):
        raw_rules = parsed
    elif isinstance(parsed, dict):
        rules = parsed.get("popup_rules")
        if isinstance(rules, list):
            raw_rules = rules
        for source_key, target_key in SCALARS.items():
            if source_key in parsed:
                scalars[target_key] = parsed[source_key]
    seen = set()
    for item in raw_rules:
        if not isinstance(item, dict):
            continue
        rule_id = item.get("id", "")
        action = item.get("action", "")
        rule_id = "" if rule_id is None else str(rule_id)
        action = "" if action is None else str(action)
        if not rule_id and not action:
            continue
        key = (rule_id, action)
        if key in seen:
            continue
        seen.add(key)
        entry = {"id": rule_id, "action": action}
        if "times" in item:
            entry["times"] = to_int(item["times"])
        result["popupRules"].append(entry)
    for key, value in scalars.items():
        result[key] = to_bool(value) if key in BOOL_SCALARS else to_int(value)
    return result


def combine(existing, incoming):
    merged = dict(existing)
    seen = {(rule["id"], rule["action"]) for rule in merged["popupRules"]}
    for rule in incoming["popupRules"]:
        key = (rule["id"], rule["action"])
        if key not in seen:
            seen.add(key)
            merged["popupRules"].append(rule)
    for key, value in incoming.items():
        if key == "popupRules":
            continue
        merged[key] = value
    return merged


def load_source(path):
    text = path.read_text(encoding="utf-8-sig")
    elements, repaired = loads_tolerant(text)
    apps = {}
    failures = []
    duplicates = []
    for element in top_elements(elements):
        if not isinstance(element, dict):
            failures.append("<element>")
            continue
        for app_id, raw in element.items():
            app_key = str(app_id)
            try:
                if isinstance(raw, str):
                    parsed, fixes = loads_tolerant(raw)
                    repaired += fixes
                else:
                    parsed = raw
                normalized = normalize(parsed)
            except (json.JSONDecodeError, TypeError, ValueError):
                normalized = {"popupRules": []}
                failures.append(app_key)
            if app_key in apps:
                duplicates.append(app_key)
                apps[app_key] = combine(apps[app_key], normalized)
            else:
                apps[app_key] = normalized
    return apps, failures, repaired, duplicates


def merge_sources(sources):
    apps = {}
    order = []
    seen_scalars = {}
    for priority, source_apps in sources:
        for app_id, data in source_apps.items():
            if app_id not in apps:
                apps[app_id] = {"popupRules": []}
                seen_scalars[app_id] = set()
                order.append(app_id)
            target = apps[app_id]
            index = {(rule["id"], rule["action"]) for rule in target["popupRules"]}
            for rule in data["popupRules"]:
                key = (rule["id"], rule["action"])
                if key not in index:
                    index.add(key)
                    target["popupRules"].append(rule)
            seen = seen_scalars[app_id]
            for key, value in data.items():
                if key == "popupRules":
                    continue
                if priority == "basic" and key in seen:
                    continue
                target[key] = value
                seen.add(key)
    return {app_id: apps[app_id] for app_id in order}


def main():
    if not SOURCE_DIR.is_dir():
        print(f"source directory not found: {SOURCE_DIR}", file=sys.stderr)
        return 1
    loaded = []
    total_fixes = 0
    total_failures = 0
    total_duplicates = 0
    for filename, priority in SOURCES:
        path = SOURCE_DIR / filename
        if not path.is_file():
            print(f"missing source file: {path}", file=sys.stderr)
            return 1
        apps, failures, repaired, duplicates = load_source(path)
        rules = sum(len(data["popupRules"]) for data in apps.values())
        loaded.append((priority, apps))
        total_fixes += repaired
        total_failures += len(failures)
        total_duplicates += len(duplicates)
        print(
            f"{filename}: apps={len(apps)} rules={rules} "
            f"trailing_comma_fixes={repaired} unparsed={len(failures)} duplicates={len(duplicates)}"
        )
        if failures:
            print(f"  unparsed ids: {', '.join(sorted(failures))}")
        if duplicates:
            print(f"  duplicate ids: {', '.join(sorted(duplicates))}")
    merged = merge_sources(loaded)
    payload = {
        "version": 1,
        "generatedAt": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "apps": merged,
    }
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    total_rules = sum(len(data["popupRules"]) for data in merged.values())
    print(
        f"merged: apps={len(merged)} rules={total_rules} bytes={OUTPUT.stat().st_size} "
        f"output={OUTPUT}"
    )
    print(
        f"selfcheck: apps={len(merged)} rule_pairs={total_rules} "
        f"trailing_comma_fixes={total_fixes} unparsed={total_failures} "
        f"duplicates={total_duplicates} bad_entries_repaired={total_fixes}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
