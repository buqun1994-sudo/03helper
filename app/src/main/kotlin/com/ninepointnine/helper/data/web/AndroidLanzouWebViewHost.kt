package com.ninepointnine.helper.data.web

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
import com.ninepointnine.helper.domain.artifact.ArtifactFailure
import com.ninepointnine.helper.domain.artifact.ArtifactFailurePhase
import com.ninepointnine.helper.domain.artifact.ArtifactSourceKind
import com.ninepointnine.helper.domain.artifact.ReleaseSourcePolicy
import com.ninepointnine.helper.domain.artifact.ResolvedDownloadRequest
import org.json.JSONObject
import org.json.JSONTokener
import java.net.URI

/**
 * A detached, non-interactive WebView. It is never attached to the activity
 * hierarchy and never exposes a JavaScript bridge.
 */
@Suppress("ClickableViewAccessibility")
class AndroidLanzouWebViewHost(
    context: Context,
    private val sourcePolicy: ReleaseSourcePolicy = ReleaseSourcePolicy(),
) : LanzouWebViewHost, LanzouFolderWebViewHost {
    private val applicationContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private var webView: WebView? = null
    @Volatile
    private var destroyed = false
    private var currentPageUrl: String? = null
    private var downloadCallback: ((ResolvedDownloadRequest) -> Unit)? = null
    private var failureCallback: ((ArtifactFailure) -> Unit)? = null
    private var folderEntriesCallback: ((List<LanzouFolderEntry>) -> Unit)? = null
    private var folderFailureCallback: ((ArtifactFailure) -> Unit)? = null
    private var operation: Operation = Operation.NONE
    private var folderPassword: String? = null
    private var folderSnapshotStabilizer: FolderEntrySnapshotStabilizer? = null
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
            folderEntriesCallback = null
            folderFailureCallback = null
            folderPassword = null
            operation = Operation.DOWNLOAD
            currentPageUrl = shareUrl
            triggerAttempts = 0
            val view = createWebView()
            view.loadUrl(shareUrl)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun startFolder(
        folderUrl: String,
        password: String,
        expectedArchiveFileNames: Set<String>,
        onEntries: (List<LanzouFolderEntry>) -> Unit,
        onFailure: (ArtifactFailure) -> Unit,
    ) {
        Log.d(TAG, "folder_start")
        mainHandler.post {
            if (destroyed) return@post
            downloadCallback = null
            failureCallback = null
            folderEntriesCallback = onEntries
            folderFailureCallback = onFailure
            folderPassword = password
            folderSnapshotStabilizer = FolderEntrySnapshotStabilizer(expectedArchiveFileNames)
            operation = Operation.FOLDER
            currentPageUrl = folderUrl
            triggerAttempts = 0
            val view = createWebView()
            view.loadUrl(folderUrl)
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
                Log.d(TAG, "transient_download_capture host=${safeHost(url)}")
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
                Log.d(TAG, "transient_download_capture host=${safeHost(url)}")
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
            if (operation == Operation.FOLDER) {
                triggerFolderPageAction(view)
            } else {
                triggerPageAction(view)
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView {
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
        return view
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

    private fun triggerFolderPageAction(view: WebView) {
        if (destroyed) return
        if (triggerAttempts++ >= MAX_TRIGGER_ATTEMPTS) {
            when (val decision = folderSnapshotStabilizer?.completeAtDeadline()) {
                is FolderSnapshotDecision.Complete -> {
                    folderEntriesCallback?.invoke(decision.entries)
                    destroyNow()
                }

                FolderSnapshotDecision.Wait,
                null,
                -> reportFolderFailure("lanzou_folder_parse_timeout", retryable = true)
            }
            return
        }
        val passwordLiteral = JSONObject.quote(folderPassword.orEmpty())
        view.evaluateJavascript(
            """
            (function(){
              var links=[].slice.call(document.querySelectorAll('#infos .mbx a.mlink, #infos #ready a')).map(function(a){
                var href=a.getAttribute('href') || '';
                var row=a.closest('#ready') || a.closest('.mbx') || a.parentElement;
                var nameNode=a.cloneNode(true);
                var meta=nameNode.querySelector('.mmr');
                if(meta){ meta.remove(); }
                var sizeNode=row ? row.querySelector('#size, .size, .sizeh') : null;
                var timeNode=row ? row.querySelector('#time, .time, .timeh') : null;
                return {
                  href:href,
                  name:(nameNode.textContent || '').trim(),
                  size:sizeNode ? (sizeNode.textContent || '').trim() : '',
                  modified:timeNode ? (timeNode.textContent || '').trim() : '',
                  rowText:row ? (row.innerText || row.textContent || '').trim() : ''
                };
              }).filter(function(item){ return item.href && item.name; });
              if(links.length){ return JSON.stringify({kind:'entries',entries:links}); }
              var info=(document.getElementById('infos') || {}).innerText || '';
              if(info.indexOf('没有文件') >= 0 || info.indexOf('获取失败') >= 0){
                return JSON.stringify({kind:'failure'});
              }
              var pwd=document.getElementById('pwd');
              var submit=document.getElementById('sub');
              if(pwd && submit && !window.__03helperFolderSubmitted){
                pwd.value=$passwordLiteral;
                window.__03helperFolderSubmitted=true;
                submit.click();
                return 'submitted';
              }
              return 'wait';
            })()
            """.trimIndent(),
        ) { rawResult ->
            if (destroyed) return@evaluateJavascript
            when (val parsed = parseFolderResult(rawResult)) {
                is FolderPageResult.Entries -> {
                    when (val decision = folderSnapshotStabilizer?.observe(parsed.entries)) {
                        is FolderSnapshotDecision.Complete -> {
                            folderEntriesCallback?.invoke(decision.entries)
                            destroyNow()
                        }

                        FolderSnapshotDecision.Wait,
                        null,
                        -> mainHandler.postDelayed(
                            { triggerFolderPageAction(view) },
                            TRIGGER_RETRY_DELAY_MILLIS,
                        )
                    }
                }

                FolderPageResult.Failure -> reportFolderFailure("lanzou_folder_empty", retryable = false)
                FolderPageResult.Wait -> mainHandler.postDelayed(
                    { triggerFolderPageAction(view) },
                    TRIGGER_RETRY_DELAY_MILLIS,
                )
            }
        }
    }

    private fun parseFolderResult(rawResult: String?): FolderPageResult {
        if (rawResult.isNullOrBlank()) return FolderPageResult.Wait
        val value = runCatching { JSONTokener(rawResult).nextValue() }.getOrNull()
        val json = (value as? String)?.let {
            runCatching { JSONObject(it) }.getOrNull()
        } ?: (value as? JSONObject)
        if (json == null) return FolderPageResult.Wait
        return when (json.optString("kind")) {
            "failure" -> FolderPageResult.Failure
            "entries" -> {
                val entriesJson = json.optJSONArray("entries") ?: return FolderPageResult.Wait
                val entries = buildList {
                    for (index in 0 until entriesJson.length()) {
                        val item = entriesJson.optJSONObject(index) ?: continue
                        val href = item.optString("href").trim()
                        val name = item.optString("name").trim()
                        val sizeLabel = item.optString("size").trim().takeIf { it.isNotBlank() }
                            ?: item.optString("rowText").trim().takeIf { it.isNotBlank() }
                        val modifiedLabel = item.optString("modified").trim().takeIf { it.isNotBlank() }
                        val id = runCatching {
                            URI(href).path.orEmpty().trim('/').substringAfterLast('/')
                        }.getOrDefault("")
                        if (id.matches(FOLDER_ENTRY_ID_PATTERN) && name.isNotBlank()) {
                            add(
                                LanzouFolderEntry(
                                    id = id,
                                    name = name,
                                    sizeLabel = sizeLabel?.let(::normalizeSizeLabel),
                                    modifiedLabel = modifiedLabel,
                                ),
                            )
                        }
                    }
                }
                FolderPageResult.Entries(entries)
            }

            else -> FolderPageResult.Wait
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

    private fun reportFolderFailure(reasonCode: String, retryable: Boolean) {
        if (destroyed) return
        Log.w(TAG, "folder_failure reason=$reasonCode retryable=$retryable")
        folderFailureCallback?.invoke(
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
        folderEntriesCallback = null
        folderFailureCallback = null
        folderPassword = null
        folderSnapshotStabilizer = null
        operation = Operation.NONE
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

    private fun normalizePageUrl(url: String): String = url.substringBefore('#')

    private companion object {
        const val TAG = "03helper.Lanzou"
        const val MAX_TRIGGER_ATTEMPTS = 48
        const val TRIGGER_RETRY_DELAY_MILLIS = 200L
        val FOLDER_ENTRY_ID_PATTERN = Regex("^i[a-zA-Z0-9]+$")
        val SIZE_LABEL_PATTERN = Regex("([0-9]+(?:\\.[0-9]+)?)\\s*(B|K|KB|M|MB|G|GB|T|TB)", RegexOption.IGNORE_CASE)

        fun normalizeSizeLabel(value: String): String? {
            val match = SIZE_LABEL_PATTERN.find(value.trim()) ?: return null
            val unit = when (match.groupValues[2].uppercase()) {
                "K" -> "KB"
                "M" -> "MB"
                "G" -> "GB"
                "T" -> "TB"
                else -> match.groupValues[2].uppercase()
            }
            return "${match.groupValues[1]} $unit"
        }
    }

    private enum class Operation {
        NONE,
        DOWNLOAD,
        FOLDER,
    }

    private sealed interface FolderPageResult {
        data class Entries(val entries: List<LanzouFolderEntry>) : FolderPageResult
        data object Failure : FolderPageResult
        data object Wait : FolderPageResult
    }
}
