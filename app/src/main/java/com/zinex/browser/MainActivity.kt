package com.zinex.browser

import android.Manifest
import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import android.util.Base64
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import java.io.File

private data class BrowserTab(
    val webView: WebView,
    var privateMode: Boolean = false,
    var title: String = "New tab",
    var errorUrl: String? = null
)

private class SheetRow(
    val title: String,
    val subtitle: String,
    val onClick: () -> Unit,
    val onDelete: (() -> Unit)? = null
)

private class SheetButton(val label: String, val action: () -> Unit)

private class MenuAction(val label: String, val icon: Int, val active: Boolean, val action: () -> Unit)

private class WallpaperDrawable(private val bmp: Bitmap, private val scrim: Int) : Drawable() {
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val scrimPaint = Paint().apply { color = scrim }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val scale = maxOf(b.width().toFloat() / bmp.width, b.height().toFloat() / bmp.height)
        val left = b.left + (b.width() - bmp.width * scale) / 2f
        val top = b.top + (b.height() - bmp.height * scale) / 2f
        val save = canvas.save()
        canvas.clipRect(b)
        canvas.translate(left, top)
        canvas.scale(scale, scale)
        canvas.drawBitmap(bmp, 0f, 0f, paint)
        canvas.restoreToCount(save)
        canvas.drawRect(b, scrimPaint)
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.OPAQUE
}

private class MaxHeightScrollView(context: Context, private val maxPx: Int) : ScrollView(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(maxPx, View.MeasureSpec.AT_MOST))
    }
}

class MainActivity : ComponentActivity() {
    private lateinit var root: LinearLayout
    private lateinit var addressBar: EditText
    private lateinit var securityIcon: ImageView
    private lateinit var reloadButton: ImageView
    private lateinit var progressBar: ProgressBar
    private lateinit var webContainer: LinearLayout
    private lateinit var bottomBar: LinearLayout
    private lateinit var tabCount: TextView
    private lateinit var backButton: ImageView
    private lateinit var forwardButton: ImageView

    private val tabs = mutableListOf<BrowserTab>()
    private var activeIndex = -1
    private var darkMode = true
    private var desktopMode = false
    private var loading = false
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null
    private var pendingDownload: (() -> Unit)? = null
    private var wallpaper: Bitmap? = null
    private val hasWallpaper: Boolean get() = wallpaper != null

    private val accent = Color.parseColor("#6C63FF")
    private val internalBase = "https://zinex.local"
    private val desktopAgent = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    private val filePicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        val uris: Array<Uri> = if (result.resultCode == RESULT_OK) {
            when {
                data?.clipData != null -> Array(data.clipData!!.itemCount) { i -> data.clipData!!.getItemAt(i).uri }
                data?.data != null -> arrayOf(data.data!!)
                else -> emptyArray<Uri>()
            }
        } else emptyArray<Uri>()
        fileCallback?.onReceiveValue(uris)
        fileCallback = null
    }

    private val wallpaperPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) importWallpaper(uri)
    }

    private val storagePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val job = pendingDownload
        pendingDownload = null
        if (granted && job != null) {
            job()
        } else {
            toast("Storage permission is needed to download files")
        }
    }

    // ---------------------------------------------------------------- colors

    private fun cBg(): Int = Color.parseColor(if (darkMode) "#0F1220" else "#F3F5FB")
    private fun cSurface(): Int = Color.parseColor(
        if (hasWallpaper) (if (darkMode) "#661A1F36" else "#8CFFFFFF") else (if (darkMode) "#1A1F36" else "#FFFFFF")
    )
    private fun cSurface2(): Int = Color.parseColor(
        if (hasWallpaper) (if (darkMode) "#40FFFFFF" else "#80FFFFFF") else (if (darkMode) "#262C4A" else "#E7EAF5")
    )
    private fun cSheet(): Int = Color.parseColor(
        if (hasWallpaper) (if (darkMode) "#CC1A1F36" else "#D9FFFFFF") else (if (darkMode) "#1A1F36" else "#FFFFFF")
    )
    private fun cGlassStroke(): Int = Color.parseColor(if (darkMode) "#33FFFFFF" else "#99FFFFFF")
    private fun cText(): Int = Color.parseColor(if (darkMode) "#FFFFFF" else "#1B2033")
    private fun cSub(): Int = Color.parseColor(if (darkMode) "#9AA3C2" else "#6B7391")

    // ------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        darkMode = getSharedPreferences("zinex", MODE_PRIVATE).getBoolean("dark", true)
        loadWallpaper()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 29) {
            window.setNavigationBarContrastEnforced(false)
        }
        buildInterface()
        val incoming = intent?.data
        if (incoming != null && (incoming.scheme == "http" || incoming.scheme == "https")) {
            addTab(loadHome = false, url = incoming.toString())
        } else {
            addTab(loadHome = true)
        }
    }

    override fun onBackPressed() {
        if (customView != null) {
            exitFullscreen()
            return
        }
        if (addressBar.hasFocus()) {
            currentWebView()?.requestFocus()
            hideKeyboard()
            return
        }
        val web = currentWebView()
        if (web != null && web.canGoBack()) {
            web.goBack()
        } else if (tabs.size > 1) {
            closeTab(activeIndex)
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        tabs.forEach { it.webView.stopLoading(); it.webView.destroy() }
        tabs.clear()
        super.onDestroy()
    }

    // -------------------------------------------------------------------- UI

    private fun buildInterface() {
        root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        val wall = wallpaper
        if (wall != null) {
            root.background = WallpaperDrawable(wall, Color.parseColor(if (darkMode) "#40000000" else "#14000000"))
        } else {
            root.setBackgroundColor(cBg())
        }

        securityIcon = ImageView(this)
        securityIcon.setImageResource(R.drawable.ic_search)
        securityIcon.setColorFilter(cSub())

        addressBar = EditText(this)
        addressBar.setSingleLine(true)
        addressBar.textSize = 15f
        addressBar.hint = "Search or type website"
        addressBar.imeOptions = EditorInfo.IME_ACTION_GO
        addressBar.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        addressBar.setBackgroundColor(Color.TRANSPARENT)
        addressBar.setTextColor(cText())
        addressBar.setHintTextColor(cSub())
        addressBar.setPadding(dp(10), 0, dp(6), 0)
        addressBar.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_GO || action == EditorInfo.IME_ACTION_DONE) {
                navigate(addressBar.text.toString())
                true
            } else false
        }
        addressBar.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                bottomBar.visibility = View.GONE
                addressBar.setText(fullUrl())
                addressBar.post { addressBar.selectAll() }
            } else {
                bottomBar.visibility = View.VISIBLE
                showDisplayUrl()
            }
        }

        reloadButton = ImageView(this)
        reloadButton.setImageResource(R.drawable.ic_refresh)
        reloadButton.setColorFilter(cSub())
        reloadButton.scaleType = ImageView.ScaleType.CENTER
        reloadButton.contentDescription = "Reload"
        reloadButton.setBackgroundResource(rippleRes())
        reloadButton.setOnClickListener { reloadOrStop() }

        val pill = LinearLayout(this)
        pill.orientation = LinearLayout.HORIZONTAL
        pill.gravity = Gravity.CENTER_VERTICAL
        pill.background = rounded(cSurface(), 24)
        pill.addView(securityIcon, LinearLayout.LayoutParams(dp(18), dp(18)).apply { setMargins(dp(14), 0, 0, 0) })
        pill.addView(addressBar, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        pill.addView(reloadButton, LinearLayout.LayoutParams(dp(44), dp(44)))

        val topBar = LinearLayout(this)
        topBar.orientation = LinearLayout.HORIZONTAL
        topBar.setPadding(dp(12), dp(8), dp(12), dp(6))
        topBar.addView(pill, LinearLayout.LayoutParams(0, dp(48), 1f))

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        progressBar.max = 100
        progressBar.progressTintList = ColorStateList.valueOf(accent)
        progressBar.progressBackgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
        progressBar.visibility = View.INVISIBLE

        webContainer = LinearLayout(this)
        webContainer.orientation = LinearLayout.VERTICAL
        webContainer.setBackgroundColor(if (hasWallpaper) Color.TRANSPARENT else cBg())

        bottomBar = LinearLayout(this)
        bottomBar.orientation = LinearLayout.HORIZONTAL
        bottomBar.gravity = Gravity.CENTER
        bottomBar.background = roundedTop(cSurface(), 20)
        bottomBar.setPadding(dp(6), dp(4), dp(6), dp(4))

        backButton = navButton(R.drawable.ic_back, "Back") { goBack() }
        forwardButton = navButton(R.drawable.ic_forward, "Forward") { goForward() }
        val homeButton = navButton(R.drawable.ic_home, "Home") { showHome() }
        val menuButton = navButton(R.drawable.ic_menu, "Menu") { showMenuSheet() }

        tabCount = TextView(this)
        tabCount.text = tabs.size.toString()
        tabCount.textSize = 12f
        tabCount.gravity = Gravity.CENTER
        tabCount.setTypeface(null, Typeface.BOLD)
        tabCount.setTextColor(cText())
        val box = GradientDrawable()
        box.setColor(Color.TRANSPARENT)
        box.setCornerRadius(dp(7).toFloat())
        box.setStroke(dp(2), cText())
        tabCount.background = box

        val tabCell = FrameLayout(this)
        tabCell.setBackgroundResource(rippleRes())
        tabCell.setOnClickListener { showTabs() }
        tabCell.addView(tabCount, FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER))

        bottomBar.addView(backButton, navParams())
        bottomBar.addView(forwardButton, navParams())
        bottomBar.addView(homeButton, navParams())
        bottomBar.addView(tabCell, navParams())
        bottomBar.addView(menuButton, navParams())

        root.addView(topBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(progressBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)))
        root.addView(webContainer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(bottomBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val imeOn = ime.bottom > 0
            v.setPadding(sys.left, sys.top, sys.right, if (imeOn) ime.bottom else 0)
            bottomBar.setPadding(dp(6), dp(4), dp(6), if (imeOn) dp(4) else sys.bottom + dp(4))
            insets
        }

        setContentView(root)
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.setAppearanceLightStatusBars(!darkMode)
        controller.setAppearanceLightNavigationBars(!darkMode)
        ViewCompat.requestApplyInsets(root)
    }

    private fun rebuildUi() {
        webContainer.removeAllViews()
        buildInterface()
        val web = currentWebView()
        if (web != null) {
            webContainer.addView(web)
        }
        updateTabCount()
        refreshChrome()
    }

    private fun navParams() = LinearLayout.LayoutParams(0, dp(52), 1f)

    private fun navButton(icon: Int, desc: String, action: () -> Unit): ImageView {
        val v = ImageView(this)
        v.setImageResource(icon)
        v.setColorFilter(cText())
        v.scaleType = ImageView.ScaleType.CENTER
        v.contentDescription = desc
        v.setBackgroundResource(rippleRes())
        v.setOnClickListener { action() }
        return v
    }

    private fun rippleRes(): Int {
        val tv = TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true)
        return tv.resourceId
    }

    private fun rounded(color: Int, radiusDp: Int): GradientDrawable {
        val d = GradientDrawable()
        d.setColor(color)
        d.setCornerRadius(dp(radiusDp).toFloat())
        if (hasWallpaper) d.setStroke(dp(1), cGlassStroke())
        return d
    }

    private fun roundedTop(color: Int, radiusDp: Int): GradientDrawable {
        val d = GradientDrawable()
        d.setColor(color)
        val r = dp(radiusDp).toFloat()
        d.setCornerRadii(floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f))
        if (hasWallpaper) d.setStroke(dp(1), cGlassStroke())
        return d
    }

    private fun updateTabCount() {
        tabCount.text = tabs.size.toString()
    }

    private fun refreshChrome() {
        if (!addressBar.hasFocus()) {
            showDisplayUrl()
        }
        val web = currentWebView()
        backButton.alpha = if (web != null && web.canGoBack()) 1f else 0.35f
        forwardButton.alpha = if (web != null && web.canGoForward()) 1f else 0.35f
        reloadButton.setImageResource(if (loading) R.drawable.ic_close else R.drawable.ic_refresh)
    }

    private fun showDisplayUrl() {
        val full = fullUrl()
        if (full.isEmpty()) {
            addressBar.setText("")
            securityIcon.setImageResource(R.drawable.ic_search)
            securityIcon.setColorFilter(cSub())
        } else {
            addressBar.setText(hostOf(full))
            if (full.startsWith("https://")) {
                securityIcon.setImageResource(R.drawable.ic_lock)
                securityIcon.setColorFilter(Color.parseColor("#34C759"))
            } else {
                securityIcon.setImageResource(R.drawable.ic_info)
                securityIcon.setColorFilter(Color.parseColor("#FF9F0A"))
            }
        }
    }

    private fun fullUrl(): String {
        val tab = tabs.getOrNull(activeIndex) ?: return ""
        val err = tab.errorUrl
        if (err != null) return err
        val url = tab.webView.url
        if (url == null || url.startsWith(internalBase)) return ""
        return url
    }

    private fun hostOf(url: String): String {
        val host = Uri.parse(url).host
        return if (host == null || host.isEmpty()) url else host.removePrefix("www.")
    }

    private fun isInternal(url: String?): Boolean = url == null || url.startsWith(internalBase)

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(addressBar.windowToken, 0)
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    // ------------------------------------------------------------------ web

    private fun mobileAgent(): String = WebSettings.getDefaultUserAgent(this).replace("; wv", "")

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
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            settings.setSupportZoom(true)
            settings.setBuiltInZoomControls(true)
            settings.setDisplayZoomControls(false)
            settings.setUseWideViewPort(true)
            settings.setLoadWithOverviewMode(true)
            settings.userAgentString = if (desktopMode) desktopAgent else mobileAgent()
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, !privateMode)

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val uri = request?.url ?: return false
                    val scheme = uri.scheme?.lowercase()
                    if (scheme == "zinex") {
                        if (uri.host == "add") showAddShortcutDialog()
                        return true
                    }
                    if (scheme == "http" || scheme == "https") return false
                    return try {
                        startActivity(Intent(Intent.ACTION_VIEW, uri)); true
                    } catch (_: Exception) { true }
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    if (url != null && url.startsWith("http") && !url.startsWith(internalBase)) {
                        view?.setBackgroundColor(Color.WHITE)
                    }
                    val tab = tabs.firstOrNull { it.webView === view }
                    if (tab != null && url != null && !url.startsWith(internalBase)) {
                        tab.errorUrl = null
                    }
                    if (activeWebView() === view) {
                        loading = true
                        refreshChrome()
                    }
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    updateTabTitle(view, view?.title)
                    if (activeWebView() === view) {
                        loading = false
                        progressBar.visibility = View.INVISIBLE
                        refreshChrome()
                    }
                    if (!privateMode && url != null && url.startsWith("http") && !url.startsWith(internalBase)) {
                        saveEntry("history", view?.title ?: url, url, 200)
                    }
                }

                override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                    if (activeWebView() === view) {
                        refreshChrome()
                    }
                }

                override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                    if (request == null || !request.isForMainFrame) return
                    val code = error?.errorCode ?: return
                    val offline = code == WebViewClient.ERROR_HOST_LOOKUP ||
                        code == WebViewClient.ERROR_CONNECT ||
                        code == WebViewClient.ERROR_TIMEOUT ||
                        code == WebViewClient.ERROR_IO
                    if (!offline) return
                    val failing = request.url.toString()
                    tabs.firstOrNull { it.webView === view }?.errorUrl = failing
                    showErrorPage(view, failing)
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    if (activeWebView() === view) {
                        progressBar.progress = newProgress
                        progressBar.visibility = if (newProgress in 1..99) View.VISIBLE else View.INVISIBLE
                    }
                }

                override fun onReceivedTitle(view: WebView?, title: String?) {
                    updateTabTitle(view, title)
                }

                override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                    if (view == null) {
                        callback?.onCustomViewHidden()
                        return
                    }
                    enterFullscreen(view, callback)
                }

                override fun onHideCustomView() {
                    exitFullscreen()
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

            setOnLongClickListener {
                val hit = hitTestResult
                val extra = hit.extra
                if ((hit.type == WebView.HitTestResult.IMAGE_TYPE ||
                        hit.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) && !extra.isNullOrBlank()
                ) {
                    showImageSheet(extra, privateMode)
                    true
                } else if (hit.type == WebView.HitTestResult.SRC_ANCHOR_TYPE && !extra.isNullOrBlank() &&
                    isInternal(url) && findShortcut(extra) != null
                ) {
                    showShortcutSheet(extra, privateMode)
                    true
                } else {
                    false
                }
            }

            setDownloadListener(DownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
                requestDownload(url, userAgent, contentDisposition, mimeType)
            })
        }
    }

    private fun updateTabTitle(view: WebView?, title: String?) {
        val tab = tabs.firstOrNull { it.webView === view } ?: return
        val url = view?.url
        if (isInternal(url)) {
            tab.title = if (tab.errorUrl != null) "Can't reach page" else if (tab.privateMode) "Private tab" else "New tab"
        } else if (title != null && title.isNotBlank()) {
            tab.title = title
        }
    }

    private fun enterFullscreen(view: View, callback: WebChromeClient.CustomViewCallback?) {
        if (customView != null) {
            callback?.onCustomViewHidden()
            return
        }
        customView = view
        customCallback = callback
        val decor = window.decorView as ViewGroup
        decor.addView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.visibility = View.GONE
        WindowCompat.getInsetsController(window, window.decorView).hide(WindowInsetsCompat.Type.systemBars())
    }

    private fun exitFullscreen() {
        val view = customView ?: return
        val decor = window.decorView as ViewGroup
        decor.removeView(view)
        customView = null
        root.visibility = View.VISIBLE
        WindowCompat.getInsetsController(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
        customCallback?.onCustomViewHidden()
        customCallback = null
    }

    private fun requestDownload(url: String, userAgent: String?, contentDisposition: String?, mimeType: String?) {
        if (Build.VERSION.SDK_INT <= 28 &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingDownload = { startDownload(url, userAgent, contentDisposition, mimeType) }
            storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            startDownload(url, userAgent, contentDisposition, mimeType)
        }
    }

    private fun showImageSheet(src: String, privateMode: Boolean) {
        val rows = mutableListOf<SheetRow>()
        rows.add(SheetRow("Download image", "Save to your Downloads folder", { downloadImage(src) }))
        if (src.startsWith("http")) {
            rows.add(SheetRow("Open image in new tab", "View the full image", { addTab(false, src, privateMode) }))
        }
        showSheet("Image", rows, emptyList(), null)
    }

    private fun downloadImage(src: String) {
        when {
            src.startsWith("data:") -> saveDataImage(src)
            src.startsWith("http") -> {
                val ext = MimeTypeMap.getFileExtensionFromUrl(src)
                val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext?.lowercase() ?: "") ?: "image/jpeg"
                requestDownload(src, currentWebView()?.settings?.userAgentString, null, mime)
            }
            else -> toast("This image can't be downloaded")
        }
    }

    private fun saveDataImage(dataUri: String) {
        if (Build.VERSION.SDK_INT <= 28 &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingDownload = { saveDataImage(dataUri) }
            storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        try {
            val comma = dataUri.indexOf(',')
            if (comma < 0) throw IllegalArgumentException("bad image data")
            val header = dataUri.substring(5, comma)
            val body = dataUri.substring(comma + 1)
            val mime = header.substringBefore(';').ifEmpty { "image/jpeg" }
            val bytes = if (header.contains(";base64")) Base64.decode(body, Base64.DEFAULT) else Uri.decode(body).toByteArray()
            val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "jpg"
            val name = "zinex_" + System.currentTimeMillis() + "." + ext
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, mime)
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw IllegalStateException("can't create file")
                contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            } else {
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                dir.mkdirs()
                File(dir, name).outputStream().use { it.write(bytes) }
            }
            toast("Saved to Downloads: $name")
        } catch (e: Exception) {
            toast("Download failed: ${e.message}")
        }
    }

    private fun startDownload(url: String, userAgent: String?, contentDisposition: String?, mimeType: String?) {
        try {
            val name = URLUtil.guessFileName(url, contentDisposition, mimeType)
            val request = DownloadManager.Request(Uri.parse(url))
                .setMimeType(mimeType)
                .addRequestHeader("User-Agent", userAgent)
                .addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url) ?: "")
                .setTitle(name)
                .setDescription("Downloading with Zinex Browser")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            val manager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            manager.enqueue(request)
            toast("Downloading $name")
        } catch (e: Exception) {
            toast("Download failed: ${e.message}")
        }
    }

    // ----------------------------------------------------------------- tabs

    private fun currentWebView() = tabs.getOrNull(activeIndex)?.webView
    private fun activeWebView() = currentWebView()

    private fun addTab(loadHome: Boolean, url: String? = null, privateMode: Boolean = false) {
        val web = createWebView(privateMode)
        tabs.add(BrowserTab(web, privateMode, if (privateMode) "Private tab" else "New tab"))
        activeIndex = tabs.lastIndex
        webContainer.removeAllViews()
        webContainer.addView(web)
        loading = false
        progressBar.visibility = View.INVISIBLE
        updateTabCount()
        if (url != null) {
            web.loadUrl(url)
        } else if (loadHome) {
            loadHomepage(web, privateMode)
        }
        refreshChrome()
    }

    private fun switchTab(index: Int) {
        if (index !in tabs.indices) return
        activeIndex = index
        webContainer.removeAllViews()
        webContainer.addView(tabs[index].webView)
        loading = false
        progressBar.visibility = View.INVISIBLE
        updateTabCount()
        refreshChrome()
    }

    private fun closeTab(index: Int) {
        if (index !in tabs.indices) return
        val removed = tabs.removeAt(index)
        webContainer.removeView(removed.webView)
        removed.webView.stopLoading()
        removed.webView.destroy()
        if (tabs.isEmpty()) {
            activeIndex = -1
            addTab(loadHome = true)
            return
        }
        if (index < activeIndex) {
            activeIndex -= 1
        } else if (index == activeIndex) {
            switchTab(if (index >= tabs.size) tabs.size - 1 else index)
            return
        }
        updateTabCount()
    }

    private fun navigate(input: String) {
        val text = input.trim()
        if (text.isEmpty()) return
        val url = when {
            text.startsWith("https://") || text.startsWith("http://") -> text
            text.contains(".") && !text.contains(" ") -> "https://$text"
            else -> "https://www.google.com/search?q=${Uri.encode(text)}"
        }
        tabs.getOrNull(activeIndex)?.errorUrl = null
        currentWebView()?.loadUrl(url)
        currentWebView()?.requestFocus()
        hideKeyboard()
    }

    private fun reloadOrStop() {
        val tab = tabs.getOrNull(activeIndex) ?: return
        val err = tab.errorUrl
        if (loading) {
            tab.webView.stopLoading()
        } else if (err != null) {
            tab.errorUrl = null
            tab.webView.loadUrl(err)
        } else {
            tab.webView.reload()
        }
    }

    private fun goBack() {
        val web = currentWebView() ?: return
        if (web.canGoBack()) {
            web.goBack()
        } else if (!isInternal(web.url) || tabs[activeIndex].errorUrl != null) {
            showHome()
        }
    }

    private fun goForward() {
        val web = currentWebView() ?: return
        if (web.canGoForward()) web.goForward()
    }

    private fun showHome() {
        val tab = tabs.getOrNull(activeIndex) ?: return
        tab.errorUrl = null
        loadHomepage(tab.webView, tab.privateMode)
    }

    // --------------------------------------------------------------- pages

    private fun loadHomepage(web: WebView, privateTab: Boolean) {
        web.setBackgroundColor(if (hasWallpaper) Color.TRANSPARENT else Color.WHITE)
        web.loadDataWithBaseURL("$internalBase/", homeHtml(privateTab), "text/html", "UTF-8", null)
    }

    private fun showErrorPage(view: WebView?, url: String) {
        val bg = if (darkMode) "#0F1220" else "#F3F5FB"
        val fg = if (darkMode) "#FFFFFF" else "#1B2033"
        val sub = if (darkMode) "#9AA3C2" else "#6B7391"
        val esc = TextUtils.htmlEncode(url)
        val html = """
            <!doctype html><html><head><meta charset="utf-8"><title>Can't reach page</title>
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <style>
            *{box-sizing:border-box}
            body{margin:0;background:$bg;color:$fg;font-family:Roboto,Arial,sans-serif;min-height:100vh;display:flex;align-items:center;justify-content:center;text-align:center}
            .wrap{max-width:420px;padding:28px}
            .emoji{font-size:56px;margin-bottom:12px}
            h2{margin:0 0 8px;font-size:22px}
            p{margin:0 0 8px;color:$sub;font-size:14px;line-height:1.5}
            .url{word-break:break-all;font-size:12px;opacity:.8;margin-bottom:24px}
            .btn{display:inline-block;background:#6C63FF;color:#fff;text-decoration:none;font-weight:600;padding:13px 30px;border-radius:24px}
            </style></head><body><div class="wrap">
            <div class="emoji">📡</div>
            <h2>Can't reach this page</h2>
            <p>Check your internet connection and try again.</p>
            <p class="url">$esc</p>
            <a class="btn" href="$esc">Try again</a>
            </div></body></html>
        """.trimIndent()
        view?.loadDataWithBaseURL("$internalBase/error", html, "text/html", "UTF-8", null)
    }

    private fun homeHtml(privateTab: Boolean): String {
        val bg = if (darkMode) "#0F1220" else "#F3F5FB"
        val fg = if (darkMode) "#FFFFFF" else "#1B2033"
        val card = if (darkMode) "#1A1F36" else "#FFFFFF"
        val sub = if (darkMode) "#9AA3C2" else "#6B7391"
        val glass = hasWallpaper
        val pageBg = if (glass) "transparent" else bg
        val cardBg = if (glass) (if (darkMode) "rgba(26,31,54,.45)" else "rgba(255,255,255,.55)") else card
        val glassCss = if (glass) {
            ".search{backdrop-filter:blur(18px);-webkit-backdrop-filter:blur(18px);border:1px solid rgba(255,255,255,.28)}" +
                ".item,.tag{text-shadow:0 1px 4px " + (if (darkMode) "rgba(0,0,0,.65)" else "rgba(255,255,255,.7)") + "}"
        } else ""
        val title = if (privateTab) "Private tab" else "Zinex"
        val tagline = if (privateTab) "History isn't saved in this tab" else "Fast. Simple. Yours."
        val tiles = allShortcuts().joinToString("") { sc ->
            "<a class=\"item\" href=\"${escapeHtml(sc[1])}\"><div class=\"ico\" style=\"background:${escapeHtml(sc[2])}\">${escapeHtml(sc[3])}</div>${escapeHtml(sc[0])}</a>"
        }
        val items = tiles +
            "<a class=\"item\" href=\"zinex://add\"><div class=\"ico\" style=\"background:rgba(127,127,127,.28)\">+</div>Add</a>"
        return """
            <!doctype html><html><head><meta charset="utf-8"><title>Zinex</title>
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <style>
            *{box-sizing:border-box;-webkit-tap-highlight-color:transparent}
            body{margin:0;background:$pageBg;color:$fg;font-family:Roboto,Arial,sans-serif;min-height:100vh;display:flex;justify-content:center}
            .wrap{width:100%;max-width:520px;padding:64px 22px 24px;text-align:center}
            .logo{font-size:46px;font-weight:800;letter-spacing:-1.5px;margin:0;background:linear-gradient(90deg,#8B83FF,#4FD1C5);-webkit-background-clip:text;-webkit-text-fill-color:transparent}
            .tag{color:$sub;font-size:14px;margin:6px 0 30px}
            .search{display:flex;align-items:center;background:$cardBg;border-radius:28px;padding:6px 6px 6px 18px;box-shadow:0 6px 24px rgba(0,0,0,.18)}
            .search input{flex:1;min-width:0;border:0;outline:0;background:transparent;color:$fg;font-size:16px;padding:12px 4px}
            .search button{border:0;border-radius:22px;background:#6C63FF;color:#fff;font-size:15px;font-weight:600;padding:12px 20px}
            .grid{display:grid;grid-template-columns:repeat(4,1fr);gap:20px 10px;margin-top:40px}
            .item{text-decoration:none;color:$fg;font-size:12px}
            .ico{width:56px;height:56px;border-radius:18px;margin:0 auto 8px;display:flex;align-items:center;justify-content:center;font-size:24px;font-weight:700;color:#fff}
            $glassCss
            </style></head><body><div class="wrap">
            <h1 class="logo">$title</h1>
            <div class="tag">$tagline</div>
            <form class="search" action="https://www.google.com/search" method="get">
            <input name="q" placeholder="Search Google or type a URL" autocomplete="off"><button>Go</button>
            </form>
            <div class="grid">$items</div>
            </div></body></html>
        """.trimIndent()
    }

    // --------------------------------------------------------------- sheets

    private fun newSheetDialog(): android.app.Dialog {
        val dialog = android.app.Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        return dialog
    }

    private fun showSheetDialog(dialog: android.app.Dialog, content: View) {
        dialog.setContentView(content)
        val w = dialog.window
        if (w != null) {
            w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            w.setGravity(Gravity.BOTTOM)
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        dialog.show()
    }

    private fun newSheetContainer(): LinearLayout {
        val sheet = LinearLayout(this)
        sheet.orientation = LinearLayout.VERTICAL
        sheet.background = roundedTop(cSheet(), 24)
        sheet.setPadding(dp(18), dp(10), dp(18), dp(26))
        val handle = View(this)
        handle.background = rounded(cSurface2(), 3)
        val lp = LinearLayout.LayoutParams(dp(40), dp(5))
        lp.gravity = Gravity.CENTER_HORIZONTAL
        lp.bottomMargin = dp(14)
        sheet.addView(handle, lp)
        return sheet
    }

    private fun showSheet(title: String, rows: List<SheetRow>, buttons: List<SheetButton>, reopen: (() -> Unit)?) {
        val dialog = newSheetDialog()
        val sheet = newSheetContainer()

        val heading = TextView(this)
        heading.text = title
        heading.textSize = 18f
        heading.setTypeface(null, Typeface.BOLD)
        heading.setTextColor(cText())
        heading.setPadding(0, 0, 0, dp(6))
        sheet.addView(heading)

        val list = LinearLayout(this)
        list.orientation = LinearLayout.VERTICAL
        for (row in rows) {
            val line = LinearLayout(this)
            line.orientation = LinearLayout.HORIZONTAL
            line.gravity = Gravity.CENTER_VERTICAL
            line.setPadding(0, dp(10), 0, dp(10))

            val textCol = LinearLayout(this)
            textCol.orientation = LinearLayout.VERTICAL
            val t1 = TextView(this)
            t1.text = row.title
            t1.textSize = 15f
            t1.setTextColor(cText())
            t1.setSingleLine(true)
            t1.ellipsize = TextUtils.TruncateAt.END
            textCol.addView(t1)
            if (row.subtitle.isNotEmpty()) {
                val t2 = TextView(this)
                t2.text = row.subtitle
                t2.textSize = 12f
                t2.setTextColor(cSub())
                t2.setSingleLine(true)
                t2.ellipsize = TextUtils.TruncateAt.END
                textCol.addView(t2)
            }
            line.addView(textCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            val del = row.onDelete
            if (del != null) {
                val x = ImageView(this)
                x.setImageResource(R.drawable.ic_close)
                x.setColorFilter(cSub())
                x.scaleType = ImageView.ScaleType.CENTER
                x.setBackgroundResource(rippleRes())
                x.setOnClickListener {
                    dialog.dismiss()
                    del()
                    if (reopen != null) reopen()
                }
                line.addView(x, LinearLayout.LayoutParams(dp(40), dp(40)))
            }
            line.setOnClickListener {
                dialog.dismiss()
                row.onClick()
            }
            list.addView(line, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val scroll = MaxHeightScrollView(this, (resources.displayMetrics.heightPixels * 0.5).toInt())
        scroll.addView(list)
        sheet.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        if (buttons.isNotEmpty()) {
            val bar = LinearLayout(this)
            bar.orientation = LinearLayout.HORIZONTAL
            bar.setPadding(0, dp(14), 0, 0)
            for (i in buttons.indices) {
                val b = buttons[i]
                val tv = TextView(this)
                tv.text = b.label
                tv.textSize = 14f
                tv.gravity = Gravity.CENTER
                tv.setTypeface(null, Typeface.BOLD)
                tv.setTextColor(if (i == 0) Color.WHITE else cText())
                tv.background = rounded(if (i == 0) accent else cSurface2(), 22)
                tv.setOnClickListener {
                    dialog.dismiss()
                    b.action()
                }
                val lp = LinearLayout.LayoutParams(0, dp(44), 1f)
                if (i > 0) lp.setMargins(dp(10), 0, 0, 0)
                bar.addView(tv, lp)
            }
            sheet.addView(bar)
        }
        showSheetDialog(dialog, sheet)
    }

    private fun showTabs() {
        val rows = mutableListOf<SheetRow>()
        for (i in tabs.indices) {
            val tab = tabs[i]
            val url = tab.webView.url
            val subtitle = if (url == null || url.startsWith(internalBase)) {
                if (tab.privateMode) "Private" else "Home"
            } else {
                hostOf(url)
            }
            val prefix = if (i == activeIndex) "● " else ""
            rows.add(SheetRow(prefix + tab.title, subtitle, { switchTab(i) }, { closeTab(i) }))
        }
        val buttons = listOf(
            SheetButton("New tab") { addTab(loadHome = true) },
            SheetButton("Private tab") { addTab(loadHome = true, privateMode = true) }
        )
        showSheet("Tabs (${tabs.size})", rows, buttons) { showTabs() }
    }

    private fun showMenuSheet() {
        val dialog = newSheetDialog()
        val sheet = newSheetContainer()
        val items = listOf(
            MenuAction("New tab", R.drawable.ic_add, false) { addTab(loadHome = true) },
            MenuAction("Private tab", R.drawable.ic_lock, false) { addTab(loadHome = true, privateMode = true) },
            MenuAction("Bookmarks", R.drawable.ic_star, false) { showSavedEntries("Bookmarks", "bookmarks", "No bookmarks yet") },
            MenuAction("History", R.drawable.ic_history, false) { showSavedEntries("History", "history", "No history yet") },
            MenuAction("Add bookmark", R.drawable.ic_star_border, false) { addBookmark() },
            MenuAction("Pin to home", R.drawable.ic_pin, false) { pinCurrentPage() },
            MenuAction("Downloads", R.drawable.ic_download, false) { openDownloads() },
            MenuAction("Share", R.drawable.ic_share, false) { shareCurrent() },
            MenuAction("Copy link", R.drawable.ic_copy, false) { copyLink() },
            MenuAction("Desktop site", R.drawable.ic_desktop, desktopMode) { toggleDesktop() },
            MenuAction(if (darkMode) "Light mode" else "Dark mode", R.drawable.ic_dark, false) { toggleTheme() },
            MenuAction("Wallpaper", R.drawable.ic_image, hasWallpaper) { showWallpaperSheet() },
            MenuAction("Clear data", R.drawable.ic_delete, false) { showClearData() },
            MenuAction("Close tab", R.drawable.ic_close, false) { closeTab(activeIndex) }
        )
        var index = 0
        while (index < items.size) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            for (j in index until index + 4) {
                if (j < items.size) {
                    row.addView(menuCell(dialog, items[j]), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                } else {
                    row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
                }
            }
            sheet.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            index += 4
        }
        showSheetDialog(dialog, sheet)
    }

    private fun menuCell(dialog: android.app.Dialog, item: MenuAction): View {
        val cell = LinearLayout(this)
        cell.orientation = LinearLayout.VERTICAL
        cell.gravity = Gravity.CENTER_HORIZONTAL
        cell.setPadding(dp(2), dp(10), dp(2), dp(10))

        val icon = ImageView(this)
        icon.setImageResource(item.icon)
        icon.setColorFilter(if (item.active) Color.WHITE else cText())
        icon.scaleType = ImageView.ScaleType.CENTER
        val oval = GradientDrawable()
        oval.setShape(GradientDrawable.OVAL)
        oval.setColor(if (item.active) accent else cSurface2())
        if (hasWallpaper) oval.setStroke(dp(1), cGlassStroke())
        icon.background = oval
        cell.addView(icon, LinearLayout.LayoutParams(dp(52), dp(52)))

        val label = TextView(this)
        label.text = item.label
        label.textSize = 11.5f
        label.gravity = Gravity.CENTER
        label.maxLines = 2
        label.setTextColor(cText())
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(6)
        cell.addView(label, lp)

        cell.setOnClickListener {
            dialog.dismiss()
            item.action()
        }
        return cell
    }

    // -------------------------------------------------------- menu actions

    private fun addBookmark() {
        val url = fullUrl()
        val web = currentWebView()
        if (url.isEmpty() || web == null) {
            toast("Open a page first")
            return
        }
        saveEntry("bookmarks", web.title ?: url, url, 500)
        toast("Bookmark saved")
    }

    private fun openDownloads() {
        try {
            startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS))
        } catch (_: Exception) {
            toast("Downloads are managed by Android")
        }
    }

    private fun shareCurrent() {
        val url = fullUrl()
        if (url.isEmpty()) {
            toast("Open a page first")
            return
        }
        val send = Intent(Intent.ACTION_SEND)
        send.type = "text/plain"
        send.putExtra(Intent.EXTRA_TEXT, url)
        startActivity(Intent.createChooser(send, "Share link"))
    }

    private fun copyLink() {
        val url = fullUrl()
        if (url.isEmpty()) {
            toast("Open a page first")
            return
        }
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("url", url))
        toast("Link copied")
    }

    private fun toggleDesktop() {
        desktopMode = !desktopMode
        val web = currentWebView() ?: return
        web.settings.userAgentString = if (desktopMode) desktopAgent else mobileAgent()
        web.reload()
        toast(if (desktopMode) "Desktop site on" else "Mobile site on")
    }

    private fun toggleTheme() {
        darkMode = !darkMode
        getSharedPreferences("zinex", MODE_PRIVATE).edit().putBoolean("dark", darkMode).apply()
        rebuildUi()
        val tab = tabs.getOrNull(activeIndex)
        if (tab != null && isInternal(tab.webView.url) && tab.errorUrl == null) {
            loadHomepage(tab.webView, tab.privateMode)
        }
    }

    // ------------------------------------------------------------ shortcuts

    private val pinnedShortcut = arrayOf("Anabiya Craft", "https://anabiyacraft.shop", "#B8743B", "A")

    private fun defaultShortcuts(): List<Array<String>> = listOf(
        arrayOf("Google", "https://www.google.com", "#4285F4", "G"),
        arrayOf("Facebook", "https://www.facebook.com", "#1877F2", "f"),
        arrayOf("YouTube", "https://www.youtube.com", "#FF0033", "▶"),
        arrayOf("WhatsApp", "https://web.whatsapp.com", "#25D366", "W"),
        arrayOf("Gmail", "https://mail.google.com", "#EA4335", "M"),
        arrayOf("Wikipedia", "https://www.wikipedia.org", "#636B7E", "W"),
        arrayOf("Instagram", "https://www.instagram.com", "#E1306C", "I"),
        arrayOf("Maps", "https://maps.google.com", "#34A853", "◉")
    )

    private fun sameUrl(a: String, b: String) = a.trimEnd('/').equals(b.trimEnd('/'), ignoreCase = true)

    private fun storedShortcuts(): List<Array<String>> {
        val raw = getSharedPreferences("zinex", MODE_PRIVATE).getString("shortcuts", null) ?: return defaultShortcuts()
        return raw.split("\n").filter { it.isNotBlank() }.map { it.split("||").toTypedArray() }.filter { it.size >= 4 }
    }

    private fun saveShortcuts(list: List<Array<String>>) {
        val raw = list.joinToString("\n") { it.joinToString("||") }
        getSharedPreferences("zinex", MODE_PRIVATE).edit().putString("shortcuts", raw).apply()
    }

    private fun allShortcuts(): List<Array<String>> =
        listOf(pinnedShortcut) + storedShortcuts().filter { !sameUrl(it[1], pinnedShortcut[1]) }

    private fun findShortcut(url: String): Array<String>? = allShortcuts().firstOrNull { sameUrl(it[1], url) }

    private fun escapeHtml(t: String) = t.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun normalizeShortcutUrl(input: String): String? {
        var u = input.trim()
        if (u.isEmpty() || u.contains(' ')) return null
        if (!u.contains("://")) u = "https://$u"
        val uri = Uri.parse(u)
        val scheme = uri.scheme?.lowercase()
        val host = uri.host
        if ((scheme != "http" && scheme != "https") || host.isNullOrEmpty() || !host.contains('.')) return null
        return u
    }

    private fun addShortcut(title: String, url: String) {
        if (findShortcut(url) != null) {
            toast("Already on your home page")
            return
        }
        val host = hostOf(url)
        val name = title.trim().replace("||", " ").replace("\n", " ").ifBlank { host }.take(24)
        val palette = listOf("#6C63FF", "#E67E22", "#16A085", "#C0392B", "#2980B9", "#8E44AD", "#D35400", "#2C3E50")
        val color = palette[Math.abs(host.hashCode()) % palette.size]
        val glyph = name.first().uppercase()
        saveShortcuts(storedShortcuts() + listOf(arrayOf(name, url, color, glyph)))
        toast("Pinned to home")
        reloadHomeTabs()
    }

    private fun removeShortcut(url: String) {
        saveShortcuts(storedShortcuts().filter { !sameUrl(it[1], url) })
        toast("Removed from home")
        reloadHomeTabs()
    }

    private fun pinCurrentPage() {
        val url = fullUrl()
        val web = currentWebView()
        if (url.isEmpty() || web == null) {
            toast("Open a page first")
            return
        }
        addShortcut(web.title ?: "", url)
    }

    private fun showShortcutSheet(url: String, privateMode: Boolean) {
        val sc = findShortcut(url) ?: return
        if (sameUrl(sc[1], pinnedShortcut[1])) {
            toast("This shortcut is pinned")
            return
        }
        val rows = listOf(
            SheetRow("Remove from home", "Delete this shortcut", { removeShortcut(url) }),
            SheetRow("Open in new tab", hostOf(url), { addTab(false, url, privateMode) })
        )
        showSheet(sc[0], rows, emptyList(), null)
    }

    private fun showAddShortcutDialog() {
        val dialog = newSheetDialog()
        val sheet = newSheetContainer()

        val heading = TextView(this)
        heading.text = "Pin to home"
        heading.textSize = 18f
        heading.setTypeface(null, Typeface.BOLD)
        heading.setTextColor(cText())
        heading.setPadding(0, 0, 0, dp(10))
        sheet.addView(heading)

        fun field(hint: String, uri: Boolean): EditText {
            val e = EditText(this)
            e.hint = hint
            e.textSize = 15f
            e.setTextColor(cText())
            e.setHintTextColor(cSub())
            e.setSingleLine(true)
            e.inputType = if (uri) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI else InputType.TYPE_CLASS_TEXT
            e.background = rounded(cSurface2(), 14)
            e.setPadding(dp(14), dp(12), dp(14), dp(12))
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = dp(10)
            sheet.addView(e, lp)
            return e
        }
        val nameInput = field("Name (optional)", false)
        val urlInput = field("Website, e.g. example.com", true)

        val pin = TextView(this)
        pin.text = "Pin"
        pin.textSize = 14f
        pin.gravity = Gravity.CENTER
        pin.setTypeface(null, Typeface.BOLD)
        pin.setTextColor(Color.WHITE)
        pin.background = rounded(accent, 22)
        pin.setOnClickListener {
            val url = normalizeShortcutUrl(urlInput.text.toString())
            if (url == null) {
                toast("Enter a valid website")
            } else {
                dialog.dismiss()
                addShortcut(nameInput.text.toString(), url)
            }
        }
        sheet.addView(pin, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)))

        showSheetDialog(dialog, sheet)
        dialog.window?.setSoftInputMode(
            android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
        )
    }

    private fun showWallpaperSheet() {
        val rows = mutableListOf<SheetRow>()
        rows.add(SheetRow("Choose image", "Pick a photo from your gallery", { wallpaperPicker.launch("image/*") }))
        if (hasWallpaper) {
            rows.add(SheetRow("Remove wallpaper", "Back to the default look", { removeWallpaper() }))
        }
        showSheet("Wallpaper", rows, emptyList(), null)
    }

    private fun wallpaperFile() = File(filesDir, "wallpaper.jpg")

    private fun loadWallpaper() {
        val f = wallpaperFile()
        wallpaper = if (f.exists()) {
            try { BitmapFactory.decodeFile(f.absolutePath) } catch (_: Throwable) { null }
        } else null
    }

    private fun exifRotation(stream: java.io.InputStream): Int {
        return try {
            val exif = android.media.ExifInterface(stream)
            when (exif.getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL)) {
                android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90
                android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180
                android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        } catch (_: Exception) { 0 }
    }

    private fun importWallpaper(uri: Uri) {
        Thread {
            try {
                val dm = resources.displayMetrics
                val target = maxOf(dm.widthPixels, dm.heightPixels)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= target && bounds.outHeight / (sample * 2) >= target) sample *= 2
                val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                var bmp = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
                    ?: throw IllegalStateException("decode failed")
                val rotation = contentResolver.openInputStream(uri)?.use { exifRotation(it) } ?: 0
                if (rotation != 0) {
                    val m = Matrix()
                    m.postRotate(rotation.toFloat())
                    bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
                }
                wallpaperFile().outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                val done = bmp
                runOnUiThread {
                    wallpaper = done
                    applyWallpaperChange()
                    toast("Wallpaper set")
                }
            } catch (_: Throwable) {
                runOnUiThread { toast("Couldn't set wallpaper") }
            }
        }.start()
    }

    private fun removeWallpaper() {
        wallpaperFile().delete()
        wallpaper = null
        applyWallpaperChange()
        toast("Wallpaper removed")
    }

    private fun applyWallpaperChange() {
        rebuildUi()
        reloadHomeTabs()
    }

    private fun reloadHomeTabs() {
        tabs.forEach { t ->
            if (t.errorUrl == null && isInternal(t.webView.url)) {
                loadHomepage(t.webView, t.privateMode)
            }
        }
    }

    private fun showClearData() {
        val rows = listOf(
            SheetRow("Browsing history", "Pages you visited", { clearEntries("history") }),
            SheetRow("Cache", "Saved images and files", { clearCache() }),
            SheetRow("Cookies and site data", "You will be signed out of sites", { clearCookies() })
        )
        showSheet("Clear data", rows, emptyList(), null)
    }

    private fun clearCache() {
        tabs.forEach { it.webView.clearCache(true) }
        toast("Cache cleared")
    }

    private fun clearCookies() {
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        WebStorage.getInstance().deleteAllData()
        toast("Cookies and site data cleared")
    }

    // ---------------------------------------------------- bookmarks/history

    private fun saveEntry(key: String, title: String, url: String, limit: Int) {
        val prefs = getSharedPreferences("zinex", MODE_PRIVATE)
        val set = prefs.getStringSet(key, emptySet<String>())?.toMutableSet() ?: mutableSetOf<String>()
        set.removeAll { it.endsWith("||$url") }
        set.add("${System.currentTimeMillis()}||${title.take(60).replace("||", " ")}||$url")
        val trimmed = set.sortedByDescending { it.substringBefore("||").toLongOrNull() ?: 0L }.take(limit).toSet()
        prefs.edit().putStringSet(key, trimmed).apply()
    }

    private fun entriesOf(key: String): List<Triple<Long, String, String>> {
        val prefs = getSharedPreferences("zinex", MODE_PRIVATE)
        val raw = prefs.getStringSet(key, emptySet<String>()) ?: emptySet<String>()
        val out = mutableListOf<Triple<Long, String, String>>()
        for (item in raw) {
            val parts = item.split("||")
            if (parts.size >= 3) {
                out.add(Triple(parts[0].toLongOrNull() ?: 0L, parts[1], parts.drop(2).joinToString("||")))
            }
        }
        return out.sortedByDescending { it.first }
    }

    private fun removeEntry(key: String, url: String) {
        val prefs = getSharedPreferences("zinex", MODE_PRIVATE)
        val set = prefs.getStringSet(key, emptySet<String>())?.toMutableSet() ?: mutableSetOf<String>()
        set.removeAll { it.endsWith("||$url") }
        prefs.edit().putStringSet(key, set).apply()
    }

    private fun clearEntries(key: String) {
        getSharedPreferences("zinex", MODE_PRIVATE).edit().remove(key).apply()
        toast("Cleared")
    }

    private fun showSavedEntries(heading: String, key: String, emptyMessage: String) {
        val entries = entriesOf(key)
        if (entries.isEmpty()) {
            toast(emptyMessage)
            return
        }
        val rows = mutableListOf<SheetRow>()
        for (e in entries.take(100)) {
            val url = e.third
            val title = if (e.second.isBlank()) hostOf(url) else e.second
            val del: (() -> Unit)? = { removeEntry(key, url) }
            rows.add(SheetRow(title, hostOf(url), { currentWebView()?.loadUrl(url) }, del))
        }
        val buttons = listOf(SheetButton("Clear all") { clearEntries(key) })
        showSheet(heading, rows, buttons) { showSavedEntries(heading, key, emptyMessage) }
    }
}
