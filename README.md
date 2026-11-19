# Claude Agents Team Muse

فريق وكلاء ذكاء اصطناعي على أندرويد: كل وكيل بمفتاح API رسمي، والمنسّق يوزّع العمل ويبدّل تلقائياً عند أي توقف.
An Android app that coordinates a team of AI agents (each with its own official API key) with automatic failover,
resumable tasks, a chat with coordination modes, and a local OpenAI/Anthropic-compatible gateway.

> This project is the evolution of *Claude Relay*. Routing ideas (strategies, per-key tracking, daily-quota handling,
> sticky sessions, fusion, unified endpoint, analytics) were inspired by the MIT-licensed
> [FreeLLMAPI](https://github.com/tashfeenahmed/freellmapi); the code here is an independent Kotlin implementation.

## Highlights
- **Providers:** Claude, Gemini, Groq, OpenRouter, Mistral, Cerebras, Hugging Face, NVIDIA NIM, Cohere, Z.ai, SambaNova,
  **Custom OpenAI-compatible** (Ollama, LM Studio, llama.cpp, vLLM), plus a manual web agent.
- **Smart routing:** strategies *Balanced / Priority / Fastest / Most reliable*, latency (EWMA) and success tracking,
  exponential backoff with Retry-After, **daily-quota cooldown until UTC midnight**, auto-disable on rejected keys.
- **Local gateway:** `/v1/chat/completions`, `/v1/messages`, `/v1/models` with one unified key, `auto`, `auto:fast`,
  `auto:reliable`, `auto:priority`, `auto:vision`, `X-Routed-Via` header, foreground service while running.
- **Chat:** auto / compare+merge (fusion) / draft→critique→refine, sticky sessions with handoff, response cache,
  prompt compression, `/task <goal>` to start a full checkpointed task.
- **Tasks:** plan → steps → final answer with a checkpoint after every step, coding mode (file blocks → ZIP), deep thinking,
  attachments (text, images, PDF, ZIP, video frames), projects memory and export.
- **Analytics:** per-agent requests, success %, latency, estimated tokens; one-tap key health check.
- **Connectors:** GitHub, Google sign-in (Credential Manager), Slack/Discord webhook, Telegram, Notion, ntfy.
- **GitHub upload:** compare → secret scan → one atomic commit (no force push) → verify the Actions build.
- **Security:** API keys/tokens encrypted with Android Keystore (AES-256-GCM); encrypted backups.

## Build
AGP 8.2.2 · Kotlin 1.9.22 · Compose compiler 1.5.8 · BOM 2024.02.00 · Gradle 8.5 · JDK 17 · minSdk 26.
GitHub Actions builds `assembleDebug` and runs the unit tests; download `app-debug` from the run's Artifacts.

Application id: `com.claudeagents.team.muse` (installs side by side with the old Claude Relay).

## Gateway notes
Runs inside the app (127.0.0.1 by default; LAN only if you enable it). Streaming is sent in one burst;
tool calling and images are not supported by the gateway.
