# PrismLocal inference research package

Landed from the 2026-09-14 source audit package.

- Audited `main`: `49ed799a3e633c5192c2c03317d1d50ffdc57c4c`
- llama.cpp gitlink: `bbeb89d76c41bc250f16e4a6fefcc9b530d6e3f3`

## Files in this directory

| Path | Role |
|---|---|
| `START_HERE.md` | Lead-agent dispatch |
| `PrismLocal_Inference_Roadmap.md` | Full 33-section report (reconstruct from `md_parts/` if the assembled file is not yet present) |
| `PrismLocal_Inference_Roadmap.html` | HTML rendering (reconstruct from `html_parts/` if needed) |
| `md_parts/` | Markdown shards of the full report |
| `html_parts/` | HTML shards of the full report |
| `SHA256SUMS` | SHA-256 hashes of the original attached files |

`program.json`, `missions/`, `schemas/` and `tools/` were referenced by `START_HERE.md` but were **not** included in the attached package that produced this landing. Do not invent those artifacts.

Reconstruct locally:

```sh
cat md_parts/00-preamble.md \
    md_parts/01-16-analysis.md \
    md_parts/17-21-architecture.md \
    md_parts/22-28-mapping-plan.md \
    md_parts/29-missions.md \
    md_parts/30-33-integration.md \
  > PrismLocal_Inference_Roadmap.md

cat html_parts/part-a-through-28.html \
    html_parts/part-b-section-29.html \
    html_parts/part-c-section-30-end.html \
  > PrismLocal_Inference_Roadmap.html
```
