package com.rezedesign.android

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
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
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
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

class MainActivity : Activity() {

    private lateinit var webView: WebView
    private var webViewPkg: PackageInfo? = null

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

        val target = if (hasBuiltSite()) APP_URL else PROBE_URL
        Log.i(TAG, "加载 $target（WebView ${webViewPkg?.versionName ?: "未知"}）")
        webView.loadUrl(target)
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
            ): WebResourceResponse? {
                // 同源请求由本地 assets 提供；非本域返回 null，交给网络栈
                // （上游演示资源在 R2 CDN，M2 起还会在这里补 /api/* 的空态响应）
                return assetLoader.shouldInterceptRequest(request.url)
            }
        }

        view.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                Log.i(TAG, "[console:${msg.messageLevel()}] ${msg.message()} @${msg.sourceId()}:${msg.lineNumber()}")
                return true
            }
        }
    }

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