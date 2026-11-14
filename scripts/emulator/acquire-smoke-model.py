#!/usr/bin/env python3
"""Stage the public, hash-pinned GGUF used by Android instrumentation tests."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import sys
import tempfile
import urllib.request


MODEL_NAME = "tinyllama-v0.q8_0.gguf"
SHA256 = "0cbe6769faaa77f4cdbf2f39dbc5f0fb9b3d62f7f9bc5760aa364946e6f2d0f5"
MODEL_URL = (
    "https://huggingface.co/afrideva/TinyLLama-v0-GGUF/resolve/"
    "d2206a68a3411a19d76dba454d1ee06ccd0740ea/" + MODEL_NAME
)
DEFAULT_OUTPUT = Path(__file__).resolve().parents[2] / "app/src/androidTest/assets/smoke-model"


def digest(path: Path) -> str:
    sha = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            sha.update(block)
    return sha.hexdigest()


def check_model(path: Path) -> None:
    actual = digest(path)
    if actual != SHA256:
        raise ValueError(f"SHA-256 mismatch for {path}: expected {SHA256}, got {actual}")


def check_manifest(path: Path) -> None:
    try:
        manifest = json.loads(path.read_text(encoding="utf-8"))
        version = manifest["versions"][manifest["active_version"]]
        valid = version["file"] == MODEL_NAME and version["sha256"].lower() == SHA256
    except (KeyError, TypeError, ValueError, AttributeError) as exc:
        raise ValueError(f"Existing manifest is invalid; preserved: {path}") from exc
    if not valid:
        raise ValueError(f"Existing manifest references a different model; preserved: {path}")


def stage(output: Path, local_source: Path | None) -> None:
    output.mkdir(parents=True, exist_ok=True)
    model = output / MODEL_NAME
    manifest = output / "manifest.json"

    # Never replace existing, unrecognized assets in a user's checkout.
    if manifest.exists():
        check_manifest(manifest)
    if model.exists():
        check_model(model)
    else:
        with tempfile.NamedTemporaryFile(prefix=".smoke-model-", dir=output, delete=False) as tmp:
            temporary = Path(tmp.name)
        try:
            with temporary.open("wb") as tmp:
                if local_source is None:
                    with urllib.request.urlopen(MODEL_URL, timeout=60) as source:
                        shutil.copyfileobj(source, tmp)
                else:
                    with local_source.open("rb") as source:
                        shutil.copyfileobj(source, tmp)
            check_model(temporary)
            try:
                # Same-directory hard linking publishes complete bytes without overwriting a race.
                os.link(temporary, model)
            except FileExistsError:
                check_model(model)
        finally:
            temporary.unlink(missing_ok=True)

    if not manifest.exists():
        contents = {"active_version": "v1", "versions": {"v1": {"file": MODEL_NAME, "sha256": SHA256}}}
        try:
            with manifest.open("x", encoding="utf-8", newline="\n") as target:
                json.dump(contents, target, indent=2)
                target.write("\n")
        except FileExistsError:
            check_manifest(manifest)
    print(f"Verified {model} (SHA-256 {SHA256})")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, help="reuse a local copy after SHA-256 verification")
    parser.add_argument("--output-dir", type=Path, default=DEFAULT_OUTPUT)
    args = parser.parse_args()
    try:
        stage(args.output_dir, args.source)
    except (OSError, ValueError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
