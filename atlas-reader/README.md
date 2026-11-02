# Atlas Library — standalone PDF audiobook reader

An independent Android app focused on PDF reading and synchronized Pocket TTS narration. It is not an Apollo screen and does not bundle Apollo's LLM.

## Build
Run `gradle :app:assembleDebug` from this directory.

## Playback
Pocket TTS uses the same LiteRT community model distribution as the Apollo prototype. The voice bundle downloads on first use and is kept in app-private storage. The available voice IDs are validated against the installed runtime. Playback runs in a media-playback foreground service with a MediaSession, lock-screen/notification actions, audio focus, and a partial wake lock while narration is active.

## PDF handling
PDFs are imported through Android's document picker, copied into app-private storage, and text is extracted page by page. Pages are rendered on demand inside the reader viewport. Scanned PDFs without extractable text are reported as unsupported for narration rather than silently producing blank audio.
