"""从剩余的完整身份页和数量页生成独立恢复候选，供存档副本验证。

结构检查无法证明原始提交和数量，因此不能自动替换游戏中的磁盘存档。
"""

import argparse
import copy
import hashlib
import json
from pathlib import Path
import re
import struct
import uuid
import zipfile


PAGE_SIZE = 512
SEGMENT_BYTES = 4 * 1024 * 1024
PAGE_NAME = re.compile(r"(item|fluid)-([0-9]{1,4})-(key|amount)-([0-9a-f]{64})\.bin")


class Reader:
    def __init__(self, data):
        self.data = data
        self.offset = 0

    def take(self, size):
        if size < 0 or size > len(self.data) - self.offset:
            raise ValueError("Truncated record")
        result = self.data[self.offset:self.offset + size]
        self.offset += size
        return result

    def number(self, code):
        return struct.unpack(">" + code, self.take(struct.calcsize(">" + code)))[0]

    def text(self):
        # 页面文件名和资源 ID 都是 ASCII，不依赖 Java 的修改版 UTF-8。
        return self.take(self.number("H")).decode("utf-8", errors="surrogateescape")

    def identity(self):
        return str(uuid.UUID(bytes=self.take(16)))

    def finish(self):
        if self.offset != len(self.data):
            raise ValueError("Trailing bytes")


def checked(archive, name, limit):
    info = archive.getinfo(name)
    if not 44 <= info.file_size <= limit + 44:
        raise ValueError("Invalid file size: " + name)
    data = archive.read(name)
    reader = Reader(data)
    if reader.number("I") != 0x52534955 or reader.number("I") != 1:
        raise ValueError("Invalid file header: " + name)
    length = reader.number("i")
    body = reader.take(length)
    if reader.take(32) != hashlib.sha256(body).digest():
        raise ValueError("Checksum mismatch: " + name)
    reader.finish()
    return body


def envelope(body):
    return struct.pack(">III", 0x52534955, 1, len(body)) + body + hashlib.sha256(body).digest()


def manifest_from(body):
    reader = Reader(body)
    if reader.number("i") != 1:
        raise ValueError("Unsupported manifest format")
    result = {"world": reader.identity(), "disk": reader.identity()}
    owned = reader.number("B")
    if owned not in (0, 1):
        raise ValueError("Invalid owner flag")
    result["owner"] = reader.identity() if owned else None
    result["limits"] = [reader.number("i") for _ in range(4)]
    items, fluids, entry_bytes, payload_bytes = result["limits"]
    if not (1 <= items <= 262144 and 1 <= fluids <= 262144
            and 1 <= entry_bytes <= 1048576 and 1 <= payload_bytes <= 268435456):
        raise ValueError("Invalid manifest limits")
    result["commit"] = reader.number("q")
    if result["commit"] < 1:
        raise ValueError("Invalid commit")
    for kind, capacity in (("item", items), ("fluid", fluids)):
        count = reader.number("i")
        if not 0 <= count <= (capacity + PAGE_SIZE - 1) // PAGE_SIZE:
            raise ValueError("Invalid manifest page count")
        pages = []
        for _ in range(count):
            segments = reader.number("i")
            if not 1 <= segments <= PAGE_SIZE:
                raise ValueError("Invalid manifest segment count")
            pages.append({"keys": [reader.text() for _ in range(segments)], "amounts": reader.text()})
        result[kind] = pages
    reader.finish()
    return result


def manifest_bytes(manifest):
    result = bytearray(struct.pack(">i", 1))
    for field in ("world", "disk"):
        result.extend(uuid.UUID(manifest[field]).bytes)
    result.extend(struct.pack(">B", manifest["owner"] is not None))
    if manifest["owner"]:
        result.extend(uuid.UUID(manifest["owner"]).bytes)
    result.extend(struct.pack(">iiiiq", *manifest["limits"], manifest["commit"]))
    for kind in ("item", "fluid"):
        result.extend(struct.pack(">i", len(manifest[kind])))
        for page in manifest[kind]:
            result.extend(struct.pack(">i", len(page["keys"])))
            for name in page["keys"] + [page["amounts"]]:
                encoded = name.encode("ascii")
                result.extend(struct.pack(">H", len(encoded)) + encoded)
    return envelope(bytes(result))


def nbt_payload(reader, kind, depth=0):
    if depth > 64:
        raise ValueError("NBT nesting exceeds limit")
    if kind in (1, 2, 3, 4, 5, 6):
        return reader.number({1: "b", 2: "h", 3: "i", 4: "q", 5: "f", 6: "d"}[kind])
    if kind == 8:
        return reader.text()
    if kind in (7, 11, 12):
        count = reader.number("i")
        return reader.take(count * {7: 1, 11: 4, 12: 8}[kind])
    if kind == 9:
        child, count = reader.number("B"), reader.number("i")
        if not 0 <= count <= len(reader.data) or (child == 0 and count):
            raise ValueError("Invalid NBT list")
        return child, tuple(nbt_payload(reader, child, depth + 1) for _ in range(count))
    if kind == 10:
        entries = {}
        while True:
            child = reader.number("B")
            if child == 0:
                return tuple(sorted(entries.items()))
            name = reader.text()
            if name in entries:
                raise ValueError("Duplicate NBT compound field")
            entries[name] = (child, nbt_payload(reader, child, depth + 1))
    raise ValueError("Unknown NBT type")


def resource(payload, kind):
    reader = Reader(payload)
    if reader.number("B") != 10:
        raise ValueError("Resource must be an NBT compound")
    reader.text()
    identity = nbt_payload(reader, 10)
    reader.finish()
    fields = dict(identity)
    name_field, amount_field = ("id", "Count") if kind == "item" else ("FluidName", "Amount")
    amount = fields.get(amount_field)
    if not amount or amount[0] not in (1, 2, 3, 4) or amount[1] != 1:
        raise ValueError("Resource template count must be 1")
    name = fields.get(name_field)
    if not name or name[0] != 8 or not re.fullmatch(r"[a-z0-9_.-]+:[a-z0-9_./-]+", name[1]):
        raise ValueError("Invalid resource ID")
    return name[1], identity


def page_body(archive, prefix, name, kind, page_index):
    match = PAGE_NAME.fullmatch(name)
    if not match or match[1] != kind or int(match[2]) != page_index:
        raise ValueError("Invalid page reference")
    body = checked(archive, prefix + name, SEGMENT_BYTES)
    if hashlib.sha256(body).hexdigest() != match[4]:
        raise ValueError("Page content address mismatch")
    return body


def validate_pair(archive, prefix, kind, page_index, page, limits):
    for name in page["keys"]:
        match = PAGE_NAME.fullmatch(name)
        if not match or match[3] != "key":
            raise ValueError("Invalid key column reference")
    match = PAGE_NAME.fullmatch(page["amounts"])
    if not match or match[3] != "amount":
        raise ValueError("Invalid amount column reference")
    amount_body = page_body(archive, prefix, page["amounts"], kind, page_index)
    if len(amount_body) != PAGE_SIZE * 4:
        raise ValueError("Invalid amount page layout")
    amounts = struct.unpack(">512i", amount_body)
    records, seen = [], set()
    capacity = limits[0 if kind == "item" else 1]
    for name in page["keys"]:
        reader = Reader(page_body(archive, prefix, name, kind, page_index))
        while reader.offset < len(reader.data):
            offset, generation, order, length = (reader.number("i"), reader.number("i"),
                                                  reader.number("q"), reader.number("i"))
            if not 0 <= offset < PAGE_SIZE or offset in seen or generation < 0 or not 0 <= length <= limits[2]:
                raise ValueError("Invalid key page layout")
            seen.add(offset)
            payload = reader.take(length)
            amount = amounts[offset]
            if not length:
                if amount != 0:
                    raise ValueError("Amount has no resource identity")
                continue
            slot = page_index * PAGE_SIZE + offset
            if slot >= capacity or generation <= 0 or order <= 0 or amount <= 0:
                raise ValueError("Invalid occupied slot")
            resource_id, identity = resource(payload, kind)
            records.append({"slot": slot, "order": order, "id": resource_id,
                            "identity": identity, "amount": amount, "bytes": length})
    if len(seen) != PAGE_SIZE:
        raise ValueError("Incomplete key page; multi-segment reconstruction needs an intact manifest")
    return records


def build_candidate(source, destination, report_path):
    source, destination, report_path = map(Path, (source, destination, report_path))
    if len({path.resolve() for path in (source, destination, report_path)}) != 3:
        raise ValueError("Input, output and report paths must differ")
    if destination.exists() or report_path.exists():
        raise ValueError("Output already exists; refusing overwrite")
    with zipfile.ZipFile(source) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)):
            raise ValueError("Duplicate archive entries")
        if any(name.startswith(("/", "\\")) or ".." in name.replace("\\", "/").split("/") for name in names):
            raise ValueError("Unsafe archive paths")
        manifests = [name for name in names if name.endswith("/manifest.bin")]
        if len(manifests) != 1:
            raise ValueError("Expected exactly one disk manifest")
        manifest_name = manifests[0]
        prefix = manifest_name[:-len("manifest.bin")]
        world_name = prefix.rsplit("/", 2)[0] + "/world.bin" if prefix.count("/") > 1 else "world.bin"
        original = manifest_from(checked(archive, manifest_name, 1048576))
        if prefix.rstrip("/").rsplit("/", 1)[-1] != original["disk"]:
            raise ValueError("Disk UUID differs from directory")
        if str(uuid.UUID(bytes=checked(archive, world_name, 16))) != original["world"]:
            raise ValueError("World UUID differs from manifest")
        candidate = copy.deepcopy(original)
        candidate["commit"] += 1
        report = {"status": "UNVERIFIED_RECOVERY_CANDIDATE", "source": str(source.resolve()),
                  "output": str(destination.resolve()), "disk": original["disk"],
                  "world": original["world"], "originalCommit": original["commit"],
                  "warning": "Selected by complete slot layouts and ZIP timestamps. Original commit and quantities cannot be established. Test in a world copy.",
                  "originalMissingFiles": [], "pages": [], "totals": {}}
        payload_total = 0
        for kind in ("item", "fluid"):
            all_records = []
            for index, old_page in enumerate(original[kind]):
                report["originalMissingFiles"].extend(prefix + name for name in old_page["keys"] + [old_page["amounts"]] if prefix + name not in names)
                keys, amounts = [], []
                for name in names:
                    if not name.startswith(prefix):
                        continue
                    basename = name[len(prefix):]
                    match = PAGE_NAME.fullmatch(basename)
                    if match and match[1] == kind and int(match[2]) == index:
                        (keys if match[3] == "key" else amounts).append(basename)
                options, rejected = [], []
                key_options = [[name] for name in keys]
                if old_page["keys"] not in key_options:
                    key_options.append(old_page["keys"])
                for key_names in key_options:
                    for amount_name in amounts:
                        page = {"keys": key_names, "amounts": amount_name}
                        try:
                            key_time = max(archive.getinfo(prefix + name).date_time for name in key_names)
                            amount_time = archive.getinfo(prefix + amount_name).date_time
                            if key_time > amount_time:
                                raise ValueError("Key is newer than amount page")
                            records = validate_pair(archive, prefix, kind, index, page, original["limits"])
                            options.append((amount_time, key_time, page, records))
                        except (ValueError, KeyError) as error:
                            rejected.append({"page": page, "reason": str(error)})
                if not options:
                    raise ValueError(f"No complete recovery pair for {kind} page {index}")
                options.sort(key=lambda option: option[:2], reverse=True)
                newest = [option for option in options if option[:2] == options[0][:2]]
                if len(newest) != 1:
                    raise ValueError(f"Ambiguous newest pair for {kind} page {index}")
                amount_time, key_time, page, records = newest[0]
                candidate[kind][index] = page
                all_records.extend(records)
                report["pages"].append({"kind": kind, "index": index, **page,
                                        "keyTime": key_time, "amountTime": amount_time,
                                        "entries": len(records), "quantity": sum(record["amount"] for record in records),
                                        "validAlternatives": len(options), "rejected": rejected})
            if len({record["order"] for record in all_records}) != len(all_records):
                raise ValueError("Duplicate insertion order across pages")
            if len({record["identity"] for record in all_records}) != len(all_records):
                raise ValueError("Duplicate resource identity across pages")
            payload_total += sum(record["bytes"] for record in all_records)
            report["totals"][kind] = {"entries": len(all_records),
                                      "quantity": sum(record["amount"] for record in all_records)}
        if payload_total > original["limits"][3]:
            raise ValueError("Recovery candidate exceeds payload limit")
        encoded = manifest_bytes(candidate)
        destination.parent.mkdir(parents=True, exist_ok=True)
        report_path.parent.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(destination, "x", compression=zipfile.ZIP_DEFLATED) as output:
            for info in archive.infolist():
                replacement = info.filename in (manifest_name, prefix + "manifest.previous.bin")
                output.writestr(info, encoded if replacement else archive.read(info))
            if prefix + "manifest.previous.bin" not in names:
                output.writestr(prefix + "manifest.previous.bin", encoded)
        with report_path.open("x", encoding="utf-8") as output:
            json.dump(report, output, ensure_ascii=True, indent=2)
            output.write("\n")
        return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("destination", type=Path)
    parser.add_argument("--report", type=Path, required=True)
    args = parser.parse_args()
    try:
        report = build_candidate(args.source, args.destination, args.report)
    except (ValueError, KeyError, OSError, zipfile.BadZipFile) as error:
        parser.exit(1, str(error) + "\n")
    print(json.dumps({"status": report["status"], "totals": report["totals"], "output": report["output"]}))


if __name__ == "__main__":
    main()
