import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "acquire-smoke-model.py"
MODEL_NAME = "tinyllama-v0.q8_0.gguf"
EXPECTED_SHA256 = "0cbe6769faaa77f4cdbf2f39dbc5f0fb9b3d62f7f9bc5760aa364946e6f2d0f5"


class AcquireSmokeModelTest(unittest.TestCase):
    def invoke(self, source: Path, output: Path) -> subprocess.CompletedProcess:
        return subprocess.run(
            [sys.executable, str(SCRIPT), "--source", str(source), "--output-dir", str(output)],
            text=True,
            capture_output=True,
            check=False,
        )

    def test_bad_source_is_rejected_without_installing_a_model(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            source = root / "corrupt.gguf"
            source.write_bytes(b"not the published model")
            output = root / "assets"
            result = self.invoke(source, output)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("SHA-256 mismatch", result.stderr)
            self.assertFalse((output / MODEL_NAME).exists())
            self.assertFalse((output / "manifest.json").exists())
            self.assertEqual(source.read_bytes(), b"not the published model")

    def test_existing_unknown_asset_is_not_overwritten(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            source = root / "unused.gguf"
            source.write_bytes(b"source")
            output = root / "assets"
            output.mkdir()
            existing = output / MODEL_NAME
            existing.write_bytes(b"existing user data")
            result = self.invoke(source, output)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("SHA-256 mismatch", result.stderr)
            self.assertEqual(existing.read_bytes(), b"existing user data")

    @unittest.skipUnless(os.environ.get("PRISM_SMOKE_SOURCE"), "set PRISM_SMOKE_SOURCE to the published model")
    def test_verified_model_is_staged_and_rerun_is_idempotent(self):
        source = Path(os.environ["PRISM_SMOKE_SOURCE"])
        with tempfile.TemporaryDirectory() as tmp:
            output = Path(tmp) / "assets"
            for _ in range(2):
                result = self.invoke(source, output)
                self.assertEqual(result.returncode, 0, result.stderr)
            installed = output / MODEL_NAME
            self.assertEqual(hashlib.sha256(installed.read_bytes()).hexdigest(), EXPECTED_SHA256)
            manifest = json.loads((output / "manifest.json").read_text(encoding="utf-8"))
            self.assertEqual(manifest["versions"][manifest["active_version"]]["sha256"], EXPECTED_SHA256)


if __name__ == "__main__":
    unittest.main()
