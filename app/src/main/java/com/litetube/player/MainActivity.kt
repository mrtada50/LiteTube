package com.litetube.player

import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PorterDuff
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ProgressBar
import java.io.ByteArrayInputStream

class MainActivity : Activity() {

    private val homeUrl = "https://m.youtube.com"

    private lateinit var root: FrameLayout
    private lateinit var progress: ProgressBar
    private lateinit var web: WebView
    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null

    // الدومينات المسموحة فقط. أي شي ثاني ينمنع.
    private val allowedHosts = listOf(
        "youtube.com", "youtube-nocookie.com", "ytimg.com",
        "google.com", "gstatic.com", "googleusercontent.com"
    )

    // إعلانات وتتبع: نمنعها لتخفيف الحمل على الجهاز الضعيف
    private val blockedHosts = listOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com",
        "google-analytics.com", "googletagmanager.com", "googletagservices.com"
    )

    private fun hostMatches(host: String, list: List<String>) =
        list.any { host == it || host.endsWith(".$it") }

    private fun isAllowed(url: String?): Boolean {
        if (url == null) return false
        if (url == "about:blank") return true
        val uri = Uri.parse(url)
        if (uri.scheme != "https") return false
        val host = uri.host ?: return false
        return hostMatches(host, allowedHosts)
    }

    private fun isBlockedRequest(uri: Uri): Boolean {
        val host = uri.host ?: return false
        if (hostMatches(host, blockedHosts)) return true
        val path = uri.path ?: return false
        return path.startsWith("/pagead/")
    }

    // نفس User-Agent حق الـ WebView الحقيقي (بنفس رقم إصداره) بس بدون علامة "wv"
    // عشان يوتيوب يعامله كمتصفح عادي ويرسل له كود يقدر يشغله
    private fun buildUserAgent(): String =
        WebSettings.getDefaultUserAgent(this)
            .replace("; wv", "")
            .replace(" Version/4.0", "")

    private fun chromeVersion(): String =
        Regex("Chrome/([\\d.]+)").find(WebSettings.getDefaultUserAgent(this))
            ?.groupValues?.get(1) ?: "unknown"

    private fun showError(view: WebView, msg: String) {
        val html = "<html><head><meta name='viewport' content='width=device-width,initial-scale=1'></head>" +
            "<body style='background:#111;color:#eee;font-family:sans-serif;padding:24px;text-align:center;direction:rtl'>" +
            "<h2>تعذّر تحميل الصفحة</h2><p>$msg</p>" +
            "<p style='color:#888;font-size:12px'>WebView: Chrome ${chromeVersion()}</p>" +
            "<p><a href='$homeUrl' style='color:#ff4d40;font-size:22px'>إعادة المحاولة</a></p>" +
            "</body></html>"
        view.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView {
        val w = WebView(this)
        w.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            setSupportZoom(false)
            builtInZoomControls = false
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            cacheMode = WebSettings.LOAD_DEFAULT
            userAgentString = buildUserAgent()
        }

        w.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                return !isAllowed(request.url.toString())
            }

            @Suppress("OVERRIDE_DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                return !isAllowed(url)
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                return if (isBlockedRequest(request.url)) {
                    WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                } else null
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                if (!isAllowed(url)) view.stopLoading()
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: android.webkit.WebResourceError
            ) {
                if (request.isForMainFrame) {
                    showError(view, "تأكد من الاتصال بالإنترنت ثم أعد المحاولة.")
                }
            }

            @Suppress("OVERRIDE_DEPRECATION")
            override fun onReceivedError(view: WebView, errorCode: Int, description: String?, failingUrl: String?) {
                if (failingUrl != null && failingUrl == view.url) {
                    showError(view, "تأكد من الاتصال بالإنترنت ثم أعد المحاولة.")
                }
            }

            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                handler.cancel()
                showError(view, "خطأ في الشهادة الأمنية. تأكد أن تاريخ ووقت الجهاز صحيحين.")
            }

            // الجهاز عنده 1GB رام: لو النظام قتل عملية العرض نعيد بناء الـ WebView بدل ما يتجمد أو يطلع أبيض
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                recreateWebView()
                return true
            }
        }

        w.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progress.progress = newProgress
                progress.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
            }

            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                if (customView != null) {
                    callback.onCustomViewHidden()
                    return
                }
                customView = view
                customCallback = callback
                view.setBackgroundColor(Color.BLACK)
                root.addView(
                    view,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )
                web.visibility = View.GONE
                enterImmersive()
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            }

            override fun onHideCustomView() {
                leaveFullscreen()
            }
        }
        return w
    }

    private fun leaveFullscreen() {
        val v = customView ?: return
        root.removeView(v)
        customView = null
        customCallback?.onCustomViewHidden()
        customCallback = null
        web.visibility = View.VISIBLE
        exitImmersive()
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    private fun recreateWebView() {
        leaveFullscreen()
        root.removeView(web)
        web.destroy()
        web = createWebView()
        root.addView(web, 0, matchParent())
        web.loadUrl(homeUrl)
    }

    private fun matchParent() = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT
    )

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)

        web = createWebView()
        root.addView(web, matchParent())

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        progress.max = 100
        progress.visibility = View.GONE
        progress.progressDrawable.setColorFilter(Color.parseColor("#E62117"), PorterDuff.Mode.SRC_IN)
        val h = (3 * resources.displayMetrics.density).toInt().coerceAtLeast(3)
        root.addView(
            progress,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h, Gravity.TOP)
        )

        setContentView(root)
        CookieManager.getInstance().setAcceptCookie(true)

        web.loadUrl(homeUrl)
    }

    @Suppress("DEPRECATION")
    private fun enterImmersive() {
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
    }

    @Suppress("DEPRECATION")
    private fun exitImmersive() {
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        when {
            customView != null -> leaveFullscreen()
            web.canGoBack() -> web.goBack()
            else -> super.onBackPressed()
        }
    }

    override fun onPause() {
        super.onPause()
        web.onPause()
    }

    override fun onResume() {
        super.onResume()
        web.onResume()
    }

    override fun onDestroy() {
        root.removeAllViews()
        web.destroy()
        super.onDestroy()
    }
}
