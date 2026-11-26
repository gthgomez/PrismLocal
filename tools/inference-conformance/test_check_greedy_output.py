import unittest

from check_greedy_output import mismatches

EXPECTED = dict(prompt_tokens="3", prompt_ids="8,9,10", top1_id="18", greedy_ids="18,19",
                greedy_text="First answer.", second_text="Second answer.")
OUTPUT = """formatted_bytes=42 add_special=0
prompt_tokens=3 ids=8,9,10
top_logits 18:4.2:Token 19:3.0:Other
run=0 seed=1 generated=2 ids=18,19
TEXT_BEGIN
First answer.
TEXT_END
top_logits 21:4.2:Token 18:3.0:Other
SECOND_BEGIN
Second answer.
SECOND_END
"""


class GreedyCheckerTest(unittest.TestCase):
    def test_exact_output_matches(self):
        self.assertEqual(mismatches(OUTPUT, EXPECTED), [])

    def test_prefix_matches_and_later_logits_do_not_hide_divergence(self):
        mutations = [
            ("8,9,10", "8,9,100", "prompt_ids"),
            ("ids=18,19", "ids=18,19,20", "greedy_ids"),
            ("First answer.", "First answer. More text.", "greedy_text"),
            ("Second answer.", "Second answer. More text.", "second_text"),
            ("top_logits 18:4.2:Token", "top_logits 21:4.2:Token", "top1_id"),
            ("add_special=0", "add_special=1", "add_special"),
        ]
        for old, new, field in mutations:
            with self.subTest(field=field):
                self.assertEqual(mismatches(OUTPUT.replace(old, new), EXPECTED), [field])
