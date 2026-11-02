(() => {
  "use strict";
  const $ = (selector, root = document) => root.querySelector(selector);
  const api = window.AtlasNative;
  const pending = new Map();
  const voiceNames = { alba:"Alba", marius:"Marius", javert:"Javert", jean:"Jean", fantine:"Fantine", cosette:"Cosette", eponine:"Eponine", azelma:"Azelma" };
  const state = {
    books: [], book: null, page: 1, pageCount: 1, zoom: 1,
    playback: "IDLE", followVoice: true, voice: "alba", rate: 1,
    observer: null, imageObserver: null, toastTimer: null, autoRead: false
  };

  const id = prefix => prefix + "_" + (crypto.randomUUID ? crypto.randomUUID() : Date.now() + "_" + Math.random().toString(16).slice(2));

  function native(method, ...args) {
    if (!api || typeof api[method] !== "function") return Promise.reject(new Error("The Android reader service is unavailable."));
    const requestId = id("atlas");
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        pending.delete(requestId);
        reject(new Error("Android did not respond in time. Please try again."));
      }, method === "renderPage" ? 120000 : 30000);
      pending.set(requestId, { resolve, reject, timer });
      try { api[method](requestId, ...args); }
      catch (error) {
        clearTimeout(timer);
        pending.delete(requestId);
        reject(new Error(error?.message || "The Android operation failed."));
      }
    });
  }

  window.atlasNativeResult = result => {
    const item = pending.get(result?.requestId);
    if (!item) return;
    pending.delete(result.requestId);
    clearTimeout(item.timer);
    if (!result.ok) item.reject(new Error(result.error || "The operation failed."));
    else item.resolve(result);
  };

  window.atlasNativeEvent = event => {
    if (!event || typeof event !== "object") return;
    switch (event.type) {
      case "state":
        setPlaybackState(event.state || "IDLE");
        if (event.page) updatePage(event.page, false);
        if (event.state === "COMPLETED") {
          state.autoRead = false;
          showToast("Finished reading.");
        }
        break;
      case "download": {
        const total = Number(event.total || 0);
        const downloaded = Number(event.downloaded || 0);
        const percent = total > 0 ? Math.min(100, Math.round(downloaded * 100 / total)) : 0;
        $("#downloadLine").hidden = false;
        $("#downloadProgress").style.width = percent + "%";
        $("#downloadText").textContent = total > 0
          ? "Preparing Pocket TTS · " + percent + "%"
          : (event.message || "Preparing Pocket TTS…");
        $("#playbackStatus").textContent = "PREPARING POCKET TTS";
        break;
      }
      case "prepared":
        $("#downloadLine").hidden = true;
        showToast("Pocket TTS is ready.");
        break;
      case "voice-ready":
        $("#playbackStatus").textContent = (event.voiceName || "Voice") + " ready";
        break;
      case "page":
        updatePage(event.page, true);
        if (event.title) $("#playerTitle").textContent = event.title;
        $("#playerSubtitle").textContent = "Page " + event.page + " of " + event.pageCount;
        break;
      case "page-complete":
        break;
      case "preview-complete":
        setPlaybackState("COMPLETED");
        $("#playerSubtitle").textContent = "Voice preview complete";
        showToast("Voice preview finished.");
        break;
      case "completed":
        setPlaybackState("COMPLETED");
        state.autoRead = false;
        $("#playerSubtitle").textContent = "Finished · " + (event.pageCount || state.pageCount) + " pages";
        $("#downloadLine").hidden = true;
        break;
      case "stopped":
        setPlaybackState("STOPPED");
        state.autoRead = false;
        break;
      case "notice":
        showToast(event.message || "Check Android notification permission for lock-screen controls.");
        break;
      case "error":
        setPlaybackState("ERROR");
        state.autoRead = false;
        $("#downloadLine").hidden = true;
        showToast(event.message || "Pocket TTS encountered an error.");
        break;
      default:
        break;
    }
  };

  function escapeHtml(value) {
    return String(value ?? "").replace(/[&<>"']/g, char => ({
      "&":"&amp;", "<":"&lt;", ">":"&gt;", '"':"&quot;", "'":"&#39;"
    })[char]);
  }

  function showToast(message) {
    const toast = $("#toast");
    toast.textContent = String(message || "");
    toast.classList.add("show");
    clearTimeout(state.toastTimer);
    state.toastTimer = setTimeout(() => toast.classList.remove("show"), 2800);
  }

  function setPlaybackState(value) {
    state.playback = value;
    const playing = value === "PLAYING";
    const paused = value === "PAUSED";
    const preparing = value === "PREPARING";
    const active = playing || paused || preparing;
    $("#playToggle").textContent = playing ? "Ⅱ" : "▶";
    $("#playToggle").setAttribute("aria-label", playing ? "Pause" : "Play");
    $("#playToggle").disabled = value === "STOPPING";
    $("#liveDot").classList.toggle("active", playing);
    $("#playbackStatus").textContent =
      playing ? "NOW READING" :
      paused ? "PAUSED" :
      preparing ? "PREPARING AUDIO" :
      value === "COMPLETED" ? "FINISHED" :
      value === "ERROR" ? "AUDIO ERROR" :
      value === "STOPPED" ? "STOPPED" : "READY TO LISTEN";
    if (!active) $("#downloadLine").hidden = true;
  }

  async function refreshBooks() {
    try {
      const result = await native("listBooks");
      state.books = result.data.books || [];
      renderBooks();
    } catch (error) {
      showToast(error.message);
    }
  }

  function renderBooks() {
    $("#bookCount").textContent = state.books.length + (state.books.length === 1 ? " document" : " documents");
    const list = $("#bookList");
    if (!state.books.length) {
      list.innerHTML = '<div class="empty-state"><div class="empty-glyph">Aa</div><strong>Your shelf is waiting.</strong><p>Import a PDF to begin reading.</p></div>';
      return;
    }
    list.innerHTML = state.books.map(book => {
      const percent = book.pageCount > 0 ? Math.round((Math.max(1, book.lastPage || 1) - 1) * 100 / book.pageCount) : 0;
      return '<article class="book-card" data-id="' + escapeHtml(book.id) + '">' +
        '<div class="book-card-head"><div class="book-cover">Aa</div><div class="book-details">' +
        '<strong>' + escapeHtml(book.title || "Untitled PDF") + '</strong>' +
        '<span>' + Number(book.pageCount || 0) + ' pages · ' + Number(book.wordCount || 0).toLocaleString() + ' words</span>' +
        '</div></div><div class="book-progress"><span style="width:' + percent + '%"></span></div>' +
        '<div class="book-actions"><button class="open-book" data-action="open">Open reader</button>' +
        '<button data-action="listen">Listen</button><button data-action="delete">Delete</button></div></article>';
    }).join("");
    list.querySelectorAll("[data-action]").forEach(button => {
      button.addEventListener("click", event => {
        event.stopPropagation();
        const card = button.closest(".book-card");
        const book = state.books.find(item => item.id === card?.dataset.id);
        if (!book) return;
        if (button.dataset.action === "delete") deleteBook(book);
        else if (button.dataset.action === "listen") openBook(book, true);
        else openBook(book, false);
      });
    });
  }

  async function importPdf() {
    try {
      showToast("Choose a PDF from your device.");
      const result = await native("pickPdf");
      if (result.type === "imported") {
        const book = result.data;
        showToast(book.pagesWithText > 0 ? "PDF imported successfully." : "Imported, but this PDF appears to be scanned and has no selectable text.");
        await refreshBooks();
        if (book.pagesWithText > 0) openBook(book, false);
      } else if (result.type !== "cancelled") {
        showToast("PDF import finished.");
      }
    } catch (error) {
      showToast(error.message);
    }
  }

  async function deleteBook(book) {
    if (!confirm('Remove "' + (book.title || "this PDF") + '" from Atlas Library?')) return;
    try {
      await native("deleteBook", book.id);
      if (state.book?.id === book.id) closeReader();
      await refreshBooks();
      showToast("PDF removed from your library.");
    } catch (error) {
      showToast(error.message);
    }
  }

  async function openBook(book, startListening) {
    state.book = book;
    state.pageCount = Number(book.pageCount || 1);
    state.page = Math.max(1, Math.min(state.pageCount, Number(book.lastPage || 1)));
    state.zoom = 1;
    state.autoRead = false;
    $("#homeView").hidden = true;
    $("#readerView").hidden = false;
    $("#readerTitle").textContent = book.title || "Untitled PDF";
    $("#playerTitle").textContent = book.title || "Untitled PDF";
    $("#playerSubtitle").textContent = book.pagesWithText > 0 ? "Ready to read or listen" : "Scanned PDF · narration unavailable";
    $("#pageScrub").max = String(state.pageCount);
    $("#pageScrub").value = String(state.page);
    $("#pageCounter").textContent = state.page + " / " + state.pageCount;
    $("#readerPageMeta").textContent = state.pageCount + " pages";
    $("#pageStack").innerHTML = Array.from({length: state.pageCount}, (_, index) => {
      const page = index + 1;
      return '<div class="pdf-page" id="pdf-page-' + page + '" data-page="' + page + '">' +
        '<div class="page-placeholder">Loading page ' + page + '…</div><span class="page-number">' + page + '</span></div>';
    }).join("");
    $("#documentPane").scrollTop = 0;
    setPlaybackState("IDLE");
    setupPageObservers();
    await native("saveProgress", book.id, state.page).catch(() => {});
    await ensurePageImage(state.page);
    scrollToPage(state.page, false);
    if (startListening && book.pagesWithText > 0) await startReading(state.page);
  }

  function closeReader() {
    $("#readerView").hidden = true;
    $("#homeView").hidden = false;
    state.book = null;
    state.autoRead = false;
    state.observer?.disconnect();
    state.imageObserver?.disconnect();
    refreshBooks();
  }

  function setupPageObservers() {
    state.observer?.disconnect();
    state.imageObserver?.disconnect();
    const pane = $("#documentPane");
    state.imageObserver = new IntersectionObserver(entries => {
      entries.forEach(entry => {
        if (entry.isIntersecting) {
          const page = Number(entry.target.dataset.page);
          ensurePageImage(page);
        }
      });
    }, { root: pane, rootMargin: "500px 0px", threshold: 0.01 });
    state.observer = new IntersectionObserver(entries => {
      const visible = entries.filter(entry => entry.isIntersecting)
        .sort((a, b) => b.intersectionRatio - a.intersectionRatio)[0];
      if (visible && state.book) {
        const page = Number(visible.target.dataset.page);
        if (page && page !== state.page) {
          state.page = page;
          updatePageLabels();
          native("saveProgress", state.book.id, page).catch(() => {});
        }
      }
    }, { root: pane, threshold: [0.2, 0.45, 0.65, 0.85] });
    document.querySelectorAll(".pdf-page").forEach(element => {
      state.imageObserver.observe(element);
      state.observer.observe(element);
      element.addEventListener("click", () => {
        if (state.book?.pagesWithText > 0) startReading(Number(element.dataset.page));
      });
    });
  }

  const imageRequests = new Map();
  async function ensurePageImage(page) {
    if (!state.book || page < 1 || page > state.pageCount) return;
    const element = $("#pdf-page-" + page);
    if (!element || element.dataset.loaded === "true" || element.dataset.loading === "true") return;
    element.dataset.loading = "true";
    try {
      const result = await native("renderPage", state.book.id, page);
      const image = document.createElement("img");
      image.alt = "PDF page " + page;
      image.loading = "lazy";
      image.src = result.data.dataUrl;
      image.style.transform = "scale(" + state.zoom + ")";
      element.querySelector(".page-placeholder")?.remove();
      element.prepend(image);
      element.dataset.loaded = "true";
    } catch (error) {
      element.querySelector(".page-placeholder").textContent = "Could not render this page. Tap to retry.";
      element.dataset.loading = "false";
    }
  }

  function updatePage(page, fromVoice) {
    if (!state.book) return;
    state.page = Math.max(1, Math.min(state.pageCount, Number(page) || 1));
    updatePageLabels();
    ensurePageImage(state.page);
    native("saveProgress", state.book.id, state.page).catch(() => {});
    if (fromVoice && state.followVoice) scrollToPage(state.page, true);
    if (fromVoice) {
      document.querySelectorAll(".pdf-page.current-speaking").forEach(el => el.classList.remove("current-speaking"));
      $("#pdf-page-" + state.page)?.classList.add("current-speaking");
    }
  }

  function updatePageLabels() {
    $("#pageScrub").value = String(state.page);
    $("#pageCounter").textContent = state.page + " / " + state.pageCount;
    $("#readerPageMeta").textContent = "Page " + state.page + " of " + state.pageCount;
    $("#playerSubtitle").textContent = "Page " + state.page + " of " + state.pageCount;
  }

  function scrollToPage(page, smooth) {
    const pane = $("#documentPane");
    const element = $("#pdf-page-" + page);
    if (!pane || !element) return;
    ensurePageImage(page);
    const paneRect = pane.getBoundingClientRect();
    const elementRect = element.getBoundingClientRect();
    const top = pane.scrollTop + elementRect.top - paneRect.top - 8;
    pane.scrollTo({ top: Math.max(0, top), behavior: smooth ? "smooth" : "auto" });
  }

  async function startReading(page) {
    if (!state.book) return;
    if (state.book.pagesWithText <= 0) {
      showToast("This PDF has no selectable text. Pocket TTS cannot narrate a scanned image-only PDF.");
      return;
    }
    state.page = Math.max(1, Math.min(state.pageCount, Number(page) || 1));
    state.autoRead = true;
    updatePageLabels();
    $("#downloadLine").hidden = false;
    $("#downloadText").textContent = "Preparing Pocket TTS if this is the first use…";
    setPlaybackState("PREPARING");
    try {
      await native("playBook", state.book.id, state.page, state.book.title || "Your PDF", state.voice, state.rate);
    } catch (error) {
      state.autoRead = false;
      setPlaybackState("ERROR");
      $("#downloadLine").hidden = true;
      showToast(error.message);
    }
  }

  async function togglePlayback() {
    try {
      if (state.playback === "PLAYING") {
        await native("pausePlayback");
        setPlaybackState("PAUSED");
      } else if (state.playback === "PAUSED") {
        await native("resumePlayback");
        setPlaybackState("PLAYING");
      } else {
        await startReading(state.page);
      }
    } catch (error) {
      showToast(error.message);
    }
  }

  async function stopPlayback() {
    state.autoRead = false;
    try {
      await native("stopPlayback");
      setPlaybackState("STOPPED");
    } catch (error) {
      showToast(error.message);
    }
  }

  async function saveSettings() {
    try {
      await native("saveSettings", JSON.stringify({
        voice: state.voice, rate: state.rate, followVoice: state.followVoice
      }));
    } catch (error) {
      showToast(error.message);
    }
  }

  async function loadSettings() {
    try {
      const result = await native("getSettings");
      const data = result.data;
      state.voice = data.voice || "alba";
      state.rate = Number(data.rate || 1);
      state.followVoice = data.followVoice !== false;
      const select = $("#voiceSelect");
      select.innerHTML = (data.voices || []).map(voice =>
        '<option value="' + escapeHtml(voice.id) + '">' + escapeHtml(voice.name) + '</option>'
      ).join("");
      if (!select.options.length) {
        select.innerHTML = Object.keys(voiceNames).map(key => '<option value="' + key + '">' + voiceNames[key] + '</option>').join("");
      }
      select.value = state.voice;
      $("#speedSelect").value = String(state.rate);
      $("#followToggle").classList.toggle("active", state.followVoice);
      $("#followToggle").setAttribute("aria-pressed", String(state.followVoice));
      if (data.modelReady) $("#playbackStatus").textContent = "POCKET TTS READY";
    } catch (_) {
      $("#voiceSelect").innerHTML = Object.keys(voiceNames).map(key => '<option value="' + key + '">' + voiceNames[key] + '</option>').join("");
    }
  }

  async function previewVoice() {
    try {
      await native("previewVoice", state.voice, state.rate);
      setPlaybackState("PREPARING");
      $("#playerSubtitle").textContent = "Previewing " + (voiceNames[state.voice] || state.voice);
    } catch (error) {
      showToast(error.message);
    }
  }

  function bindEvents() {
    $("#importButton").addEventListener("click", importPdf);
    $("#emptyImport").addEventListener("click", importPdf);
    $("#backButton").addEventListener("click", closeReader);
    $("#playToggle").addEventListener("click", togglePlayback);
    $("#stopButton").addEventListener("click", stopPlayback);
    $("#followToggle").addEventListener("click", async () => {
      state.followVoice = !state.followVoice;
      $("#followToggle").classList.toggle("active", state.followVoice);
      $("#followToggle").setAttribute("aria-pressed", String(state.followVoice));
      await saveSettings();
      if (state.followVoice) scrollToPage(state.page, true);
    });
    $("#voiceSelect").addEventListener("change", async event => {
      state.voice = event.target.value;
      await saveSettings();
      showToast("Voice set to " + (voiceNames[state.voice] || state.voice) + ".");
    });
    $("#speedSelect").addEventListener("change", async event => {
      state.rate = Number(event.target.value);
      await saveSettings();
      showToast("Playback speed updated.");
    });
    $("#previewVoice").addEventListener("click", previewVoice);
    $("#pageScrub").addEventListener("change", async event => {
      const page = Number(event.target.value);
      const wasPlaying = state.playback === "PLAYING" || state.playback === "PAUSED";
      updatePage(page, false);
      scrollToPage(page, true);
      if (wasPlaying) await startReading(page);
    });
    $("#zoomIn").addEventListener("click", () => setZoom(state.zoom + 0.08));
    $("#zoomOut").addEventListener("click", () => setZoom(state.zoom - 0.08));
    window.addEventListener("keydown", event => {
      if (event.key === "Escape" && !$("#readerView").hidden) closeReader();
    });
  }

  function setZoom(value) {
    state.zoom = Math.max(0.8, Math.min(1.35, value));
    document.querySelectorAll(".pdf-page img").forEach(image => {
      image.style.width = (100 / state.zoom) + "%";
      image.style.maxWidth = "none";
      image.style.transform = "scale(" + state.zoom + ")";
      image.style.transformOrigin = "top center";
    });
  }

  async function init() {
    bindEvents();
    await loadSettings();
    await refreshBooks();
  }

  init();
})();