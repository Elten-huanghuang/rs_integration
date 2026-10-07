import hashlib
from pathlib import Path
import struct
import tempfile
import unittest
import uuid
import zipfile

from recover_unified_disk import build_candidate, checked, envelope, manifest_bytes, manifest_from, validate_pair


def text(value):
    value = value.encode("ascii")
    return struct.pack(">H", len(value)) + value


def item_payload(name):
    return b"\x0a\x00\x00\x01" + text("Count") + b"\x01\x08" + text("id") + text(name) + b"\x00"


def key_body(name):
    result = bytearray()
    for slot in range(512):
        payload = item_payload(name) if slot == 0 else b""
        result.extend(struct.pack(">iiqi", slot, 1 if payload else 0, 1 if payload else 0, len(payload)))
        result.extend(payload)
    return bytes(result)


def page_name(column, body):
    return "item-0-" + column + "-" + hashlib.sha256(body).hexdigest() + ".bin"


class RecoveryTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.source, self.output, self.report = (self.root / name for name in ("source.zip", "candidate.zip", "report.json"))
        self.world, self.disk, self.owner = (str(uuid.uuid4()) for _ in range(3))
        self.prefix = self.disk + "/"

    def tearDown(self):
        self.temporary.cleanup()

    def fixture(self, corrupt_key=False, missing_slot=False, duplicate_timestamp=False):
        old_keys = key_body("minecraft:stone")
        keys = key_body("minecraft:diamond")
        old_amounts = struct.pack(">512i", 7, *([0] * 511))
        amounts = struct.pack(">512i", 19, *([0] * 510), 1 if missing_slot else 0)
        key, old_amount, amount = page_name("key", keys), page_name("amount", old_amounts), page_name("amount", amounts)
        metadata = {"world": self.world, "disk": self.disk, "owner": self.owner,
                    "limits": [512, 512, 1048576, 268435456], "commit": 3,
                    "item": [{"keys": [page_name("key", old_keys)], "amounts": old_amount}], "fluid": []}
        with zipfile.ZipFile(self.source, "w") as archive:
            archive.writestr("world.bin", envelope(uuid.UUID(self.world).bytes))
            archive.writestr(self.prefix + "manifest.bin", manifest_bytes(metadata))
            archive.writestr(self.prefix + "manifest.previous.bin", manifest_bytes(metadata))
            for name, body, minute in ((key, keys, 1), (old_amount, old_amounts, 0), (amount, amounts, 2)):
                encoded = envelope(body)
                if corrupt_key and name == key:
                    encoded = encoded[:-1] + bytes([encoded[-1] ^ 1])
                archive.writestr(zipfile.ZipInfo(self.prefix + name, (2026, 10, 6, 21, minute, 0)), encoded)
            if duplicate_timestamp:
                other = key_body("minecraft:emerald")
                archive.writestr(zipfile.ZipInfo(self.prefix + page_name("key", other), (2026, 10, 6, 21, 1, 0)), envelope(other))
        return metadata, key, amount

    def test_candidate_keeps_identity_validates_pages_and_never_changes_source(self):
        original, key, amount = self.fixture()
        source_bytes = self.source.read_bytes()
        report = build_candidate(self.source, self.output, self.report)
        self.assertEqual(source_bytes, self.source.read_bytes())
        self.assertEqual("UNVERIFIED_RECOVERY_CANDIDATE", report["status"])
        self.assertEqual({"entries": 1, "quantity": 19}, report["totals"]["item"])
        self.assertEqual(1, len(report["originalMissingFiles"]))
        with zipfile.ZipFile(self.output) as archive:
            restored = manifest_from(checked(archive, self.prefix + "manifest.bin", 1048576))
            for field in ("world", "disk", "owner", "limits"):
                self.assertEqual(original[field], restored[field])
            self.assertEqual([{ "keys": [key], "amounts": amount}], restored["item"])
            self.assertEqual(4, restored["commit"])
            self.assertEqual(archive.read(self.prefix + "manifest.bin"), archive.read(self.prefix + "manifest.previous.bin"))
            records = validate_pair(archive, self.prefix, "item", 0, restored["item"][0], restored["limits"])
            self.assertEqual("minecraft:diamond", records[0]["id"])

    def test_corrupt_key_is_never_used(self):
        self.fixture(corrupt_key=True)
        with self.assertRaisesRegex(ValueError, "No complete recovery pair"):
            build_candidate(self.source, self.output, self.report)
        self.assertFalse(self.output.exists())

    def test_unidentified_amount_fails_reconstruction(self):
        self.fixture(missing_slot=True)
        with self.assertRaisesRegex(ValueError, "No complete recovery pair"):
            build_candidate(self.source, self.output, self.report)

    def test_same_timestamp_different_identities_are_ambiguous(self):
        self.fixture(duplicate_timestamp=True)
        with self.assertRaisesRegex(ValueError, "Ambiguous newest pair"):
            build_candidate(self.source, self.output, self.report)

    def test_input_and_existing_output_cannot_be_overwritten(self):
        self.fixture()
        with self.assertRaisesRegex(ValueError, "paths must differ"):
            build_candidate(self.source, self.source, self.report)
        self.output.write_bytes(b"preserve")
        with self.assertRaisesRegex(ValueError, "Output already exists"):
            build_candidate(self.source, self.output, self.report)
        self.assertEqual(b"preserve", self.output.read_bytes())


if __name__ == "__main__":
    unittest.main()
