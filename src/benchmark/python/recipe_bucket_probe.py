"""只读回放 CraftTweaker 登记日志；统计样本桶，不冒充运行时 RecipeIndex。"""

import argparse
from collections import Counter, defaultdict
import csv
import hashlib
import json
from pathlib import Path
import re


ADD = re.compile(
    r"Adding '([^']+)' recipe,.*?with name: '([^']+)' that outputs: '(.*)'$"
)
REMOVE_OUTPUT = re.compile(r"Removing '([^']+)' recipes with output: '(.*)'$")
REMOVE_NAMES = re.compile(
    r"Removing '([^']+)' recipe\(s\) with name\(s\): '\[([^]]*)\]'$"
)
STACK = re.compile(r"<item:([^>]+)>(.*)")
COUNT = re.compile(r" \* \d+$")


def replay(lines):
    entries = {}
    events = Counter()
    unresolved = []
    for number, line in enumerate(lines, 1):
        match = ADD.search(line)
        if match:
            events["add_events"] += 1
            recipe_type, recipe_id, output = match.groups()
            stack = STACK.fullmatch(output)
            # 同 ID 后登记的新配方可能不再产生物品。
            entries.pop(recipe_id, None)
            if stack and stack[1] != "minecraft:air":
                entries[recipe_id] = {
                    "recipe_id": recipe_id,
                    "recipe_type": recipe_type,
                    "item": stack[1],
                    "declared_suffix": COUNT.sub("", stack[2]),
                    "log_line": number,
                }
            else:
                events["non_item_or_air_adds"] += 1
            continue
        match = REMOVE_NAMES.search(line)
        if match:
            events["remove_name_events"] += 1
            recipe_type = match[1] if ":" in match[1] else "minecraft:" + match[1]
            for recipe_id in match[2].split(","):
                recipe_id = recipe_id.strip()
                entry = entries.get(recipe_id)
                if entry and entry["recipe_type"] == recipe_type:
                    del entries[recipe_id]
                    events["removed_sample_entries"] += 1
            continue
        match = REMOVE_OUTPUT.search(line)
        if match:
            events["remove_output_events"] += 1
            recipe_type, output = match.groups()
            recipe_type = recipe_type if ":" in recipe_type else "minecraft:" + recipe_type
            stack = STACK.fullmatch(output)
            if stack and not stack[2]:
                removed = [
                    recipe_id for recipe_id, entry in entries.items()
                    if entry["recipe_type"] == recipe_type and entry["item"] == stack[1]
                ]
                for recipe_id in removed:
                    del entries[recipe_id]
                events["removed_sample_entries"] += len(removed)
                continue
            # 日志中的任意 predicate / 带 NBT 删除不能当成普通 item 删除。
            unresolved.append({"log_line": number, "event": line})
            continue
        if "[CraftTweaker]: Removing " in line and "recipe" in line:
            unresolved.append({"log_line": number, "event": line})
        elif "[CraftTweaker]: Adding " in line and " recipe" in line:
            unresolved.append({"log_line": number, "event": line})
    return list(entries.values()), dict(events), unresolved


def summarize(entries):
    buckets = defaultdict(list)
    for entry in entries:
        buckets[entry["item"]].append(entry)
    rows = []
    for item, producers in buckets.items():
        variants = Counter(entry["declared_suffix"] for entry in producers)
        rows.append({
            "item": item,
            "sample_recipes": len(producers),
            "declared_variant_strings": len(variants),
            "largest_declared_variant_bucket": max(variants.values()),
            "untagged_recipes": variants.get("", 0),
            "recipe_types": dict(sorted(Counter(
                entry["recipe_type"] for entry in producers
            ).items())),
        })
    rows.sort(key=lambda row: (-row["sample_recipes"], row["item"]))
    sizes = sorted(row["sample_recipes"] for row in rows)
    summary = {
        "sample_recipes": len(entries),
        "sample_items": len(rows),
        "maximum": max(sizes, default=0),
        "percentiles": {
            str(p): sizes[max(0, (len(sizes) * p + 99) // 100 - 1)] if sizes else 0
            for p in (50, 90, 95, 99)
        },
        "buckets_at_least": {
            str(limit): sum(size >= limit for size in sizes)
            for limit in (16, 32, 64, 128, 256)
        },
    }
    return summary, rows


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--log", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    raw = args.log.read_bytes()
    entries, events, unresolved = replay(raw.decode("utf-8", errors="replace").splitlines())
    summary, buckets = summarize(entries)
    result = {
        "scope": "crafttweaker_logged_additions_not_runtime_recipe_index",
        "source_log": str(args.log.resolve()),
        "source_sha256": hashlib.sha256(raw).hexdigest(),
        "limitations": [
            "Only logged additions are counted, not jar/datapack or RSI-generated recipes.",
            "Unresolved deletions may invalidate sample entries; inspect unresolved_events.",
            "Declared suffix strings are not canonical NBT keys or proven fixed outputs.",
            "No request frequency, candidate timing, or end-to-end speedup is measured.",
        ],
        "summary": summary,
        "events": events,
        "unresolved_events": unresolved,
        "buckets": buckets,
        "entries": sorted(entries, key=lambda entry: entry["recipe_id"]),
    }
    args.output.mkdir(parents=True, exist_ok=True)
    (args.output / "logged-buckets.json").write_text(
        json.dumps(result, indent=2, ensure_ascii=False) + "\n", encoding="utf-8"
    )
    with (args.output / "logged-buckets.csv").open(
        "w", newline="", encoding="utf-8-sig"
    ) as stream:
        writer = csv.DictWriter(stream, fieldnames=[
            "item", "sample_recipes", "declared_variant_strings",
            "largest_declared_variant_bucket", "untagged_recipes", "recipe_types",
        ])
        writer.writeheader()
        for bucket in buckets:
            writer.writerow({
                **bucket, "recipe_types": json.dumps(bucket["recipe_types"], sort_keys=True)
            })
    print(json.dumps(summary, ensure_ascii=True))
    for bucket in buckets[:15]:
        print(json.dumps(bucket, ensure_ascii=True))
    print("unresolved_events=" + str(len(unresolved)))


if __name__ == "__main__":
    main()
