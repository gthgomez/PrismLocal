# Prompt: Prism Local UI Polish & Upgrade Audit

Copy everything below the line into a fresh agent session (or paste as the task brief for a lead agent that may fan out read-only subagents).

---

## Mission

You are auditing **Prism Local** (`PrismLocal`, package `com.prismai.llmhost`) — an on-device Android GGUF chat app (Kotlin + Jetpack Compose Material3 + foreground inference service).

**Goal:** Produce an evidence-based **UI polish & upgrade backlog**: concrete improvements we *can* add, ranked by impact and cost, grounded only in the current codebase and (if available) running UI — not marketing fantasy.

**Mode:** Research and audit only. **Do not implement** unless the user explicitly asks after delivery.

---

## Product constraints (do not violate in recommendations)

- Local-first inference; UI must stay usable under long-running generation, thermal/battery pressure, and model load.
- Agent tools, downloads, destructive chat actions, and model switches are confirmation-gated in source — do not propose removing those gates.
- Text-only native bridge (images may be metadata-only unless vision paths prove otherwise).
- Preserve high-risk zones: native/JNI, signing, model storage; UI proposals should not require unsafe native changes unless clearly marked **native-dependent**.
- Tree may be dirty; do not “clean up” unrelated files. Read-only audit preferred.
- Working directory: `<workspace>\Project_Android\PrismLocal`.

---

## UI map (start here)

Primary surfaces under `app/src/main/java/com/prismai/llmhost/ui/`:

| Area | Paths |
|---|---|
| Shell / chat | `ChatScreen.kt`, `chat/ChatTopBar.kt`, `chat/ChatListSheet.kt`, `chat/MessageItem.kt` |
| Composer | `composer/PromptComposer.kt` |
| Runtime / settings | `controlplane/ControlPlaneSheet.kt`, `controlplane/RuntimeControls.kt` |
| Theme | `theme/PrismTheme.kt` |
| Shared chrome | `components/SharedUiComponents.kt`, `components/ToolExecutionCard.kt` |
| Markdown / code / tables | `MarkdownText.kt`, `CodeBlockRendering.kt`, `TableRendering.kt` |
| Streaming display | bridge helpers used by UI: `bridge/StreamingTextState.kt`, `Utf8TextPipeline.kt` (display path) |
| Memory / RAG | `memory/MemoryBrowser.kt`, `rag/DocumentBrowser.kt` |
| Benchmark | `benchmark/BenchmarkCenter.kt` |
| Export | `export/ExportSheet.kt` |
| Voice / S-Pen | `voice/VoiceOverlay.kt`, `spen/SPenHoverController.kt` |
| State / events | `ServiceUiState.kt`, `UiEventBus.kt`, `UiFormatUtils.kt` |

Also inspect entry: `MainActivity.kt`, and how `InferenceService` state is collected in `ChatScreen`.

Theme brand tokens: Prism blue/violet/cyan glass aesthetic in `PrismTheme.kt` (light + dark schemes already exist).

---

## Context management: when to use subagents

If context is large, the **lead agent** should stay in synthesis mode and fan out **read-only** subagents. Do **not** give write access to parallel UI explorers.

### Suggested fan-out (parallel, read-only)

| Subagent | Scope | Deliverable back to lead |
|---|---|---|
| **A — Chat chrome** | `ChatScreen`, `ChatTopBar`, `ChatListSheet`, `MessageItem`, composer | Findings on layout, density, empty/loading/error, scroll, streaming UX |
| **B — Control plane & runtime** | `ControlPlaneSheet`, `RuntimeControls`, model/status surfaces | Settings IA, discoverability, dangerous controls, feedback clarity |
| **C — Content rendering** | `MarkdownText`, code/table rendering, `ToolExecutionCard` | Readability, selection/copy, code blocks, long content, a11y |
| **D — Secondary surfaces** | Memory, RAG, Benchmark, Export, Voice, S-Pen | Consistency with main chat, dead ends, polish gaps |
| **E — Design system** | `PrismTheme`, `SharedUiComponents`, color/type usage across UI | Token consistency, dark mode, contrast, reusable components |

Lead agent merges subagent reports, dedupes, and produces the final ranked backlog.

If running **without** subagents, walk the map in the same order (A→E) and keep notes structured the same way.

---

## Research method (evidence-first)

1. **Map, don’t invent.** Open the files above; note what composables exist, what states they bind (`StateFlow`s from service), and what interactions are wired.
2. **Trace user journeys** end-to-end in code:
   - First launch / no model selected  
   - Load model / switch model  
   - Send message → stream tokens → complete / cancel / continue  
   - Attachments  
   - Agent tool confirm / result cards  
   - Chat list / switch / new chat  
   - Control plane: threads, context, temp, agent toggle, KV/Flash if exposed  
   - Memory / RAG / benchmark / export / voice if present  
3. **Compare against current Material3 + Compose patterns** only where the app already uses Compose (do not propose Flutter/React Native).
4. **Optional device pass** (if emulator/device available): screenshot key states; note jank only with evidence. If no device, mark findings **code-only**.
5. **Do not** claim “missing feature” if it already exists under another sheet name — search first (`grep` / codebase search).

---

## Audit dimensions (score each 1–5 with evidence)

For each dimension: **score**, **what’s good**, **gaps**, **file:line or composable refs**.

1. **Visual hierarchy & brand** — Prism theme application, light/dark, glass/bubble language consistency  
2. **Information architecture** — can users find model, settings, chats, agent, memory without hunting?  
3. **Chat readability** — message density, markdown, code, tables, long transcripts, role clarity  
4. **Streaming & progress UX** — cancel, continue, TTFT/TPS metrics, cancelling vs idle, errors  
5. **Composer UX** — send, attach, voice, disabled states, keyboard, multi-line  
6. **Feedback & status** — snackbars/events, model readiness, download/import, thermal/battery  
7. **Empty / loading / error / offline-local states** — every major surface  
8. **Accessibility** — contentDescription, touch targets (~48dp), contrast, focus, semantics  
9. **Motion & polish** — AnimatedVisibility, transitions, over-animation, reduced-motion sensitivity  
10. **Consistency** — spacing, typography roles, buttons, sheets, icons across modules  
11. **Power-user density vs simplicity** — control plane cognitive load; progressive disclosure  
12. **Trust & safety UI** — confirmation dialogs clarity; tool cards; untrusted content framing  

---

## Output format (required)

Deliver a single markdown report with these sections:

### 1. Executive summary
- 5–8 bullets: overall UI maturity, top strengths, top 3 upgrade themes

### 2. Surface inventory
Table: Surface | Primary file(s) | Main states | Notes

### 3. Dimension scores
Table: Dimension | Score 1–5 | One-line justification | Evidence path

### 4. Ranked backlog

For **each** recommendation:

| Field | Content |
|---|---|
| **ID** | `UI-001`, … |
| **Title** | Short |
| **Type** | `Polish` (visual/UX fix on existing) \| `Upgrade` (new capability/surface) \| `Consistency` \| `A11y` \| `Perf-perception` |
| **Problem** | What hurts users now (evidence) |
| **Proposal** | Concrete UI change |
| **Where** | Files/composables to touch |
| **Impact** | H/M/L |
| **Effort** | S/M/L (S ≤ ~0.5d, M ~1–2d, L multi-day / multi-surface) |
| **Risk** | Low/Med/High (esp. if service/native coupled) |
| **Dependencies** | None / service API / native / design assets |
| **Acceptance sketch** | How we’d know it’s done |

Group backlog:

1. **Quick wins** (High impact, S effort)  
2. **Core polish** (High/Med impact, M effort)  
3. **Strategic upgrades** (L effort or new surfaces)  
4. **Park / avoid** (low value or high risk vs product)

### 5. Design system notes
- Token gaps (missing semantic colors, spacing scale, type roles)
- Components that should be extracted vs one-offs
- Dark mode gaps

### 6. Journey heat map
For journeys in Research method §2: **Friction / OK / Strong** + one note each

### 7. Open questions
Ambiguities that need product decision (not invent defaults silently)

### 8. Suggested implementation phases
Propose 2–4 UI phases (e.g. “Chat readability”, “Control plane IA”, “Secondary surface parity”) — **plans only**, no code.

---

## Quality bar

- Every finding cites **file paths** (and line ranges when possible).  
- No generic advice (“use better spacing”) without pointing at a surface.  
- Prefer **specific** proposals (“sticky metrics chip under top bar while `_isGenerating`”, “collapse RuntimeControls behind Advanced”) over slogans.  
- Separate **polish** from **new product features**.  
- If unsure whether a control exists, **search** before listing as missing.  
- Call out Samsung/S-Pen/voice paths only if code shows them; don’t invent OEM features.

---

## Non-goals

- Full redesign from scratch / new design language unrelated to Prism tokens  
- Rewriting inference, JNI, or agent tool policy  
- Play Store listing / marketing copy  
- Implementing the backlog in the same session  

---

## Start command for the lead agent

1. Read `PROJECT_CONTEXT.md` and skim `ui/ChatScreen.kt` + `ui/theme/PrismTheme.kt`.  
2. Fan out subagents A–E (read-only) **or** walk A–E yourself.  
3. Merge, rank, and emit the report in the **Output format** above.  
4. Stop after the report; do not open implementation PRs unless asked.

---

*End of prompt*
