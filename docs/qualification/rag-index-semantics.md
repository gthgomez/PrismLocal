# RAG vector index semantics, model switching, and data recovery

This note is the source-of-truth contract for the local SQLite vector store
(`VectorStore`) and the ingest path (`RagManager`). It exists because an earlier
revision of the encoder-identity work treated a *different-but-valid* encoder's
rows as "stale" and offered to delete them, which could destroy a document's only
stored text merely because a different model was loaded. That is now fixed and
prevented by tests.

## Storage model

- One index per document. Re-ingesting a document id deletes that document's rows
  and inserts the new set in a single transaction (`VectorStore.replaceDocument`).
  A document never holds two encoders' rows at once.
- Every row stores three compatibility facts: `embedding_revision` (algorithm/
  schema revision of the embedding computation), `encoder_identity` (the encoder
  artifact, e.g. the loaded model's SHA-256, plus the revision token), and
  `embedding_dim` (the vector width).
- The chunk **text** is stored in the row. The original full document text is
  **not** stored.

## Three distinct row populations

| Population | Predicate | Reachable by `search()`? | Safe to delete? |
| --- | --- | --- | --- |
| **Searchable** | revision == current **and** encoder == current | yes | no |
| **Stored, not searchable** | revision == current, encoder != current (or no model loaded) | no, right now | **no** — still valid |
| **Obsolete** | revision != current | no, under any encoder | yes |

`VectorStore` exposes these as:
- `countStoredChunks()` / `countSearchableChunks()` / `countObsoleteChunks()`
- `getDocumentSummaries()` — one bounded summary per stored document with
  `storedChunkCount`, `searchableChunkCount`, and a short preview.
- `deleteObsoleteChunks()` — deletes only the obsolete population.

## Model switch semantics (A → B → A)

Switching the active model changes the encoder identity. Rows written under model
A are then stored-but-not-searchable while B is active. They are **not** obsolete:

- `countObsoleteChunks()` does not count them and `deleteObsoleteChunks()` does
  not touch them.
- The document browser still lists the document, shows its stored chunk count, and
  marks how many chunks are searchable with the current model.
- Switching back to A makes the document searchable again **without re-indexing**.

The cleanup banner ("Remove") appears only when genuinely obsolete rows exist
(rows written by an older app/algorithm revision, unreachable by every encoder).
Its copy says "indexed by an older app version", which matches the obsolete
population and is no longer shown for a model switch.

## Ingest correctness

- **All-or-nothing.** The whole chunk set is embedded before anything is written.
  If any chunk fails to embed, or an encoder switch is detected mid-ingest, nothing
  is committed and the document's previous index is retained. `ingestDocument`
  returns the number of chunks actually committed (`0` when nothing was written).
- **Encoder snapshot.** The encoder identity is snapshotted once, before encoding,
  and every row is stamped with that snapshot regardless of how long the ingest
  takes. If the live encoder changes while the chunks are being embedded, the
  ingest aborts (`IngestResult.encoderChanged`) instead of mislabeling vectors.
- **Bounded size.** Documents larger than `RagManager.MAX_INGEST_CHARS` are
  rejected without encoding any chunk. Split larger documents first; staged
  streaming ingest is not implemented.
- **Cancellation.** A cancelled ingest stops promptly: `CancellationException`
  propagates instead of being caught by `runCatching`, so remaining chunks are not
  embedded and no partial index is committed.

## Re-index and recovery

- To re-embed a document under a new model, re-ingest it. Because the original
  full text is not stored, re-ingestion requires the source text to be supplied
  again. Chunk text already in the store is not lost by model switching.
- The only destructive cleanup offered is removal of obsolete-revision rows. There
  is intentionally no bulk "delete everything from another encoder" action,
  because that is indistinguishable from a valid index the user still wants.
- Per-document Delete removes a document's rows entirely (all encoders) — this is
  an explicit, confirmed user action.

## Tests

- JVM: `RagReIngestTest` (atomic replace, encoder snapshot, mid-ingest encoder
  change abort, commit-aware counts, cancellation propagation, oversized reject),
  `KnowledgePackStatusTest` (partial pack is not indexed, single-pass prefix
  delete), `DocumentIngestMessagingTest`.
- Device (androidTest, executed only on device/emulator):
  `VectorStoreRevisionMigrationTest` — v1→v3 migration, obsolete-row reclamation,
  and the A → B → A survival contract.
