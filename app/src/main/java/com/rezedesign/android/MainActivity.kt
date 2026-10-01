package com.rezedesign.android

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.ValueCallback
import android.window.OnBackInvokedDispatcher
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.IOException

private const val TAG = "RezeDesign"

/**
 * 本地资源使用的虚拟域名，由 [WebViewAssetLoader] 映射到 `assets/`。
 *
 * 必须是 **https**：WebGPU 只在安全上下文里可用，官方明确把 `file://` 列为不安全；
 * 而 `https://<自定义域名>` 与 `http://localhost` 一样被当作可信来源。
 */
private const val ASSET_DOMAIN = "appassets.androidplatform.net"
private const val BASE_URL = "https://$ASSET_DOMAIN/"

/** M0 探针页：验证当前设备的 WebView 是否真的能跑 WebGPU。 */
private const val PROBE_URL = "${BASE_URL}probe/index.html"

/** M1 之后由 build-web.mjs 写入的编辑器产物。 */
private const val APP_URL = BASE_URL

/** WebGPU 从 Android WebView 121 起被支持（MDN 兼容表）。 */
private const val MIN_WEBVIEW_MAJOR = 121

/** 页面发起的文件选择器（`<input type=file>`）走 startActivityForResult 的请求码。 */
private const val REQUEST_FILE_CHOOSER = 1001

class MainActivity : Activity() {

    private lateinit var webView: WebView
    private var webViewPkg: PackageInfo? = null

    /**
     * 文件选择器的回调。
     *
     * WebView 不自己去起 Intent，它把「选择结束后往哪儿回填」这件事交给宿主：
     * 系统 picker 是异步的（还跨进程），人选完之前这个 callback 必须留着，
     * 否则页面里的 `<input type=file>` 永远等不到结果。
     */
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null

    // 路径前缀 `probe/` 映射到 `assets/probe/`，其余根路径映射到 `assets/web/`（最长前缀优先匹配）。
    private val assetLoader: WebViewAssetLoader by lazy {
        WebViewAssetLoader.Builder()
            .setDomain(ASSET_DOMAIN)
            .addPathHandler("/probe/", AssetPrefixPathHandler(this, "probe"))
            .addPathHandler("/", AssetPrefixPathHandler(this, "web"))
            .build()
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            // 供 chrome://inspect 接管 WebView 控制台
            WebView.setWebContentsDebuggingEnabled(true)
        }

        webView = WebView(this)
        webView.layoutParams = android.view.ViewGroup.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
        )
        webView.fitsSystemWindows = true
        configureWebView(webView)

        // 必须在 WebView 创建之后读取，否则 WebView 提供者可能尚未加载。
        webViewPkg = webViewPackageInfo()

        setContentView(webView)

        // 转屏已经被 Manifest 的 configChanges 挡在重建之外，能走到这里的只有
        // 进程被系统回收后重建（或其它未声明的配置变更）。
        val restored = savedInstanceState?.let { webView.restoreState(it) }
        if (restored == null) {
            val target = if (hasBuiltSite()) APP_URL else PROBE_URL
            Log.i(TAG, "加载 $target（WebView ${webViewPkg?.versionName ?: "未知"}）")
            webView.loadUrl(target)
        } else {
            Log.i(TAG, "已从实例状态恢复 WebView（历史 ${restored.size} 项）")
        }

        // Android 13 起，「预测式返回」经 OnBackInvokedDispatcher 分发；
        // 未启用预测式返回的系统仍回调 onBackPressed()。两条路都进 handleBack()。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT,
            ) { handleBack() }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    /**
     * 文件选择的结果回填。
     *
     * 回来的是 `content://` URI。壳**不需要**先把文件拷进 cacheDir：WebView 会用系统
     * 授予它的临时读权限自己打开这些 URI，页面的 `File` 对象因此能正常 `arrayBuffer()`。
     *
     * 用户取消时 [WebChromeClient.FileChooserParams.parseResult] 返回 null，照传即可
     * —— 那个 input 会收到"没选"，而不是一直等。
     */
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != REQUEST_FILE_CHOOSER) {
            super.onActivityResult(requestCode, resultCode, data)
            return
        }
        val callback = fileChooserCallback ?: return
        fileChooserCallback = null
        callback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data))
    }

    /**
     * 返回键：网页历史优先，到底才退出。
     *
     * 页面自己接管返回（关闭面板、对话框）走的是历史栈，壳不该把这一层吃掉。
     *
     * `Activity.onBackPressed` 自 API 33 起废弃，但这里是纯 [Activity]（非
     * AppCompatActivity），旧系统只有这一条回调，故保留；API 33+ 由
     * [OnBackInvokedDispatcher] 接管，两者互斥，不会重复触发。
     */
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        handleBack()
    }

    private fun handleBack() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            finish()
        }
    }

    private fun configureWebView(view: WebView) {
        with(view.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true

            // 本地资源不需要 file:// 访问
            allowFileAccess = false
            allowContentAccess = false

            // 上游是响应式布局，尊重 viewport meta
            useWideViewPort = true
            loadWithOverviewMode = true

            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false

            // 音频/动作播放不应被手势限制拦住
            mediaPlaybackRequiresUserGesture = false

            cacheMode = WebSettings.LOAD_DEFAULT
        }

        view.addJavascriptInterface(ShellBridge(), "AndroidShell")

        view.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest,
            ): WebResourceResponse? = intercept(request.url)
        }

        view.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                Log.i(TAG, "[console:${msg.messageLevel()}] ${msg.message()} @${msg.sourceId()}:${msg.lineNumber()}")
                return true
            }

            /**
             * 页面里每一个 `<input type=file>` 的入口。
             *
             * 上游的全部导入（模型、动作、音频、背景图、场景包）都只走这一个 HTML 机制，
             * 拿到 `File` 后只用标准 File API 读字节（`arrayBuffer` / `text` /
             * `createObjectURL` / `createImageBitmap`）—— 所以这里接上，整条导入链路就通了。
             *
             * 注意上游模型导入用的是 `webkitdirectory`，但**壳这一侧表达不了目录选择**：
             * WebView 的公开 API [FileChooserParams] 没有「目录」mode，`createIntent()`
             * 恒为 `ACTION_GET_CONTENT`，与 WebView 版本无关（WebView 149 实测仍是文件模式）。
             * ⇒ 模型导入只能走 zip（见文档 M3、§6.4）。
             */
            @Suppress("DEPRECATION")
            override fun onShowFileChooser(
                view: WebView,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams,
            ): Boolean {
                // 连续点击时上一次的回调还挂着：先用 null 兑现它。否则页面里那个 input
                // 卡在一个永远不会 resolve 的选择上，之后的点击全部静默失效。
                fileChooserCallback?.onReceiveValue(null)
                fileChooserCallback = null

                fileChooserCallback = filePathCallback
                return try {
                    val intent = fileChooserParams.createIntent()
                    // 诊断用：WebView 的公开 API 里没有「选目录」这个 mode，
                    // 但界面上游模型导入用的就是 webkitdirectory，所以这里把
                    // Chromium 实际给的参数记下来，便于判断目录选择是否可达。
                    Log.i(
                        TAG,
                        "文件选择请求: mode=${fileChooserParams.mode}" +
                            " accept=${fileChooserParams.acceptTypes.joinToString("|")}" +
                            " capture=${fileChooserParams.isCaptureEnabled}" +
                            " intent=${intent.action}/${intent.type} extras=${intent.extras}",
                    )
                    startActivityForResult(intent, REQUEST_FILE_CHOOSER)
                    true
                } catch (e: ActivityNotFoundException) {
                    Log.w(TAG, "没有应用能处理文件选择请求", e)
                    fileChooserCallback = null
                    filePathCallback.onReceiveValue(null)
                    false
                }
            }
        }
    }

    /**
     * 请求分流。
     *
     * 1. 本域 `/api/` 开头的请求 → 空后端响应（见 [apiStub]）
     * 2. 本域的 analytics 脚本 → 空脚本
     * 3. 本域其余路径 → 本地 assets 产物
     * 4. 其它域 → 返回 null 交给网络栈（上游演示资源仍在 R2 CDN）
     */
    private fun intercept(url: Uri): WebResourceResponse? {
        val host = url.host ?: return null

        if (host == ASSET_DOMAIN) {
            val path = url.path.orEmpty()
            // 构建期已把 app/api/** 整套移出，这里补一个「没有后端」的空态，
            // 让画廊、账号面板表现为空列表而不是请求失败。
            if (path.startsWith("/api/")) return apiStub(path, url)
            // @vercel/analytics 取的是**同源**的 /_vercel/insights/script.js
            // （产物里没有这个文件）。不拦的话它会先查 assets、未命中，再落到
            // 解析不了的本域域名上 —— 白等一次 DNS，还往控制台写一条加载失败。
            if (path.startsWith("/_vercel/")) return emptyScript()
            return assetLoader.shouldInterceptRequest(url)
        }

        // 同一份代码在 debug 模式下会改指 CDN，一并吞掉
        if (host.endsWith("vercel-scripts.com")) return emptyScript()

        return null
    }

    private fun emptyScript(): WebResourceResponse =
        WebResourceResponse("application/javascript", "utf-8", ByteArrayInputStream(ByteArray(0)))

    /**
     * 各端点的空态响应体。
     *
     * 形状不是我们定的：`app/api/library/route.ts` 在上游就写了一份「没有数据库时」
     * 的降级分支（`{items:[]}` / `{tags:[]}` / `{all:0,yours:0,liked:0}` /
     * `{scenes:[],nextCursor:null}`），这里照抄，保证调用方的解析路径与线上一致。
     *
     * `/api/auth/` 下的请求单独处理：better-auth 读的是 session 对象或 null，返回 `{}` 语义不明；
     * 未登录时它的正常响应体正是 `null`。
     */
    private fun apiStub(path: String, url: Uri): WebResourceResponse {
        val body = when {
            path.startsWith("/api/auth/") -> "null"
            path == "/api/oauth-providers" -> """{"providers":[]}"""
            path == "/api/library/resolve" -> """{"payloads":{}}"""
            path == "/api/library" -> when {
                url.getQueryParameter("kind") != "scene" -> """{"items":[]}"""
                url.getQueryParameter("counts") == "tags" -> """{"tags":[]}"""
                url.getQueryParameter("counts") == "facets" -> """{"all":0,"yours":0,"liked":0}"""
                else -> """{"scenes":[],"nextCursor":null}"""
            }
            // /api/me 走的是 `d.stats ?? null`，空对象即「没有作品数据」
            else -> "{}"
        }
        return jsonResponse(body)
    }

    private fun jsonResponse(body: String): WebResourceResponse = WebResourceResponse(
        "application/json",
        "utf-8",
        200,
        "OK",
        emptyMap(),
        ByteArrayInputStream(body.toByteArray(Charsets.UTF_8)),
    )

    /** `assets/web/index.html` 存在时说明上游静态产物已经就位。 */
    private fun hasBuiltSite(): Boolean = try {
        assets.open("web/index.html").close()
        true
    } catch (e: IOException) {
        false
    }

    private fun webViewPackageInfo(): PackageInfo? = try {
        WebViewCompat.getCurrentWebViewPackage(this)
    } catch (e: Throwable) {
        Log.w(TAG, "读取 WebView 版本失败", e)
        null
    }

    private fun webViewMajorVersion(): Int? =
        webViewPkg?.versionName?.substringBefore('.')?.toIntOrNull()

    private fun featureSupported(feature: String): Boolean = try {
        WebViewFeature.isFeatureSupported(feature)
    } catch (e: Throwable) {
        false
    }

    /**
     * 暴露给页面的最小诊断接口。
     *
     * 注意：`addJavascriptInterface` 对所有 frame 可见，仅因为我们只加载自己的本地资源才可接受。
     */
    private inner class ShellBridge {

        @JavascriptInterface
        fun environment(): String {
            val pkg = webViewPkg
            val major = webViewMajorVersion()
            return JSONObject().apply {
                put("webViewPackage", pkg?.packageName ?: JSONObject.NULL)
                put("webViewVersion", pkg?.versionName ?: JSONObject.NULL)
                put("webViewMajor", major ?: JSONObject.NULL)
                put("minWebViewMajor", MIN_WEBVIEW_MAJOR)
                put("webViewTooOld", major != null && major < MIN_WEBVIEW_MAJOR)
                put("androidSdk", Build.VERSION.SDK_INT)
                put("androidRelease", Build.VERSION.RELEASE)
                put("manufacturer", Build.MANUFACTURER)
                put("model", Build.MODEL)
                // M2 的兼容层注入依赖该特性
                put("documentStartScript", featureSupported(WebViewFeature.DOCUMENT_START_SCRIPT))
                put("assetDomain", ASSET_DOMAIN)
            }.toString()
        }

        @JavascriptInterface
        fun log(level: String, message: String) {
            val priority = when (level.lowercase()) {
                "error" -> Log.ERROR
                "warn" -> Log.WARN
                "debug" -> Log.DEBUG
                else -> Log.INFO
            }
            Log.println(priority, TAG, "[page] $message")
        }
    }
}

/**
 * 把虚拟域名的请求映射到 `assets/<assetRoot>/<path>`。
 *
 * androidx.webkit 自带的 [WebViewAssetLoader.AssetsPathHandler] 只能挂到 `assets/` 根，
 * 而 M1 的产物需要挂在根路径（Next 使用绝对路径 `/_next/static/...`），
 * 所以这里自己实现一个带前缀的处理器。
 */
private class AssetPrefixPathHandler(
    private val context: Context,
    private val assetRoot: String,
) : WebViewAssetLoader.PathHandler {

    override fun handle(path: String): WebResourceResponse? {
        // 注意：WebViewAssetLoader 传入的是「去掉注册前缀后的后缀路径」，
        // 例如注册 "/probe/" 时收到的是 "index.html"（无前导斜杠）。
        val suffix = path.trimStart('/').substringBefore('?').substringBefore('#')
        // 目录请求（"/"、"foo/"）按静态站点惯例回退到 index.html
        val relative = if (suffix.isEmpty() || suffix.endsWith("/")) suffix + "index.html" else suffix

        val assetPath = "$assetRoot/$relative"
        return try {
            val mime = mimeOf(assetPath)
            WebResourceResponse(mime, encodingOf(mime), context.assets.open(assetPath))
        } catch (e: IOException) {
            Log.w(TAG, "本地资源缺失: $assetPath")
            null
        }
    }

    private fun mimeOf(name: String): String =
        when (name.substringAfterLast('.', "").lowercase()) {
            "html", "htm" -> "text/html"
            "js", "mjs" -> "text/javascript"
            "css" -> "text/css"
            "json", "map" -> "application/json"
            // 必须是 application/wasm，否则 WebAssembly.instantiateStreaming 会失败
            "wasm" -> "application/wasm"
            "svg" -> "image/svg+xml"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "ico" -> "image/x-icon"
            "woff" -> "font/woff"
            "woff2" -> "font/woff2"
            "ttf" -> "font/ttf"
            "otf" -> "font/otf"
            "mp3" -> "audio/mpeg"
            "ogg" -> "audio/ogg"
            "wav" -> "audio/wav"
            "mp4" -> "video/mp4"
            "webm" -> "video/webm"
            "txt" -> "text/plain"
            else -> "application/octet-stream"
        }

    private fun encodingOf(mime: String): String? =
        if (mime.startsWith("text/") || mime == "application/json" || mime == "image/svg+xml") {
            "utf-8"
        } else {
            null
        }
}