import unittest

from recipe_bucket_probe import replay, summarize


def added(recipe_id, item, suffix="", recipe_type="minecraft:crafting"):
    return (
        "[INFO][CraftTweaker]: Adding '" + recipe_type
        + "' recipe, of type: \"shaped\", with name: '" + recipe_id
        + "' that outputs: '<item:" + item + ">" + suffix + "'"
    )


class RecipeBucketProbeTest(unittest.TestCase):
    def test_counts_recipes_not_stack_count_and_retains_line_evidence(self):
        entries, _, unresolved = replay([
            added("test:a", "test:book", '.withTag({id: "a"}) * 64'),
            added("test:b", "test:book", '.withTag({id: "a"})'),
            added("test:c", "test:book", '.withTag({id: "b"})'),
        ])
        summary, buckets = summarize(entries)
        self.assertEqual(3, summary["sample_recipes"])
        self.assertEqual(2, buckets[0]["declared_variant_strings"])
        self.assertEqual(2, buckets[0]["largest_declared_variant_bucket"])
        self.assertEqual(3, entries[2]["log_line"])
        self.assertEqual([], unresolved)

    def test_replacing_same_id_does_not_double_count(self):
        entries, _, _ = replay([
            added("test:a", "test:old"), added("test:a", "test:new")
        ])
        self.assertEqual(["test:new"], [entry["item"] for entry in entries])

    def test_air_replacement_removes_old_item_output(self):
        entries, _, _ = replay([
            added("test:a", "test:old"), added("test:a", "minecraft:air")
        ])
        self.assertEqual([], entries)

    def test_remove_by_type_and_item_preserves_other_machine_routes(self):
        entries, events, _ = replay([
            added("test:a", "test:book"),
            added("test:b", "test:book", recipe_type="minecraft:stonecutting"),
            "[INFO][CraftTweaker]: Removing 'crafting' recipes with output: '<item:test:book>'",
            added("test:c", "test:book"),
        ])
        self.assertEqual(["test:b", "test:c"], [entry["recipe_id"] for entry in entries])
        self.assertEqual(1, events["removed_sample_entries"])

    def test_named_removals_accept_multiple_ids(self):
        entries, _, _ = replay([
            added("test:a", "test:book"), added("test:b", "test:book"),
            "[INFO][CraftTweaker]: Removing 'minecraft:crafting' recipe(s)"
            " with name(s): '[test:a, test:b]'",
        ])
        self.assertEqual([], entries)

    def test_named_removals_respect_recipe_type(self):
        entries, _, _ = replay([
            added("test:a", "test:book", recipe_type="minecraft:stonecutting"),
            "[INFO][CraftTweaker]: Removing 'minecraft:crafting' recipe(s)"
            " with name(s): '[test:a]'",
        ])
        self.assertEqual(["test:a"], [entry["recipe_id"] for entry in entries])

    def test_unknown_and_tagged_deletions_are_reported_not_guessed(self):
        entries, _, unresolved = replay([
            added("test:a", "test:book"),
            "[INFO][CraftTweaker]: Removing 'crafting' recipes with output: 'custom.Predicate@123'",
            "[INFO][CraftTweaker]: Removing 'crafting' recipes with output:"
            " '<item:test:book>.withTag({id: \"a\"})'",
        ])
        self.assertEqual(1, len(entries))
        self.assertEqual([2, 3], [event["log_line"] for event in unresolved])

    def test_empty_sample_has_zero_percentiles(self):
        summary, buckets = summarize([])
        self.assertEqual(0, summary["maximum"])
        self.assertTrue(all(value == 0 for value in summary["percentiles"].values()))
        self.assertEqual([], buckets)


if __name__ == "__main__":
    unittest.main()
