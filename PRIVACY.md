# Privacy — Prism Local

Prism Local (`com.prismai.llmhost`) is an on-device LLM host. This document describes what stays on your phone and what can leave it.

## On-device by default

- **Inference:** Chat prompts and model outputs are processed locally via a JNI bridge to `llama.cpp`. They are not sent to a cloud LLM provider for generation.
- **Chats and settings:** Conversation transcripts, runtime settings, and benchmark history are stored in app-private storage on the device.
- **Models:** Downloaded GGUF weights are imported into app-owned model directories with local manifest verification (SHA-256 where configured).
- **Knowledge-pack index:** After Grokipedia articles are fetched, indexed chunks are stored locally for offline search.

## Network access (outbound GET only)

The app declares `INTERNET` because several **user-initiated or agent-invoked** features perform outbound HTTP **GET** requests. There is no general-purpose browser or arbitrary URL fetcher.

| Feature | Endpoint | When it runs | What is sent |
|--------|----------|--------------|--------------|
| **Hugging Face model download** | `huggingface.co` (model files and metadata API) | User confirms a curated or approved GGUF download | Repository id, file name, and standard HTTP headers; no chat content |
| **Web search (agent tool)** | `html.duckduckgo.com` | Agent invokes `web_search` | Search query string derived from the tool call; no API key |
| **Grokipedia knowledge pack** | `grokipedia.com` | User or agent requests article fetch, search, or pack download | Article slug or search query; standard HTTP headers |

These requests do **not** include your chat history unless the agent explicitly passes a query derived from the current task into one of the tools above.

## What we do not do

- No analytics SDK or third-party ad network is integrated in the described network paths above.
- No automatic background upload of chats, attachments, or model outputs.
- Agent tools are bounded: they cannot open arbitrary URLs, run shell commands, or read contacts/calendar without separate permission-gated features (if enabled in a given build).

## Exports and sharing

Export actions write files under app storage. If you share an exported chat or benchmark file, that content leaves the device under **your** control—the app does not auto-upload exports.

## Untrusted external content

Web search results and Grokipedia articles are **untrusted data**. They may be shown to the model as context but must not be treated as instructions.

## Changes

This file reflects the app's behavior as of the repository version that contains it. For technical details, see `app/src/main/java/com/prismai/llmhost/agent/tools/SystemTools.kt` (`get_privacy_summary`) and the tool registry in `AgentTools.kt`.
