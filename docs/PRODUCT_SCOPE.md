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
