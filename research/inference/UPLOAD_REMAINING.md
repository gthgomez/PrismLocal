# Remaining upload

`START_HERE.md` is on this branch. The two original report files were too large for the GitHub Contents API connector used to land this package (~344 KiB markdown, ~436 KiB HTML).

Original SHA-256:

```
169a5138ea356120d6eafd4b34cd783a44c8fda74b10e6f2fc60bece9f72304f  PrismLocal_Inference_Roadmap.md
8a262bd22abbc5f5ff195769199c58807bbb02ac570e972e0fab1078d7a038f9  PrismLocal_Inference_Roadmap.html
82c04fe75bdacd09bd480afea59afed12ef523e587bbe4e2b93614644cfacd41  START_HERE.md
```

From a clone:

```sh
git fetch origin
git checkout docs/inference-roadmap-2026-09-14
cp /path/to/PrismLocal_Inference_Roadmap.md research/inference/
cp /path/to/PrismLocal_Inference_Roadmap.html research/inference/
(cd research/inference && sha256sum -c SHA256SUMS)
git add research/inference/PrismLocal_Inference_Roadmap.md research/inference/PrismLocal_Inference_Roadmap.html
git commit -m "docs(inference): add full 2026-09-14 inference roadmap report"
git push origin docs/inference-roadmap-2026-09-14
```

Do not invent `program.json`, `missions/`, `schemas/`, or `tools/`; those files were referenced by START_HERE but were not in the attached package.
