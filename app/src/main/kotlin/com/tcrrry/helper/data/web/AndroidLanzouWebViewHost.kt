package com.tcrrry.helper.data.web

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.tcrrry.helper.domain.artifact.ArtifactFailure
import com.tcrrry.helper.domain.artifact.ArtifactFailurePhase
import com.tcrrry.helper.domain.artifact.ArtifactSourceKind
import com.tcrrry.helper.domain.artifact.ResolvedDownloadRequest
import java.net.URI

/**
 * A detached, non-interactive WebView. It is never attached to the activity
 * hierarchy and never exposes a JavaScript bridge.
 */
@Suppress("ClickableViewAccessibility")
class AndroidLanzouWebViewHost(
    context: Context,
) : LanzouWebViewHost {
    private val applicationContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private var webView: WebView? = null
    @Volatile
    private var destroyed = false
    private var currentPageUrl: String? = null
    private var downloadCallback: ((ResolvedDownloadRequest) -> Unit)? = null
    private var failureCallback: ((ArtifactFailure) -> Unit)? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun start(
        shareUrl: String,
        onDownload: (ResolvedDownloadRequest) -> Unit,
        onFailure: (ArtifactFailure) -> Unit,
    ) {
        mainHandler.post {
            if (destroyed) return@post
            downloadCallback = onDownload
            failureCallback = onFailure
            currentPageUrl = shareUrl
            val view = WebView(applicationContext)
            webView = view
            view.visibility = View.GONE
            view.alpha = 0f
            view.isClickable = false
            view.isFocusable = false
            view.isFocusableInTouchMode = false
            view.isLongClickable = false
            view.clearFocus()
            view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            view.setOnTouchListener { _, _ -> true }
            view.setBackgroundColor(Color.TRANSPARENT)
            view.settings.apply {
                // Keep the Android System WebView default mobile UA untouched.
                javaScriptEnabled = true
                domStorageEnabled = true
                cacheMode = WebSettings.LOAD_NO_CACHE
            }
            view.webViewClient = webClient()
            view.setDownloadListener(downloadListener())
            view.loadUrl(shareUrl)
        }
    }

    override fun stopAndDestroy() {
        mainHandler.post { destroyNow() }
    }

    private fun webClient(): WebViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            if (!request.isForMainFrame) return false
            val url = request.url.toString()
            currentPageUrl = url
            if (!isLanzouPage(url)) {
                reportFailure("lanzou_redirect_forbidden", retryable = false)
                return true
            }
            return false
        }

        @Suppress("DEPRECATION")
        override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
            currentPageUrl = url
            if (!isLanzouPage(url)) {
                reportFailure("lanzou_redirect_forbidden", retryable = false)
                return true
            }
            return false
        }

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: WebResourceError,
        ) {
            if (request.isForMainFrame) reportFailure("lanzou_page_load_failed", retryable = true)
        }

        @Suppress("DEPRECATION")
        override fun onReceivedError(
            view: WebView,
            errorCode: Int,
            description: String?,
            failingUrl: String?,
        ) {
            if (failingUrl == null || failingUrl == currentPageUrl) {
                reportFailure("lanzou_page_load_failed", retryable = true)
            }
        }

        override fun onReceivedHttpError(
            view: WebView,
            request: WebResourceRequest,
            errorResponse: android.webkit.WebResourceResponse,
        ) {
            if (request.isForMainFrame && errorResponse.statusCode >= 400) {
                reportFailure("lanzou_http_${errorResponse.statusCode}", retryable = true)
            }
        }
    }

    private fun downloadListener(): DownloadListener = DownloadListener { url, userAgent, contentDisposition, mimeType, contentLength ->
        val request = ResolvedDownloadRequest(
            sourceKind = ArtifactSourceKind.LANZOU_SHARE,
            url = url,
            userAgent = userAgent,
            cookie = CookieManager.getInstance().getCookie(url),
            referer = currentPageUrl,
            contentDisposition = contentDisposition,
            mimeType = mimeType,
            contentLength = contentLength.takeIf { it >= 0L },
        )
        downloadCallback?.invoke(request)
        destroyNow()
    }

    private fun reportFailure(reasonCode: String, retryable: Boolean) {
        if (destroyed) return
        failureCallback?.invoke(
            ArtifactFailure(
                phase = ArtifactFailurePhase.SOURCE_RESOLUTION,
                sourceKind = ArtifactSourceKind.LANZOU_SHARE,
                reasonCode = reasonCode,
                retryable = retryable,
            ),
        )
        destroyNow()
    }

    private fun destroyNow() {
        if (destroyed) return
        destroyed = true
        downloadCallback = null
        failureCallback = null
        webView?.let { view ->
            view.stopLoading()
            view.setDownloadListener(null)
            view.webViewClient = WebViewClient()
            view.loadUrl("about:blank")
            (view.parent as? android.view.ViewGroup)?.removeView(view)
            view.destroy()
        }
        webView = null
    }

    private fun isLanzouPage(url: String): Boolean {
        val uri = try {
            URI(url)
        } catch (_: IllegalArgumentException) {
            return false
        }
        val host = uri.host?.lowercase() ?: return false
        val allowed = setOf("lanzou.com", "lanzouw.com", "lanzoux.com", "lanzoui.com", "lanzouy.com")
        return uri.scheme.equals("https", ignoreCase = true) &&
            (host in allowed || allowed.any { host.endsWith(".$it") })
    }
}
