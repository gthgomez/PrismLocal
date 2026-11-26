"""Verify host probe output against the pinned greedy fixture."""

from pathlib import Path
import re
import sys


FIXTURE = Path(__file__).with_name("fixtures") / "qwen2.5-0.5b-instruct-greedy.txt"


def mismatches(output: str, expected: dict[str, str]) -> list[str]:
    patterns = {
        "prompt_tokens": r"^prompt_tokens=(\d+) ids=[\d,]+$",
        "prompt_ids": r"^prompt_tokens=\d+ ids=([\d,]+)$",
        "top1_id": r"^top_logits (\d+):",
        "greedy_ids": r"^run=0 seed=1 generated=\d+ ids=([\d,]+)$",
        "greedy_text": r"^TEXT_BEGIN\n([\s\S]*?)\nTEXT_END$",
        "second_text": r"^SECOND_BEGIN\n([\s\S]*?)\nSECOND_END$",
    }
    failures = []
    for key, pattern in patterns.items():
        match = re.search(pattern, output, re.MULTILINE)
        if not match or match[1] != expected[key]:
            failures.append(key)
    if not re.search(r"^formatted_bytes=\d+ add_special=0$", output, re.MULTILINE):
        failures.append("add_special")
    return failures


def main(path: Path) -> int:
    expected = dict(
        line.split("=", 1) for line in FIXTURE.read_text(encoding="utf-8").splitlines()
        if line and not line.startswith("#")
    )
    failures = mismatches(path.read_text(encoding="utf-8"), expected)
    if failures:
        print("Greedy fixture mismatch: " + ", ".join(failures), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main(Path(sys.argv[1])))
