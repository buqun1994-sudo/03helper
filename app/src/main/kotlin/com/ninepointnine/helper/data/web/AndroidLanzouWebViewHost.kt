package com.ninepointnine.helper.data.web

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
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
import java.lang.ref.WeakReference
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Process-level rendezvous for the current Activity-owned WebView layer.
 * The Application keeps only this weak reference; an operation holds the
 * container strongly only until its terminal callback.
 */
class LanzouWebViewMountRegistry {
    private var currentContainer: WeakReference<FrameLayout>? = null

    @Synchronized
    fun register(container: FrameLayout) {
        currentContainer = WeakReference(container)
    }

    @Synchronized
    fun unregister(container: FrameLayout) {
        if (currentContainer?.get() === container) currentContainer = null
    }

    @Synchronized
    internal fun current(): FrameLayout? = currentContainer?.get()
        ?.takeIf { it.isAttachedToWindow }
}

/** Activity-attached, non-interactive WebView with no JavaScript bridge. */
@Suppress("ClickableViewAccessibility")
class AndroidLanzouWebViewHost(
    private val mountRegistry: LanzouWebViewMountRegistry,
    private val sourcePolicy: ReleaseSourcePolicy = ReleaseSourcePolicy(),
) : LanzouWebViewHost, LanzouFolderWebViewHost {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val terminal = AtomicBoolean(false)
    private var webView: WebView? = null
    private var mountContainer: FrameLayout? = null
    private var currentPageUrl: String? = null
    private var downloadCallback: ((ResolvedDownloadRequest) -> Unit)? = null
    private var failureCallback: ((ArtifactFailure) -> Unit)? = null
    private var folderEntriesCallback: ((List<LanzouFolderEntry>) -> Unit)? = null
    private var folderFailureCallback: ((ArtifactFailure) -> Unit)? = null
    private var operation: Operation = Operation.NONE
    private var folderPassword: String? = null
    private var folderSnapshotStabilizer: FolderEntrySnapshotStabilizer? = null
    private var triggerAttempts = 0
    private val attachStateListener = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) = Unit

        override fun onViewDetachedFromWindow(view: View) {
            if (terminal.get() || view !== webView) return
            when (operation) {
                Operation.DOWNLOAD -> reportFailure("lanzou_webview_host_detached", retryable = true)
                Operation.FOLDER -> reportFolderFailure("lanzou_webview_host_detached", retryable = true)
                Operation.NONE -> Unit
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun start(
        shareUrl: String,
        onDownload: (ResolvedDownloadRequest) -> Unit,
        onFailure: (ArtifactFailure) -> Unit,
    ) {
        Log.d(TAG, "webview_start")
        mainHandler.post {
            if (terminal.get()) return@post
            downloadCallback = onDownload
            failureCallback = onFailure
            folderEntriesCallback = null
            folderFailureCallback = null
            folderPassword = null
            operation = Operation.DOWNLOAD
            currentPageUrl = shareUrl
            triggerAttempts = 0
            val view = createWebView() ?: run {
                reportFailure("lanzou_webview_host_unavailable", retryable = true)
                return@post
            }
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
            if (terminal.get()) return@post
            downloadCallback = null
            failureCallback = null
            folderEntriesCallback = onEntries
            folderFailureCallback = onFailure
            folderPassword = password
            folderSnapshotStabilizer = FolderEntrySnapshotStabilizer(expectedArchiveFileNames)
            operation = Operation.FOLDER
            currentPageUrl = folderUrl
            triggerAttempts = 0
            val view = createWebView() ?: run {
                reportFolderFailure("lanzou_webview_host_unavailable", retryable = true)
                return@post
            }
            view.loadUrl(folderUrl)
        }
    }

    override fun stopAndDestroy() {
        mainHandler.post {
            terminal.set(true)
            destroyNow()
        }
    }

    private fun webClient(): WebViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            if (!request.isForMainFrame) return false
            val url = request.url.toString()
            if (
                request.method.equals("GET", ignoreCase = true) &&
                sourcePolicy.isLanzouVerificationPage(url)
            ) return false
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
            if (sourcePolicy.isLanzouVerificationPage(url)) return false
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
            if (terminal.get()) return
            val pageUrl = normalizePageUrl(url)
            Log.d(TAG, "page_finished host=${safeHost(pageUrl)}")
            currentPageUrl = pageUrl
            val verificationPage = sourcePolicy.isLanzouVerificationPage(pageUrl)
            if (verificationPage) {
                if (operation == Operation.FOLDER) {
                    triggerFolderPageAction(view)
                } else {
                    triggerPageAction(view)
                }
                return
            }
            if (sourcePolicy.isLanzouTransientDownloadUrl(pageUrl)) {
                // A transient endpoint that renders HTML is a risk-control or
                // error page, never an installable archive response. Give the
                // download listener a brief chance to win the WebView race.
                mainHandler.postDelayed({
                    if (!terminal.get() && currentPageUrl == pageUrl) {
                        reportFailure("lanzou_html_response", retryable = true)
                    }
                }, HTML_RESPONSE_GRACE_MILLIS)
                return
            }
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
    private fun createWebView(): WebView? {
        val container = mountRegistry.current() ?: return null
        val view = WebView(container.context)
        webView = view
        mountContainer = container
        view.visibility = View.VISIBLE
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
        view.addOnAttachStateChangeListener(attachStateListener)
        container.addView(
            view,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        return view
    }

    private fun triggerPageAction(view: WebView) {
        if (terminal.get()) return
        if (triggerAttempts++ >= MAX_TRIGGER_ATTEMPTS) {
            reportFailure("lanzou_download_trigger_timeout", retryable = true)
            return
        }
        view.evaluateJavascript(
            """
            (function(){
              function resolvedHref(doc,node){
                var raw=(node.getAttribute('href') || '').trim();
                if(!raw || /^#/i.test(raw) || /^javascript:/i.test(raw)) return '';
                var probe=doc.createElement('a');
                probe.href=raw;
                var resolved=probe.href || '';
                if(!/^https:/i.test(resolved)) return '';
                var sameHost=(probe.host || '').toLowerCase()===(doc.location.host || '').toLowerCase();
                if(sameHost && !/^\/(?:tp\/)?i[a-z0-9]+$/i.test(probe.pathname || '')) return '';
                var current=(doc.location.href || '').split('#')[0];
                if(resolved.split('#')[0]===current) return '';
                return resolved;
              }
              function isVisible(win,node){
                if(!node || node.disabled || node.getAttribute('aria-hidden')==='true') return false;
                try{
                  var style=win && win.getComputedStyle ? win.getComputedStyle(node) : null;
                  if(style && (style.display==='none' || style.visibility==='hidden' || style.visibility==='collapse' || style.opacity==='0')) return false;
                  if(typeof node.getClientRects==='function') return node.getClientRects().length>0;
                }catch(ignore){ return false; }
                return true;
              }
              function firstVisible(doc,win,selector){
                var nodes=[].slice.call(doc.querySelectorAll(selector));
                for(var i=0;i<nodes.length;i++) if(isVisible(win,nodes[i])) return nodes[i];
                return null;
              }
              function inspect(doc,win,depth){
                if(!doc || depth>3) return {kind:'wait'};
                // Lanzou currently renders the final link in a same-origin
                // iframe. Read it directly instead of relying on target=_blank
                // navigation or a bubbling click from a hidden view.
                var candidates=[].slice.call(doc.querySelectorAll('#tourl a[href],#go a[href],#sub a[href],#sub2 a[href],#ok a[href],a.tc2,#downurl,#submit'));
                for(var i=0;i<candidates.length;i++){
                  if(!isVisible(win,candidates[i])) continue;
                  var href=resolvedHref(doc,candidates[i]);
                  if(href) return {kind:'link',url:href,referer:(doc.location.href || '')};
                }

                // The transient developer page exposes a documented down_r(n)
                // action. Invoke it once; the next pass reads its generated
                // zip/webgetstore link without opening a second window.
                var action=firstVisible(doc,win,'#go [onclick*=\"down_r\"],#sub [onclick*=\"down_r\"],#sub2 [onclick*=\"down_r\"]');
                if(action && !win.__03helperVerificationTriggered){
                  win.__03helperVerificationTriggered=true;
                  try{
                    action.click();
                    return {kind:'triggered'};
                  }catch(ignore){}
                }

                // The mobile share page wires the real URL on focus/mousedown
                // of #submit. Dispatch only to that element so its handler
                // runs without bubbling into the page-level m_load() hook.
                var share=firstVisible(doc,win,'#downurl,#submit');
                if(share){
                  share.removeAttribute('target');
                  // Calling the element handlers directly keeps this path
                  // independent of whether the host view itself owns focus.
                  // The non-bubbling event remains a fallback for pages that
                  // register listeners with addEventListener instead of an
                  // inline handler.
                  try{ if(typeof share.onfocus==='function') share.onfocus.call(share); }catch(ignore){}
                  var generated=resolvedHref(doc,share);
                  if(!generated){
                    try{
                      if(typeof share.onmousedown==='function') share.onmousedown.call(share);
                    }catch(ignore){}
                    generated=resolvedHref(doc,share);
                  }
                  if(!generated){
                    try{
                      var down;
                      if(typeof win.MouseEvent==='function'){
                        down=new win.MouseEvent('mousedown',{bubbles:false,cancelable:true,view:win});
                      }else{
                        down=doc.createEvent('MouseEvents');
                        down.initMouseEvent('mousedown',false,true,win,1,0,0,0,0,false,false,false,false,0,null);
                      }
                      share.dispatchEvent(down);
                    }catch(ignore){}
                    generated=resolvedHref(doc,share);
                  }
                  if(generated) return {kind:'link',url:generated,referer:(doc.location.href || '')};
                }

                var frames=doc.getElementsByTagName('iframe');
                for(var frameIndex=0;frameIndex<frames.length;frameIndex++){
                  try{
                    var child=frames[frameIndex].contentDocument;
                    var childWindow=frames[frameIndex].contentWindow;
                    var childResult=inspect(child,childWindow,depth+1);
                    if(childResult.kind!=='wait') return childResult;
                  }catch(ignore){}
                }

                var pageText=(doc.body && (doc.body.innerText || doc.body.textContent)) || '';
                if(/网络异常|需要验证后下载|请输入验证码/i.test(pageText)){
                  // Keep polling while the one-shot down_r request is still
                  // waiting for its asynchronous response. A real challenge
                  // eventually expires through the outer bounded timeout.
                  if(win.__03helperVerificationTriggered) return {kind:'wait'};
                  return {kind:'verification'};
                }
                return {kind:'wait'};
              }
              return JSON.stringify(inspect(document,window,0));
            })()
            """.trimIndent(),
        ) { rawResult ->
            if (terminal.get()) return@evaluateJavascript
            when (val action = parsePageActionResult(rawResult)) {
                is PageActionResult.Link -> {
                    val url = action.url
                    when {
                        sourcePolicy.isLanzouTransientDownloadUrl(url) &&
                            !sourcePolicy.isLanzouVerificationPage(url) -> {
                            Log.d(TAG, "transient_download_capture host=${safeHost(url)}")
                            completeDownload(
                                url = url,
                                userAgent = view.settings.userAgentString,
                                referer = action.referer?.takeIf(String::isNotBlank) ?: currentPageUrl,
                            )
                        }

                        sourcePolicy.isLanzouVerificationPage(url) || sourcePolicy.isLanzouSharePage(url) -> {
                            currentPageUrl = normalizePageUrl(url)
                            view.loadUrl(url)
                        }

                        else -> reportFailure("lanzou_download_target_invalid", retryable = true)
                    }
                }

                PageActionResult.VerificationRequired -> {
                    reportFailure("lanzou_verification_required", retryable = false)
                }

                PageActionResult.Triggered,
                PageActionResult.Wait,
                -> mainHandler.postDelayed({ triggerPageAction(view) }, TRIGGER_RETRY_DELAY_MILLIS)
            }
        }
    }

    private fun parsePageActionResult(rawResult: String?): PageActionResult {
        if (rawResult.isNullOrBlank()) return PageActionResult.Wait
        val value = runCatching { JSONTokener(rawResult).nextValue() }.getOrNull()
        val json = (value as? String)?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?: (value as? JSONObject)
            ?: return PageActionResult.Wait
        val url = json.optString("url").trim()
        val referer = json.optString("referer").trim().takeIf { it.isNotBlank() }
        return when (json.optString("kind")) {
            "link" -> if (url.isBlank()) PageActionResult.Wait else PageActionResult.Link(url, referer)
            "triggered" -> PageActionResult.Triggered
            "verification" -> PageActionResult.VerificationRequired
            else -> PageActionResult.Wait
        }
    }

    private fun triggerFolderPageAction(view: WebView) {
        if (terminal.get()) return
        if (triggerAttempts++ >= MAX_TRIGGER_ATTEMPTS) {
            when (val decision = folderSnapshotStabilizer?.completeAtDeadline()) {
                is FolderSnapshotDecision.Complete -> {
                    completeFolderEntries(decision.entries)
                }

                FolderSnapshotDecision.Wait,
                null,
                -> reportFolderFailure("lanzou_folder_parse_timeout", retryable = true)
            }
            return
        }
        val passwordLiteral = JSONObject.quote(folderPassword.orEmpty())
        view.evaluateJavascript(
            buildLanzouFolderPageScript(passwordLiteral),
        ) { rawResult ->
            if (terminal.get()) return@evaluateJavascript
            when (val parsed = parseFolderResult(rawResult)) {
                is FolderPageResult.Entries -> {
                    when (val decision = folderSnapshotStabilizer?.observe(parsed.entries)) {
                        is FolderSnapshotDecision.Complete -> {
                            completeFolderEntries(decision.entries)
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
        if (!terminal.compareAndSet(false, true)) return
        try {
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
        } catch (exception: Exception) {
            // A callback belongs to the suspending adapter and must never be
            // able to strand this WebView. Give it one structured failure
            // opportunity, then always tear down the page.
            Log.w(TAG, "download_callback_failed", exception)
            runCatching {
                failureCallback?.invoke(
                    ArtifactFailure(
                        phase = ArtifactFailurePhase.SOURCE_RESOLUTION,
                        sourceKind = ArtifactSourceKind.LANZOU_SHARE,
                        reasonCode = "lanzou_callback_failed",
                        retryable = true,
                    ),
                )
            }
        } finally {
            destroyNow()
        }
    }

    private fun completeFolderEntries(entries: List<LanzouFolderEntry>) {
        if (!terminal.compareAndSet(false, true)) return
        try {
            folderEntriesCallback?.invoke(entries)
        } catch (exception: Exception) {
            Log.w(TAG, "folder_callback_failed", exception)
            runCatching {
                folderFailureCallback?.invoke(
                    ArtifactFailure(
                        phase = ArtifactFailurePhase.SOURCE_RESOLUTION,
                        sourceKind = ArtifactSourceKind.LANZOU_SHARE,
                        reasonCode = "lanzou_callback_failed",
                        retryable = true,
                    ),
                )
            }
        } finally {
            destroyNow()
        }
    }

    private fun reportFailure(reasonCode: String, retryable: Boolean) {
        if (!terminal.compareAndSet(false, true)) return
        Log.w(TAG, "webview_failure reason=$reasonCode retryable=$retryable")
        try {
            failureCallback?.invoke(
                ArtifactFailure(
                    phase = ArtifactFailurePhase.SOURCE_RESOLUTION,
                    sourceKind = ArtifactSourceKind.LANZOU_SHARE,
                    reasonCode = reasonCode,
                    retryable = retryable,
                ),
            )
        } catch (exception: Exception) {
            Log.w(TAG, "failure_callback_failed", exception)
        } finally {
            destroyNow()
        }
    }

    private fun reportFolderFailure(reasonCode: String, retryable: Boolean) {
        if (!terminal.compareAndSet(false, true)) return
        Log.w(TAG, "folder_failure reason=$reasonCode retryable=$retryable")
        try {
            folderFailureCallback?.invoke(
                ArtifactFailure(
                    phase = ArtifactFailurePhase.SOURCE_RESOLUTION,
                    sourceKind = ArtifactSourceKind.LANZOU_SHARE,
                    reasonCode = reasonCode,
                    retryable = retryable,
                ),
            )
        } catch (exception: Exception) {
            Log.w(TAG, "folder_failure_callback_failed", exception)
        } finally {
            destroyNow()
        }
    }

    private fun destroyNow() {
        terminal.set(true)
        downloadCallback = null
        failureCallback = null
        folderEntriesCallback = null
        folderFailureCallback = null
        folderPassword = null
        folderSnapshotStabilizer = null
        operation = Operation.NONE
        webView?.let { view ->
            view.removeOnAttachStateChangeListener(attachStateListener)
            view.stopLoading()
            view.setDownloadListener(null)
            view.webViewClient = WebViewClient()
            view.loadUrl("about:blank")
            (view.parent as? android.view.ViewGroup)?.removeView(view)
            view.destroy()
        }
        webView = null
        mountContainer = null
        @Suppress("DEPRECATION")
        CookieManager.getInstance().removeSessionCookie()
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
        const val HTML_RESPONSE_GRACE_MILLIS = 250L
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

    private sealed interface PageActionResult {
        data class Link(val url: String, val referer: String?) : PageActionResult
        data object Triggered : PageActionResult
        data object VerificationRequired : PageActionResult
        data object Wait : PageActionResult
    }
}

/**
 * Builds the folder-page probe separately so its DOM contract can be tested
 * without constructing an Android WebView in JVM tests.
 */
internal fun buildLanzouFolderPageScript(passwordLiteral: String): String =
    """
            (function(){
              var links=[].slice.call(document.querySelectorAll('#infos .mbx a.mlink, #infos #ready a')).map(function(a){
                var href=a.getAttribute('href') || '';
                var row=a.closest('#ready') || a.closest('.mbx') || a.parentElement;
                var nameNode=a.querySelector('.filename');
                if(nameNode){ nameNode=nameNode.cloneNode(true); }
                else { nameNode=a.cloneNode(true); }
                var metadataNodes=nameNode.querySelectorAll('.filesize,.mmr,.filedown,.filetime,.file-time,.file-date,.size,.sizeh,#size,#time');
                for(var metadataIndex=0;metadataIndex<metadataNodes.length;metadataIndex++){
                  metadataNodes[metadataIndex].remove();
                }
                var sizeNode=row ? row.querySelector('#size, .size, .sizeh, .filesize') : null;
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
            """.trimIndent()
