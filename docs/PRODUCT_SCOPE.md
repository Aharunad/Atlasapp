# Atlas Library Standalone — Product Scope

This project is an independent Android reading and personal knowledge application. It is not an Apollo screen or Apollo-branded build.

## Product principles

- White, restrained, typography-led interface.
- Stable app-shell navigation: the primary menu is anchored to the same corner/edge at every screen size and scroll position. It must never be implemented as a draggable/floating overlay.
- Offline-first for imported books, saved articles, notes, highlights, bookmarks, and reading progress.
- Every visible control must have a working action, a meaningful disabled state, or be omitted until implemented.
- Target entry-level Android devices: avoid keeping whole books or large article collections in memory.

## Feature scope

### 1. Reading formats and import
- Import PDF, EPUB, TXT, and Markdown through Android's Storage Access Framework.
- Extract text off the UI thread; preserve page/chapter boundaries and metadata.
- PDF fallback for scanned pages: detect image-only pages and clearly report that OCR is required rather than displaying blank text as a successful import.
- Import web articles by URL and save a clean, readable offline copy.
- Validate MIME type, file size, malformed archives, encrypted PDFs, and unsupported formats.
- Keep source files and extracted text in app-private storage; support export and deletion.

### 2. Reader experience
- Page/chapter navigation, table of contents, progress indicator, continue reading, and reading history.
- Bookmarks, highlights in multiple colors, annotations, notes, and in-document search.
- Font family/size, line height, paragraph spacing, margins, alignment, themes, and screen-awake preference.
- Persistent per-book position and global reading preferences.
- Rotation, small-screen layouts, Android back navigation, and process recreation must preserve state.

### 3. RSS and magazine reader
- Add, edit, refresh, pause, and remove RSS/Atom feeds.
- Parse RSS 2.0, RSS 1.0/RDF, and Atom; handle malformed feeds and redirects safely.
- Feed folders, unread/read state, saved articles, search, date/source filters, and refresh status.
- Fetch article pages, extract readable content, and save for offline use.
- Deduplicate by canonical URL/GUID and retain publication metadata.
- Refresh on demand first; optional background refresh must respect Android battery/network constraints.
- Never execute feed HTML or scripts inside the trusted app WebView.

### 4. Speech and TTS
- Integrate the exact Kyutai Pocket TTS distribution/version selected for the Apollo project only after verifying the actual model artifact, license, runtime, ABI support, and memory requirements.
- Keep the speech engine behind a provider interface so the app can use Pocket TTS when available and Android system TTS as an explicit fallback.
- Do not label Android system TTS as Pocket TTS.
- Provide voice selection when supported, speed, pitch where supported, play/pause/resume/stop, sentence/chunk progress, and automatic continuation across pages/articles.
- Read books and RSS articles using the same playback queue and stop state.
- Background playback requires a media playback foreground service, notification controls, audio-focus handling, and correct cleanup.
- Chunk long passages, preserve sentence boundaries, and prevent overlapping utterances.
- Expose engine/version/status and explain when a requested voice is unavailable.

### 5. Personal knowledge tools
- Library-wide search across titles, authors, extracted text, notes, and saved articles.
- AI-assisted summaries and explanations only when a configured model/provider is actually available; show source passages and never fabricate citations.
- Chapter summaries, vocabulary lookup, and selected-text actions.
- Keep AI features optional and separate from core offline reading.

### 6. Library organization
- Collections, folders/tags, favourites, recently opened, reading status, and custom sorting.
- Metadata editing, cover thumbnails, duplicate detection, storage usage, bulk selection, export, and deletion.
- Database-backed indexing and migrations; do not rely on transient browser state as the source of truth.

## Architecture

- Android host: Kotlin, Activity Result APIs, app-private storage, Room for structured metadata, WorkManager for constrained background sync, and a foreground media service for playback.
- Presentation: responsive web UI hosted in a tightly scoped WebView, with a fixed app shell and a narrow, validated JavaScript bridge.
- Core modules: library repository, import pipeline, document extraction, reader state, feed repository/parser, article extraction, search index, speech provider, playback coordinator, and settings repository.
- Use one playback coordinator for book and feed reading so stop/pause/session ownership is deterministic.
- Keep long-running parsing, indexing, downloads, and speech synthesis off the main thread.
- Validate all bridge inputs; restrict network fetches to HTTPS, cap response sizes, defend against redirect abuse/SSRF, and never expose arbitrary filesystem paths to JavaScript.

## Release gates — no feature is complete until it passes

1. Build a clean debug APK from the standalone branch.
2. Install and launch on a real Android device.
3. Verify anchored navigation on narrow and wide screens, portrait/landscape, and after scrolling.
4. Import representative PDF, EPUB, TXT, and Markdown files; reopen after force-stop.
5. Verify progress, bookmarks, highlights, notes, and settings persist after restart.
6. Add RSS and Atom feeds, refresh, open an article, save offline, and read it aloud.
7. Verify TTS interruption, resume, end-of-page continuation, background controls, audio focus, and no overlapping speech.
8. Test malformed files/feeds, network loss, denied permissions, cancellation, low storage, and oversized inputs.
9. Run on a 4 GB RAM device and measure startup, import, search, and playback memory.
10. Ensure every visible control is wired and no placeholder action falsely reports success.

## Explicit implementation note

The Apollo source inspected on 2026-11-02 currently delegates its reader speech to Android's selected system TextToSpeech engine. That is not proof that Kyutai Pocket TTS is bundled or running. Atlas Library must verify and package the actual requested Pocket TTS distribution before presenting it as the active engine.


## Mandatory playback interaction contract

The reader's primary playback control is a **single toggle button**, not a play-only button.

- Idle or stopped: show a Play icon; tapping starts the current book/article from the saved position (or selected passage).
- Playing: show a Pause icon; tapping pauses the active utterance and preserves the exact playback position.
- Paused: show a Play icon; tapping resumes from the paused position without restarting the passage.
- Stopped or completed: return to the Play state; the next tap starts from the defined restart/resume position.
- A separate, always-visible **Stop** control remains available. Stop terminates the playback session, clears queued chunks, releases audio focus/resources, and returns the UI to the stopped state. It must not be the only way to pause.
- The toggle's icon, accessible label, and state must be driven by the playback coordinator's authoritative state—not by optimistic UI-only flags.
- Define explicit states: IDLE, PREPARING, PLAYING, PAUSED, STOPPING, STOPPED, COMPLETED, and ERROR. Reject invalid transitions and ignore stale callbacks from earlier utterances/sessions.
- Pause must stop further chunk submission immediately. If the TTS runtime cannot pause an utterance natively, stop the current chunk, retain the unspoken text offset, and resume from that offset; do not replay already-spoken words.
- Stop must be distinct from Pause. Pause retains queue and position; Stop discards the active queue and resets the session according to the reader's documented behavior.
- The same toggle and Stop behavior must work for PDF/EPUB/TXT/Markdown books and RSS/article reading, including automatic page/chapter continuation.
- Background notification controls and headset/media buttons must invoke the same coordinator transitions as the in-app controls.
- Show a clear progress indicator and current sentence/chunk. Disable controls only while a real transition is in progress, and expose errors with a retry path.
- Add automated state-machine tests for Play → Pause → Resume → Stop, Pause during synthesis, Stop during queued chunks, completion, TTS error, rapid repeated taps, app background/foreground, and stale completion callbacks.
- Release acceptance: manually verify on a real Android device that tapping the primary button once starts, tapping it again pauses, tapping again resumes at the correct word, and the separate Stop button ends playback. A build or UI screenshot alone does not satisfy this gate.

## Additional quality bar for the complete feature set

- Every settings item must persist across app restart and process death; settings screens must reflect the saved value after reopening.
- Every menu item and visible button must perform its named action. Remove or clearly label unavailable features rather than shipping decorative controls.
- Menus must remain anchored to the app shell and never drift with scroll, keyboard appearance, or navigation.
- Use accessible labels, minimum touch targets, loading/error/empty states, and confirmation for destructive actions.
- Preserve library data during upgrades with versioned database migrations and tested backup/export paths.


## Document-pane scrolling and editing interaction

- The document viewport is its own independently scrollable pane. Vertical swipes over the PDF/EPUB/text page scroll the document only; they must not move the outer app shell, header, navigation, or settings.
- Keep app chrome anchored and stationary while the reader pane scrolls. The reader toolbar may remain sticky; only the document content region owns the vertical scroll.
- Avoid nested competing scroll containers. Define one clear scroll owner for the active document and ensure touch gestures begun inside the document stay within it.
- On touch devices, support natural vertical scrolling, text selection, copy/share, highlight, annotation, and bookmark actions without hijacking ordinary scroll gestures.
- If a PDF is displayed as rendered pages, scroll between pages inside the PDF viewport; if text-reflow mode is available, scroll the reflowed text inside the same bounded pane.
- Preserve current page, scroll offset, zoom, and reading position through rotation, opening/closing menus, changing settings, and process recreation.
- Test long documents, very short documents, landscape, keyboard appearance, accessibility font scaling, and rapid scroll/tap interactions.

## Pocket TTS implementation and performance requirements

### Runtime selection and truthfulness
- The requested engine is Kyutai Pocket TTS, approximately 100M parameters. The official upstream package is Python/PyTorch; it is not an Android library that can simply be embedded in the APK.
- Evaluate a maintained Android-compatible runtime such as the community LiteRT conversion/Kotlin API. Treat it as a community conversion, pin an exact release/commit and model checksum, review its license and attribution, and test its outputs against known upstream samples.
- Do not claim official Kyutai Android support where it is a third-party port.
- The engine must expose a health check, model/version identifier, selected voice, warm/cold state, and actionable failure details. The UI must clearly distinguish Pocket TTS from Android system TTS fallback.

### Voice catalogue
- Provide a searchable voice picker populated from a versioned, verified voice catalogue rather than hard-coded UI labels.
- Include every voice supported by the selected model/runtime pack, with name, language, preview, availability/download state, and attribution/license details.
- Upstream catalogue observed during implementation planning includes: Alba, Giovanni, Lola, Jürgen, Rafael, Estelle, Anna, Azelma, Bill Boerst, Caro Davy, Charles, Cosette, Eponine, Eve, Fantine, George, Jane, Jean, Javert, Marius, Mary, Michael, Paul, Peter Yearsley, Stuart Bell, and Vera. The shipped Android pack may expose a smaller subset; never display a voice as selectable unless its embedding/reference assets are actually bundled or downloadable and validated.
- Allow importing a user's own voice reference audio only if the chosen runtime supports it; validate format, duration, sample rate, size, and explicit consent. Explain that voice cloning requires rights/permission and must not be used to impersonate someone without consent.
- Cache prepared voice states/embeddings per voice where supported so switching voices does not repeatedly perform expensive preprocessing.

### Streaming, speed, and memory
- Stream audio in short sentence-aware chunks; begin playback as soon as the first valid chunk is ready and synthesize the next chunk ahead of playback.
- Keep one model instance warm for a playback session; avoid reloading the model for every sentence, page, or article.
- Preload and cache selected voice state, reuse buffers, bound the queue, and apply backpressure so long books cannot exhaust memory.
- Run synthesis on a dedicated background executor; stream PCM to AudioTrack with correct buffer sizing, sample-rate handling, underrun recovery, and audio-focus management.
- Benchmark CPU/XNNPACK and any supported GPU delegate on actual target devices. Do not assume GPU is faster: Pocket TTS upstream reports no general GPU speedup for its small batch-1 model, while community LiteRT conversions report device-dependent results.
- Add a user-visible quality/performance mode only if each mode has been measured and validated; never silently reduce quality or change the voice.
- Provide warm-up after model initialization, but do not block app startup. Report first-audio latency and real-time factor (audio seconds generated per wall-clock second).

### Required TTS validation before release
- Run an automated smoke test that loads the actual packaged/downloaded model, loads every advertised voice, synthesizes known short phrases, checks finite PCM samples/non-empty output, verifies expected sample rate/channels, and plays the result through the Android audio output path.
- Compare selected test phrases with reference outputs for truncation, clipping, silence, corrupted audio, and voice-selection regressions.
- Test first-run download, checksum validation, interrupted download/resume, insufficient storage, corrupted model repair, offline startup, model upgrade, and rollback.
- Test long-form reading for at least 30 minutes across multiple document pages/articles, with pause/resume/stop, background playback, screen lock, audio focus interruptions, Bluetooth/headset controls, and app lifecycle changes.
- Track time-to-first-audio, real-time factor, underruns, dropped chunks, memory peak, CPU use, and thermal throttling. Test cold and warm starts.
- Verify the selected voice actually changes the synthesized audio; verify every listed voice individually. A UI picker test alone is insufficient.
- Release gate: no claim of "working" or "high speed" until the exact Android runtime and model pass the smoke suite and are exercised on a physical target-class Android phone. If Pocket TTS fails, show a truthful fallback status and preserve reading via the separately identified Android TTS provider.


## Background audiobook and explicit-exit lifecycle contract

- Audio playback must continue uninterrupted when the user presses Home, switches to another app, opens the notification shade, or turns the screen off/locks the phone. The PDF reader UI may stop being visible; the audio session must remain active.
- Implement playback as an Android media-playback foreground service with a persistent media notification, media-session integration, notification Play/Pause/Stop actions, headset/Bluetooth media-button support, and correct audio-focus/noisy-audio handling. Do not rely on an Activity, WebView, or JavaScript timer remaining alive.
- The playback coordinator and current document/audio position must outlive the reader screen and Activity recreation. Reopening Atlas Library must reconnect to the existing session and show the correct playing/paused state and position.
- Define explicit exit semantics: normal backgrounding is not exit. Provide a deliberate in-app Exit action that ends playback, clears pending synthesis/audio chunks, abandons audio focus, releases the player/model resources as appropriate, removes the media notification, and closes the app session. The separate Stop button ends audio but does not need to close the app.
- Treat an explicit dismissal of the app task from Android Recents as an exit if that is the product's chosen behaviour; implement and test the corresponding task-removal lifecycle handling. Do not accidentally stop playback on ordinary Activity pause/stop callbacks. Document the chosen Recents behaviour in the UI/help text.
- Android force-stop, device shutdown, and OS process termination are exceptional cases and may terminate playback; do not promise playback survives force-stop. Persist enough state to recover the document and last confirmed reading position when the user opens the app again.
- Keep the foreground-service notification truthful and actionable, including document title and current playback state. Request only the media playback foreground-service permissions required by the target Android SDK; handle notification permission denial without silently falling back to Activity-bound playback.
- Release tests on physical Android devices: press Home while playing; switch between apps; lock/unlock the screen; use notification and headset controls; reopen the reader and verify synchronized page/sentence position; explicitly Exit and verify audio stops and notification disappears; dismiss from Recents and verify the documented behaviour; force-stop and verify honest recovery behaviour on next launch.
