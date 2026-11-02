package com.atlaslibrary.reader

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.webkit.WebViewAssetLoader
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import android.util.Base64

class MainActivity : ComponentActivity() {
    private lateinit var webView: WebView
    private lateinit var bridge: AtlasNativeBridge
    private var pendingPickerRequest: String? = null
    private var receiverRegistered = false

    private val openPdf = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val request = pendingPickerRequest
        pendingPickerRequest = null
        bridge.onPdfPicked(request, uri)
    }

    private val assetLoader by lazy {
        WebViewAssetLoader.Builder()
            .setDomain("appassets.androidplatform.net")
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()
    }

    private val ttsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val json = intent?.getStringExtra(PocketPlaybackService.EVENT_JSON) ?: return
            deliverToWeb("window.atlasNativeEvent", json)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.WHITE
        window.navigationBarColor = Color.WHITE
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        webView = WebView(this).apply {
            setBackgroundColor(Color.WHITE)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.javaScriptCanOpenWindowsAutomatically = false
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    return assetLoader.shouldInterceptRequest(request.url)
                }
            }
            webChromeClient = WebChromeClient()
        }
        bridge = AtlasNativeBridge()
        webView.addJavascriptInterface(bridge, "AtlasNative")
        setContentView(webView)
        ContextCompat.registerReceiver(this, ttsReceiver, IntentFilter(PocketPlaybackService.EVENT), ContextCompat.RECEIVER_NOT_EXPORTED)
        receiverRegistered = true
        webView.loadUrl("https://appassets.androidplatform.net/assets/index.html")
    }

    internal fun launchPdfPicker(requestId: String) {
        if (pendingPickerRequest != null) {
            bridge.respond(requestId, false, "error", error = "A document picker is already open.")
            return
        }
        pendingPickerRequest = requestId
        try {
            openPdf.launch(arrayOf("application/pdf"))
        } catch (t: Throwable) {
            pendingPickerRequest = null
            bridge.respond(requestId, false, "error", error = t.message ?: "Could not open the PDF picker.")
        }
    }

    private fun deliverToWeb(functionName: String, payload: String) {
        if (!::webView.isInitialized) return
        val safe = JSONObject.quote(payload)
        webView.post { webView.evaluateJavascript("$functionName && $functionName(JSON.parse($safe));", null) }
    }

    override fun onDestroy() {
        if (receiverRegistered) {
            runCatching { unregisterReceiver(ttsReceiver) }
            receiverRegistered = false
        }
        if (::bridge.isInitialized) bridge.close()
        if (::webView.isInitialized) {
            webView.removeJavascriptInterface("AtlasNative")
            webView.stopLoading()
            webView.destroy()
        }
        super.onDestroy()
    }

    inner class AtlasNativeBridge {
        private val io = Executors.newSingleThreadExecutor()
        private val booksRoot = File(filesDir, "atlas-library/books").apply { mkdirs() }
        private val preferences by lazy { getSharedPreferences("atlas_library_settings_v1", MODE_PRIVATE) }
        private val pickerBusy = AtomicBoolean(false)
        private val validRequest = Regex("^[A-Za-z0-9._-]{3,128}$")
        private val validBook = Regex("^[A-Za-z0-9_-]{8,64}$")

        @JavascriptInterface
        fun pickPdf(requestId: String) {
            if (!valid(requestId)) return
            runOnUiThread {
                if (!pickerBusy.compareAndSet(false, true)) {
                    respond(requestId, false, "error", error = "The PDF picker is already open.")
                } else {
                    launchPdfPicker(requestId)
                }
            }
        }

        fun onPdfPicked(requestId: String?, uri: Uri?) {
            if (requestId == null) return
            if (uri == null) {
                pickerBusy.set(false)
                respond(requestId, true, "cancelled", JSONObject().put("cancelled", true))
                return
            }
            io.execute {
                var target: File? = null
                try {
                    val displayName = displayName(uri)
                    val id = UUID.randomUUID().toString()
                    target = File(booksRoot, id)
                    val pagesDir = File(target, "pages")
                    if (!pagesDir.mkdirs()) throw IllegalStateException("Could not create private PDF storage.")
                    val pdf = File(target, "source.pdf")
                    contentResolver.openInputStream(uri).use { input ->
                        requireNotNull(input) { "Android could not open this PDF." }
                        FileOutputStream(pdf).buffered().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var total = 0L
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                total += n
                                if (total > 120L * 1024L * 1024L) throw IllegalArgumentException("PDFs larger than 120 MB are not supported.")
                                output.write(buffer, 0, n)
                            }
                        }
                    }
                    PDFBoxResourceLoader.init(applicationContext)
                    var pageCount = 0
                    var pagesWithText = 0
                    var wordCount = 0
                    var pdfTitle = displayName.removeSuffix(".pdf").ifBlank { "Untitled PDF" }
                    PDDocument.load(pdf).use { document ->
                        require(!document.isEncrypted) { "Password-protected PDFs are not supported yet." }
                        pageCount = document.numberOfPages
                        require(pageCount in 1..2000) { "This PDF must contain between 1 and 2,000 pages." }
                        val infoTitle = document.documentInformation?.title?.trim().orEmpty()
                        if (infoTitle.isNotBlank()) pdfTitle = infoTitle
                        for (page in 1..pageCount) {
                            val stripper = PDFTextStripper().apply {
                                sortByPosition = true
                                startPage = page
                                endPage = page
                            }
                            val text = stripper.getText(document).replace("\u0000", "").trim()
                            if (text.isNotBlank()) pagesWithText++
                            wordCount += text.split(Regex("\\s+")).count { it.isNotBlank() }
                            File(pagesDir, "$page.txt").writeText(text, Charsets.UTF_8)
                        }
                    }
                    val metadata = JSONObject()
                        .put("id", id)
                        .put("title", pdfTitle)
                        .put("sourceName", displayName)
                        .put("pageCount", pageCount)
                        .put("pagesWithText", pagesWithText)
                        .put("wordCount", wordCount)
                        .put("createdAt", System.currentTimeMillis())
                    File(target, "book.json").writeText(metadata.toString(), Charsets.UTF_8)
                    preferences.edit().putString("progress_$id", "1").apply()
                    respond(requestId, true, "imported", metadata)
                } catch (t: Throwable) {
                    target?.let { runCatching { it.deleteRecursively() } }
                    respond(requestId, false, "error", error = t.message ?: "PDF import failed.")
                } finally {
                    pickerBusy.set(false)
                }
            }
        }

        @JavascriptInterface
        fun listBooks(requestId: String) {
            if (!valid(requestId)) return
            io.execute {
                try {
                    val array = JSONArray()
                    booksRoot.listFiles()?.filter { it.isDirectory }?.forEach { dir ->
                        val metaFile = File(dir, "book.json")
                        if (metaFile.isFile) {
                            try {
                                val data = JSONObject(metaFile.readText(Charsets.UTF_8))
                                data.put("lastPage", preferences.getInt("progress_" + data.optString("id"), 1))
                                array.put(data)
                            } catch (_: Throwable) { }
                        }
                    }
                    val sorted = JSONArray((0 until array.length()).map { array.getJSONObject(it) }
                        .sortedByDescending { it.optLong("createdAt") })
                    respond(requestId, true, "books", JSONObject().put("books", sorted))
                } catch (t: Throwable) {
                    respond(requestId, false, "error", error = t.message ?: "Could not load your library.")
                }
            }
        }

        @JavascriptInterface
        fun renderPage(requestId: String, bookId: String, pageNumber: Int) {
            if (!valid(requestId) || !validBook.matches(bookId) || pageNumber !in 1..2000) {
                respond(requestId, false, "error", error = "Invalid PDF page request.")
                return
            }
            io.execute {
                try {
                    val dir = safeBookDir(bookId)
                    val meta = JSONObject(File(dir, "book.json").readText(Charsets.UTF_8))
                    val count = meta.optInt("pageCount", 0)
                    require(pageNumber in 1..count) { "That page is outside this PDF." }
                    val width = resources.displayMetrics.widthPixels.coerceIn(480, 1440)
                    val imageDir = File(cacheDir, "atlas-pdf-pages/$bookId").apply { mkdirs() }
                    val imageFile = File(imageDir, "$pageNumber-$width.jpg")
                    if (!imageFile.isFile) {
                        val pdf = File(dir, "source.pdf")
                        ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                            PdfRenderer(descriptor).use { renderer ->
                                renderer.openPage(pageNumber - 1).use { page ->
                                    val bitmapWidth = width
                                    val bitmapHeight = (bitmapWidth.toFloat() * page.height / page.width).toInt().coerceIn(1, 4000)
                                    val bitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
                                    bitmap.eraseColor(Color.WHITE)
                                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                    FileOutputStream(imageFile).use { output -> bitmap.compress(Bitmap.CompressFormat.JPEG, 78, output) }
                                    bitmap.recycle()
                                }
                            }
                        }
                    }
                    val bytes = imageFile.readBytes()
                    val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
                    respond(requestId, true, "page-image", JSONObject()
                        .put("bookId", bookId)
                        .put("page", pageNumber)
                        .put("dataUrl", "data:image/jpeg;base64,$encoded"))
                } catch (t: Throwable) {
                    respond(requestId, false, "error", error = t.message ?: "Could not render this PDF page.")
                }
            }
        }

        @JavascriptInterface
        fun saveProgress(requestId: String, bookId: String, pageNumber: Int) {
            if (!valid(requestId) || !validBook.matches(bookId) || pageNumber !in 1..2000) return
            io.execute {
                try {
                    val dir = safeBookDir(bookId)
                    val pages = JSONObject(File(dir, "book.json").readText(Charsets.UTF_8)).optInt("pageCount", 1)
                    preferences.edit().putInt("progress_$bookId", pageNumber.coerceIn(1, pages)).apply()
                    respond(requestId, true, "progress", JSONObject().put("bookId", bookId).put("page", pageNumber.coerceIn(1, pages)))
                } catch (t: Throwable) {
                    respond(requestId, false, "error", error = t.message ?: "Could not save reading position.")
                }
            }
        }

        @JavascriptInterface
        fun deleteBook(requestId: String, bookId: String) {
            if (!valid(requestId) || !validBook.matches(bookId)) return
            io.execute {
                try {
                    val dir = safeBookDir(bookId)
                    if (dir.exists() && !dir.deleteRecursively()) throw IllegalStateException("Could not remove this PDF.")
                    preferences.edit().remove("progress_$bookId").apply()
                    File(cacheDir, "atlas-pdf-pages/$bookId").deleteRecursively()
                    respond(requestId, true, "deleted", JSONObject().put("bookId", bookId))
                } catch (t: Throwable) {
                    respond(requestId, false, "error", error = t.message ?: "Could not delete this PDF.")
                }
            }
        }

        @JavascriptInterface
        fun getSettings(requestId: String) {
            if (!valid(requestId)) return
            val model = File(filesDir, "atlas-library/models/pocket_tts_en_int8.litertlm")
            val data = JSONObject()
                .put("voice", preferences.getString("voice", "alba"))
                .put("rate", preferences.getFloat("rate", 1f).toDouble())
                .put("followVoice", preferences.getBoolean("followVoice", true))
                .put("modelReady", model.isFile && model.length() in (130L * 1024L * 1024L)..(190L * 1024L * 1024L))
                .put("voices", JSONArray(PocketPlaybackService.VOICES.map { JSONObject().put("id", it).put("name", PocketPlaybackService.LABELS[it]) }))
            respond(requestId, true, "settings", data)
        }

        @JavascriptInterface
        fun saveSettings(requestId: String, json: String) {
            if (!valid(requestId)) return
            try {
                val input = JSONObject(json)
                val voice = input.optString("voice", "alba")
                val rate = input.optDouble("rate", 1.0).toFloat()
                require(voice in PocketPlaybackService.VOICES) { "Choose a voice from the available Pocket TTS voices." }
                require(rate in 0.65f..1.5f) { "Playback speed must be between 0.65× and 1.5×." }
                val saved = preferences.edit()
                    .putString("voice", voice)
                    .putFloat("rate", rate)
                    .putBoolean("followVoice", input.optBoolean("followVoice", true))
                    .commit()
                check(saved) { "Android could not save your reading settings." }
                getSettings(requestId)
            } catch (t: Throwable) {
                respond(requestId, false, "error", error = t.message ?: "Could not save settings.")
            }
        }

        @JavascriptInterface
        fun preparePocketTts(requestId: String) {
            if (!valid(requestId)) return
            val intent = Intent(this@MainActivity, PocketPlaybackService::class.java).setAction(PocketPlaybackService.PREPARE)
                .putExtra(PocketPlaybackService.REQUEST, requestId)
            startForegroundCompat(intent)
            respond(requestId, true, "accepted", JSONObject().put("preparing", true))
        }

        @JavascriptInterface
        fun playBook(requestId: String, bookId: String, startPage: Int, title: String, voice: String, rate: Float) {
            if (!valid(requestId) || !validBook.matches(bookId) || startPage !in 1..2000 ||
                voice !in PocketPlaybackService.VOICES || rate !in 0.65f..1.5f) {
                respond(requestId, false, "error", error = "Invalid audiobook request.")
                return
            }
            io.execute {
                try {
                    val dir = safeBookDir(bookId)
                    val meta = JSONObject(File(dir, "book.json").readText(Charsets.UTF_8))
                    require(meta.optInt("pagesWithText", 0) > 0) { "This PDF contains no selectable text. It may be a scanned document." }
                    val page = startPage.coerceIn(1, meta.optInt("pageCount", 1))
                    preferences.edit().putString("voice", voice).putFloat("rate", rate).putInt("progress_$bookId", page).apply()
                    val intent = Intent(this@MainActivity, PocketPlaybackService::class.java).setAction(PocketPlaybackService.PLAY_BOOK)
                        .putExtra(PocketPlaybackService.REQUEST, requestId)
                        .putExtra(PocketPlaybackService.BOOK, bookId)
                        .putExtra(PocketPlaybackService.PAGE, page)
                        .putExtra(PocketPlaybackService.TITLE, title.take(180))
                        .putExtra(PocketPlaybackService.VOICE, voice)
                        .putExtra(PocketPlaybackService.RATE, rate)
                    startForegroundCompat(intent)
                    respond(requestId, true, "accepted", JSONObject().put("bookId", bookId).put("page", page))
                } catch (t: Throwable) {
                    respond(requestId, false, "error", error = t.message ?: "Could not start the audiobook.")
                }
            }
        }

        @JavascriptInterface
        fun previewVoice(requestId: String, voice: String, rate: Float) {
            if (!valid(requestId) || voice !in PocketPlaybackService.VOICES || rate !in 0.65f..1.5f) return
            val intent = Intent(this@MainActivity, PocketPlaybackService::class.java).setAction(PocketPlaybackService.PREVIEW)
                .putExtra(PocketPlaybackService.REQUEST, requestId)
                .putExtra(PocketPlaybackService.VOICE, voice)
                .putExtra(PocketPlaybackService.RATE, rate)
            startForegroundCompat(intent)
            respond(requestId, true, "accepted", JSONObject().put("preview", true))
        }

        @JavascriptInterface fun pausePlayback(requestId: String) = sendPlaybackAction(requestId, PocketPlaybackService.PAUSE)
        @JavascriptInterface fun resumePlayback(requestId: String) = sendPlaybackAction(requestId, PocketPlaybackService.RESUME)
        @JavascriptInterface fun stopPlayback(requestId: String) = sendPlaybackAction(requestId, PocketPlaybackService.STOP)

        private fun sendPlaybackAction(requestId: String, action: String) {
            if (!valid(requestId)) return
            startService(Intent(this@MainActivity, PocketPlaybackService::class.java).setAction(action))
            respond(requestId, true, "accepted", JSONObject().put("action", action))
        }

        private fun startForegroundCompat(intent: Intent) {
            ContextCompat.startForegroundService(this@MainActivity, intent)
        }

        private fun safeBookDir(bookId: String): File {
            require(validBook.matches(bookId)) { "Invalid PDF identifier." }
            val root = booksRoot.canonicalFile
            val dir = File(root, bookId).canonicalFile
            require(dir.parentFile == root && File(dir, "book.json").isFile) { "PDF not found in this library." }
            return dir
        }

        private fun displayName(uri: Uri): String {
            var name = "Imported PDF"
            val cursor: Cursor? = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            cursor?.use { if (it.moveToFirst()) name = it.getString(0) ?: name }
            return name.take(180)
        }

        private fun valid(requestId: String) = validRequest.matches(requestId)
        fun respond(requestId: String, ok: Boolean, type: String, data: JSONObject? = null, error: String? = null) {
            val payload = JSONObject().put("requestId", requestId).put("ok", ok).put("type", type)
            if (data != null) payload.put("data", data)
            if (error != null) payload.put("error", error.take(500))
            deliverToWeb("window.atlasNativeResult", payload.toString())
        }

        fun respond(requestId: String, ok: Boolean, type: String, data: JSONObject) = respond(requestId, ok, type, data, null)

        fun close() {
            io.shutdownNow()
        }
    }
}