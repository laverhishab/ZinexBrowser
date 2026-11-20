package com.zinex.browser

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity

private data class BrowserTab(
    val webView: WebView,
    var privateMode: Boolean = false,
    var title: String = "New tab"
)

class MainActivity : ComponentActivity() {
    private lateinit var root: LinearLayout
    private lateinit var topBar: LinearLayout
    private lateinit var addressBar: EditText
    private lateinit var webContainer: LinearLayout
    private lateinit var bottomBar: LinearLayout
    private lateinit var tabCount: TextView
    private val tabs = mutableListOf<BrowserTab>()
    private var activeIndex = -1
    private var darkMode = true
    private var desktopMode = false
    private var fileCallback: ValueCallback<Array<Uri>>? = null

    private val filePicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        val uris = if (result.resultCode == RESULT_OK) {
            when {
                data?.clipData != null -> Array(data.clipData!!.itemCount) { i -> data.clipData!!.getItemAt(i).uri }
                data?.data != null -> arrayOf(data.data!!)
                else -> emptyArray()
            }
        } else emptyArray()
        fileCallback?.onReceiveValue(uris)
        fileCallback = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildInterface()
        addTab(loadHome = true)
    }

    private fun buildInterface() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, dp(4))
        }
        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(7), dp(10), dp(7))
        }
        val brand = TextView(this).apply {
            text = "Z"
            textSize = 21f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(108, 99, 255))
            setPadding(dp(10), dp(5), dp(10), dp(5))
        }
        addressBar = EditText(this).apply {
            singleLine = true
            textSize = 14f
            hint = "Search or enter website"
            imeOptions = EditorInfo.IME_ACTION_GO
            setPadding(dp(13), 0, dp(10), 0)
            setBackgroundColor(if (darkMode) Color.rgb(38, 42, 59) else Color.rgb(239, 241, 247))
            setTextColor(if (darkMode) Color.WHITE else Color.BLACK)
            setHintTextColor(if (darkMode) 0xFFADB3C7.toInt() else 0xFF777777.toInt())
        }
        addressBar.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_GO || action == EditorInfo.IME_ACTION_DONE) {
                navigate(addressBar.text.toString()); true
            } else false
        }
        val menu = makeButton("⋮") { showMenu(it) }
        topBar.addView(brand, LinearLayout.LayoutParams(dp(38), dp(38)))
        topBar.addView(addressBar, LinearLayout.LayoutParams(0, dp(42), 1f).apply { setMargins(dp(9), 0, dp(7), 0) })
        topBar.addView(menu, LinearLayout.LayoutParams(dp(42), dp(42)))

        webContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(5), dp(5), dp(5), dp(5))
        }
        bottomBar.addView(makeButton("‹") { goBack() }, weightParams())
        bottomBar.addView(makeButton("›") { goForward() }, weightParams())
        bottomBar.addView(makeButton("⌂") { showHome() }, weightParams())
        bottomBar.addView(makeButton("＋") { addTab(loadHome = true) }, weightParams())
        tabCount = TextView(this).apply {
            text = "1"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(49, 54, 74))
            setOnClickListener { showTabs() }
        }
        bottomBar.addView(tabCount, LinearLayout.LayoutParams(dp(42), dp(38)).apply { setMargins(dp(3), 0, dp(3), 0) })
        bottomBar.addView(makeButton("⟳") { currentWebView()?.reload() }, weightParams())

        root.addView(topBar)
        root.addView(webContainer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(bottomBar)
        setContentView(root)
        applyTheme()
    }

    private fun weightParams() = LinearLayout.LayoutParams(0, dp(40), 1f)

    private fun makeButton(label: String, action: (View) -> Unit): TextView = TextView(this).apply {
        text = label
        textSize = 22f
        gravity = Gravity.CENTER
        setTextColor(if (darkMode) Color.WHITE else Color.rgb(30, 34, 48))
        setOnClickListener(action)
        isClickable = true
        isFocusable = true
    }

    private fun createWebView(privateMode: Boolean): WebView {
        return WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundColor(Color.WHITE)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadsImagesAutomatically = true
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.setSupportMultipleWindows(false)
            settings.allowFileAccess = false
            settings.allowContentAccess = true
            settings.cacheMode = if (privateMode) WebSettings.LOAD_NO_CACHE else WebSettings.LOAD_DEFAULT
            settings.saveFormData = !privateMode
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.userAgentString = if (desktopMode) {
                settings.userAgentString.replace("; wv", "").replace(Regex("\\s*Mobile\\s*"), " ")
            } else settings.userAgentString
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val uri = request?.url ?: return false
                    val scheme = uri.scheme?.lowercase()
                    if (scheme == "http" || scheme == "https") return false
                    return try {
                        startActivity(Intent(Intent.ACTION_VIEW, uri)); true
                    } catch (_: Exception) { true }
                }
                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    if (activeWebView() === view && url != null) addressBar.setText(url)
                }
                override fun onPageFinished(view: WebView?, url: String?) {
                    if (activeWebView() === view) {
                        addressBar.setText(url ?: "")
                        tabs.getOrNull(activeIndex)?.title = view?.title ?: "Website"
                    }
                    if (!privateMode && url != null && url.startsWith("http")) saveHistory(url, view?.title ?: url)
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onReceivedTitle(view: WebView?, title: String?) {
                    if (activeWebView() === view && title != null) tabs.getOrNull(activeIndex)?.title = title
                }
                override fun onShowFileChooser(
                    webView: WebView?,
                    filePathCallback: ValueCallback<Array<Uri>>?,
                    fileChooserParams: FileChooserParams?
                ): Boolean {
                    fileCallback?.onReceiveValue(null)
                    fileCallback = filePathCallback
                    return try {
                        filePicker.launch(fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply { type = "*/*"; addCategory(Intent.CATEGORY_OPENABLE) })
                        true
                    } catch (_: Exception) {
                        fileCallback = null
                        false
                    }
                }
            }
            setDownloadListener(DownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
                try {
                    val request = DownloadManager.Request(Uri.parse(url))
                        .setMimeType(mimeType)
                        .addRequestHeader("User-Agent", userAgent)
                        .setTitle(URLUtil.guessFileName(url, contentDisposition, mimeType))
                        .setDescription("Downloading with Zinex Browser")
                        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                        .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, URLUtil.guessFileName(url, contentDisposition, mimeType))
                    (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
                    Toast.makeText(this@MainActivity, "Download started", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            })
        }
    }

    private fun addTab(loadHome: Boolean, url: String? = null, privateMode: Boolean = false) {
        val web = createWebView(privateMode)
        tabs.add(BrowserTab(web, privateMode))
        activeIndex = tabs.lastIndex
        webContainer.removeAllViews()
        webContainer.addView(web)
        tabCount.text = tabs.size.toString()
        if (url != null) web.loadUrl(url) else if (loadHome) loadHomepage(web)
    }

    private fun loadHomepage(web: WebView) {
        val bg = if (darkMode) "#101321" else "#F5F7FC"
        val fg = if (darkMode) "#FFFFFF" else "#202438"
        val card = if (darkMode) "#20263A" else "#FFFFFF"
        val html = """
            <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
            <style>
            *{box-sizing:border-box}body{margin:0;background:$bg;color:$fg;font-family:Arial,sans-serif;display:flex;min-height:88vh;align-items:center;justify-content:center;text-align:center}
            .wrap{width:100%;max-width:480px;padding:24px}.logo{font-size:54px;font-weight:800;letter-spacing:-4px;color:#8B83FF;margin-bottom:8px}.sub{opacity:.65;font-size:13px;margin-bottom:30px}
            .search{display:flex;background:$card;border-radius:18px;padding:7px;box-shadow:0 8px 28px #0002}.search input{min-width:0;flex:1;border:0;outline:0;background:transparent;color:$fg;padding:12px;font-size:15px}.search button{border:0;border-radius:13px;background:#6C63FF;color:white;padding:0 17px;font-weight:bold}
            .grid{display:grid;grid-template-columns:repeat(4,1fr);gap:12px;margin-top:30px}.item{padding:15px 4px;border-radius:16px;background:$card;color:$fg;text-decoration:none;font-size:12px}.ico{font-size:24px;display:block;margin-bottom:8px}
            </style></head><body><div class="wrap"><div class="logo">ZINEX</div><div class="sub">A smarter way to browse</div>
            <form class="search" action="https://www.google.com/search" method="get"><input name="q" placeholder="Search Google or enter URL"><button>Go</button></form>
            <div class="grid">
            <a class="item" href="https://www.google.com"><span class="ico">🔎</span>Google</a>
            <a class="item" href="https://www.facebook.com"><span class="ico">ⓕ</span>Facebook</a>
            <a class="item" href="https://www.youtube.com"><span class="ico">▶</span>YouTube</a>
            <a class="item" href="https://web.whatsapp.com"><span class="ico">☏</span>WhatsApp</a>
            <a class="item" href="https://mail.google.com"><span class="ico">✉</span>Gmail</a>
            <a class="item" href="https://www.wikipedia.org"><span class="ico">W</span>Wikipedia</a>
            <a class="item" href="https://www.instagram.com"><span class="ico">◎</span>Instagram</a>
            <a class="item" href="https://www.bing.com"><span class="ico">⌕</span>Bing</a>
            </div></div></body></html>
        """.trimIndent()
        web.loadDataWithBaseURL("https://zinex.local/", html, "text/html", "UTF-8", null)
    }

    private fun navigate(input: String) {
        val text = input.trim()
        if (text.isEmpty()) return
        val url = when {
            text.startsWith("https://") || text.startsWith("http://") -> text
            text.contains(".") && !text.contains(" ") -> "https://$text"
            else -> "https://www.google.com/search?q=${Uri.encode(text)}"
        }
        currentWebView()?.loadUrl(url)
        addressBar.clearFocus()
    }

    private fun currentWebView() = tabs.getOrNull(activeIndex)?.webView
    private fun activeWebView() = currentWebView()

    private fun goBack() {
        val web = currentWebView()
        if (web?.canGoBack() == true) web.goBack() else showHome()
    }
    private fun goForward() { currentWebView()?.let { if (it.canGoForward()) it.goForward() } }
    private fun showHome() { currentWebView()?.let { loadHomepage(it); addressBar.setText("") } }

    private fun showTabs() {
        val labels = tabs.mapIndexed { i, tab -> "${if (i == activeIndex) "✓ " else ""}${if (tab.privateMode) "Private · " else ""}${tab.title.take(32)}" }.toMutableList()
        labels.add("＋  New tab")
        android.app.AlertDialog.Builder(this)
            .setTitle("Tabs (${tabs.size})")
            .setItems(labels.toTypedArray()) { _, which ->
                if (which == tabs.size) addTab(loadHome = true) else switchTab(which)
            }
            .setNegativeButton("Close current tab") { _, _ -> closeCurrentTab() }
            .setNeutralButton("Private tab") { _, _ -> addTab(loadHome = true, privateMode = true) }
            .show()
    }

    private fun switchTab(index: Int) {
        if (index !in tabs.indices) return
        activeIndex = index
        webContainer.removeAllViews()
        webContainer.addView(tabs[index].webView)
        addressBar.setText(tabs[index].webView.url?.takeUnless { it.startsWith("https://zinex.local") } ?: "")
        tabCount.text = tabs.size.toString()
    }

    private fun closeCurrentTab() {
        if (tabs.isEmpty()) return
        val removed = tabs.removeAt(activeIndex)
        removed.webView.stopLoading()
        removed.webView.destroy()
        if (tabs.isEmpty()) addTab(loadHome = true) else switchTab((activeIndex - 1).coerceAtLeast(0))
        tabCount.text = tabs.size.toString()
    }

    private fun showMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        listOf("New tab", "Private tab", "Bookmarks", "History", "Downloads", "Desktop site", "Dark / Light theme", "Settings", "Close tab").forEach { popup.menu.add(it) }
        popup.setOnMenuItemClickListener { item ->
            when (item.title.toString()) {
                "New tab" -> addTab(loadHome = true)
                "Private tab" -> addTab(loadHome = true, privateMode = true)
                "Bookmarks" -> showSavedList("Bookmarks", "bookmarks")
                "History" -> showSavedList("History", "history")
                "Downloads" -> try { startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)) } catch (_: Exception) { toast("Downloads are managed by Android") }
                "Desktop site" -> { desktopMode = !desktopMode; currentWebView()?.settings?.userAgentString = if (desktopMode) "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/120 Safari/537.36" else WebSettings.getDefaultUserAgent(this); currentWebView()?.reload() }
                "Dark / Light theme" -> { darkMode = !darkMode; applyTheme(); showHome() }
                "Settings" -> showSettings()
                "Close tab" -> closeCurrentTab()
            }
            true
        }
        popup.show()
    }

    private fun showSavedList(title: String, key: String) {
        val prefs = getSharedPreferences("zinex", MODE_PRIVATE)
        val entries = prefs.getStringSet(key, emptySet())?.toList()?.sortedDescending().orEmpty()
        if (entries.isEmpty()) { toast("No $title yet"); return }
        android.app.AlertDialog.Builder(this).setTitle(title).setItems(entries.toTypedArray()) { _, which ->
            val value = entries[which]
            val url = if (key == "history") value.substringAfter("||", value) else value
            addTab(loadHome = false, url = url)
        }.setNegativeButton("Close", null).show()
    }

    private fun saveHistory(url: String, title: String) {
        val prefs = getSharedPreferences("zinex", MODE_PRIVATE)
        val set = prefs.getStringSet("history", emptySet())?.toMutableSet() ?: mutableSetOf()
        set.add("${title.take(50)}||$url")
        prefs.edit().putStringSet("history", set).apply()
    }

    private fun showSettings() {
        val options = arrayOf("Clear browsing history", "Clear cache", "Set Google as search engine", "About Zinex Browser")
        android.app.AlertDialog.Builder(this).setTitle("Settings").setItems(options) { _, which ->
            when (which) {
                0 -> { getSharedPreferences("zinex", MODE_PRIVATE).edit().remove("history").apply(); toast("History cleared") }
                1 -> { tabs.forEach { it.webView.clearCache(true) }; toast("Cache cleared") }
                2 -> toast("Google is the default search engine")
                3 -> android.app.AlertDialog.Builder(this).setMessage("Zinex Browser 1.0.0\nPowered by Android System WebView.").setPositiveButton("OK", null).show()
            }
        }.setNegativeButton("Close", null).show()
    }

    private fun applyTheme() {
        val background = if (darkMode) Color.rgb(16, 19, 33) else Color.rgb(245, 247, 252)
        root.setBackgroundColor(background)
        topBar.setBackgroundColor(background)
        bottomBar.setBackgroundColor(background)
        addressBar.setBackgroundColor(if (darkMode) Color.rgb(38, 42, 59) else Color.rgb(239, 241, 247))
        addressBar.setTextColor(if (darkMode) Color.WHITE else Color.BLACK)
        for (i in 0 until bottomBar.childCount) (bottomBar.getChildAt(i) as? TextView)?.setTextColor(if (darkMode) Color.WHITE else Color.rgb(30, 34, 48))
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onBackPressed() {
        val web = currentWebView()
        if (web?.canGoBack() == true) web.goBack() else if (tabs.size > 1) closeCurrentTab() else super.onBackPressed()
    }

    override fun onDestroy() {
        tabs.forEach { it.webView.stopLoading(); it.webView.destroy() }
        tabs.clear()
        super.onDestroy()
    }
}
