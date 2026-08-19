package com.tcrrry.helper.data.web

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.Log
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
import com.tcrrry.helper.domain.artifact.ReleaseSourcePolicy
import com.tcrrry.helper.domain.artifact.ResolvedDownloadRequest
import java.net.URI

/**
 * A detached, non-interactive WebView. It is never attached to the activity
 * hierarchy and never exposes a JavaScript bridge.
 */
@Suppress("ClickableViewAccessibility")
class AndroidLanzouWebViewHost(
    context: Context,
    private val sourcePolicy: ReleaseSourcePolicy = ReleaseSourcePolicy(),
) : LanzouWebViewHost {
    private val applicationContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private var webView: WebView? = null
    @Volatile
    private var destroyed = false
    private var currentPageUrl: String? = null
    private var downloadCallback: ((ResolvedDownloadRequest) -> Unit)? = null
    private var failureCallback: ((ArtifactFailure) -> Unit)? = null
    private var triggerAttempts = 0

    @SuppressLint("SetJavaScriptEnabled")
    override fun start(
        shareUrl: String,
        onDownload: (ResolvedDownloadRequest) -> Unit,
        onFailure: (ArtifactFailure) -> Unit,
    ) {
        Log.d(TAG, "webview_start")
        mainHandler.post {
            if (destroyed) return@post
            downloadCallback = onDownload
            failureCallback = onFailure
            currentPageUrl = shareUrl
            triggerAttempts = 0
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
            if (
                request.method.equals("GET", ignoreCase = true) &&
                sourcePolicy.isLanzouTransientDownloadUrl(url)
            ) {
                Log.d(TAG, "transient_download_capture host=${safeHost(url)} path=${safePathPrefix(url)}")
                completeDownload(
                    url = url,
                    userAgent = view.settings.userAgentString,
                    referer = currentPageUrl,
                )
                return true
            }
            val pageUrl = normalizePageUrl(url)
            currentPageUrl = pageUrl
            if (!sourcePolicy.isLanzouSharePage(pageUrl) && !sourcePolicy.isLanzouVerificationPage(pageUrl)) {
                Log.w(TAG, "navigation_forbidden method=${request.method} host=${safeHost(url)}")
                reportFailure("lanzou_redirect_forbidden", retryable = false)
                return true
            }
            return false
        }

        @Suppress("DEPRECATION")
        override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
            if (sourcePolicy.isLanzouTransientDownloadUrl(url)) {
                Log.d(TAG, "transient_download_capture host=${safeHost(url)} path=${safePathPrefix(url)}")
                completeDownload(
                    url = url,
                    userAgent = view.settings.userAgentString,
                    referer = currentPageUrl,
                )
                return true
            }
            val pageUrl = normalizePageUrl(url)
            currentPageUrl = pageUrl
            if (!sourcePolicy.isLanzouSharePage(pageUrl) && !sourcePolicy.isLanzouVerificationPage(pageUrl)) {
                Log.w(TAG, "navigation_forbidden host=${safeHost(url)}")
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

        override fun onPageFinished(view: WebView, url: String) {
            val pageUrl = normalizePageUrl(url)
            Log.d(TAG, "page_finished host=${safeHost(pageUrl)}")
            currentPageUrl = pageUrl
            if (!sourcePolicy.isLanzouSharePage(pageUrl) && !sourcePolicy.isLanzouVerificationPage(pageUrl)) {
                Log.w(TAG, "page_forbidden host=${safeHost(url)}")
                reportFailure("lanzou_redirect_forbidden", retryable = false)
                return
            }
            triggerPageAction(view)
        }
    }

    private fun triggerPageAction(view: WebView) {
        if (destroyed) return
        if (triggerAttempts++ >= MAX_TRIGGER_ATTEMPTS) {
            reportFailure("lanzou_download_trigger_timeout", retryable = true)
            return
        }
        view.evaluateJavascript(
            """
            (function(){
              var link=document.querySelector('#go a[href],#ok a[href],a.tc2');
              if(link && /^https:/i.test(link.href)){
                link.removeAttribute('target');
                link.click();
                return 'download';
              }
              var action=document.querySelector('#go [onclick*=\"down_r\"],#sub [onclick*=\"down_r\"],#sub2 [onclick*=\"down_r\"]');
              if(action && !window.__03helperVerificationTriggered){
                window.__03helperVerificationTriggered=true;
                action.click();
                return 'verify';
              }
              var share=document.querySelector('#downurl,#submit');
              if(share && !window.__03helperShareTriggered){
                window.__03helperShareTriggered=true;
                share.removeAttribute('target');
                share.focus();
                share.click();
                return 'share';
              }
              return 'wait';
            })()
            """.trimIndent(),
        ) { result ->
            if (!destroyed && result?.contains("download") != true) {
                mainHandler.postDelayed({ triggerPageAction(view) }, TRIGGER_RETRY_DELAY_MILLIS)
            }
        }
    }

    private fun downloadListener(): DownloadListener = DownloadListener { url, userAgent, contentDisposition, mimeType, contentLength ->
        Log.d(TAG, "download_callback host=${runCatching { URI(url).host }.getOrNull()} mime=${mimeType ?: "unknown"}")
        if (!sourcePolicy.isLanzouTransientDownloadUrl(url)) {
            reportFailure("lanzou_download_target_invalid", retryable = true)
            return@DownloadListener
        }
        completeDownload(
            url = url,
            userAgent = userAgent,
            referer = currentPageUrl,
            contentDisposition = contentDisposition,
            mimeType = mimeType,
            contentLength = contentLength.takeIf { it >= 0L },
        )
    }

    private fun completeDownload(
        url: String,
        userAgent: String?,
        referer: String?,
        contentDisposition: String? = null,
        mimeType: String? = null,
        contentLength: Long? = null,
    ) {
        if (destroyed) return
        val request = ResolvedDownloadRequest(
            sourceKind = ArtifactSourceKind.LANZOU_SHARE,
            url = url,
            userAgent = userAgent,
            cookie = CookieManager.getInstance().getCookie(url),
            referer = referer,
            contentDisposition = contentDisposition,
            mimeType = mimeType,
            contentLength = contentLength,
        )
        downloadCallback?.invoke(request)
        destroyNow()
    }

    private fun reportFailure(reasonCode: String, retryable: Boolean) {
        if (destroyed) return
        Log.w(TAG, "webview_failure reason=$reasonCode retryable=$retryable")
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

    private fun safeHost(url: String): String = runCatching { URI(url).host }
        .getOrNull()
        ?.lowercase()
        .orEmpty()

    private fun safePathPrefix(url: String): String = runCatching {
        URI(url).path.orEmpty().trim('/').substringBefore('/').lowercase()
    }.getOrDefault("")

    private fun normalizePageUrl(url: String): String = url.substringBefore('#')

    private companion object {
        const val TAG = "03helper.Lanzou"
        const val MAX_TRIGGER_ATTEMPTS = 48
        const val TRIGGER_RETRY_DELAY_MILLIS = 200L
    }
}
