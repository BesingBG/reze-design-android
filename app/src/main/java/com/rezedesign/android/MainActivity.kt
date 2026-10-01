package com.rezedesign.android

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
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
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.window.OnBackInvokedDispatcher
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream

private const val TAG = "RezeDesign"

/**
 * 本地资源使用的虚拟域名，由 [WebViewAssetLoader] 映射到 `assets/`。
 *
 * 必须是 **https**：WebGPU 只在安全上下文里可用，官方明确把 `file://` 列为不安全；
 * 而 `https://<自定义域名>` 与 `http://localhost` 一样被当作可信来源。
 */
private const val ASSET_DOMAIN = "appassets.androidplatform.net"
private const val BASE_URL = "https://$ASSET_DOMAIN/"

/** 注入兼容层时用的来源规则（不带尾斜杠）。 */
private const val BASE_ORIGIN = "https://$ASSET_DOMAIN"

/** document-start 注入的兼容层脚本；随 APK 打包，不入 assets/web（那是构建产物）。 */
private const val SHIM_ASSET = "shim/shell.js"

/** 兼容层脚本调用的导出桥名字（与 shell.js 里的 window.RezeSave 对应）。 */
private const val SAVE_BRIDGE = "RezeSave"

/** 「关于手机版」弹窗里的两个外链，一律交给系统浏览器打开。 */
private const val SITE_URL = "https://reze.design"
private const val DESKTOP_URL = "https://github.com/BesingBG/reze-design-desktop/releases"

/** 弹窗偏好：勾了「不再提示」即写入。 */
private const val PREFS_NAME = "shell"
private const val PREF_NOTICE_DISMISSED = "notice.dismissed"

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

        applyEdgeToEdge()

        // 直接给 WebView 设 padding 是没用的：它把整块 view 的尺寸当成网页视口，
        // padding 既不缩小 100vh、也不挪动绘制原点（实测给 WebView 加上 96px 的
        // paddingTop 后，页面 innerHeight 仍等于整屏高度，顶部工具条照样被状态栏盖住）。
        // 所以要缩小的是 WebView 的**布局尺寸** —— 交给一层容器来承担 padding。
        val container = FrameLayout(this)
        // 系统栏那一条露出来的就是容器的底色（WebView 默认是白的）
        container.setBackgroundColor(Color.BLACK)

        webView = WebView(this)
        webView.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        )
        container.addView(webView)
        configureWebView(webView)

        // 必须在 WebView 创建之后读取，否则 WebView 提供者可能尚未加载。
        webViewPkg = webViewPackageInfo()

        // 状态栏 / 手势条 / 挖孔的尺寸变成容器的 padding：WebView 因此被摆在系统栏
        // 之间，网页视口也就正好等于可见区域 —— 顶部工具条不再和状态栏叠住，
        // 底部走带也不会被手势条压住。
        ViewCompat.setOnApplyWindowInsetsListener(container) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            // 打一行日志：这条链路一旦失效，症状是"顶部被状态栏压住"，
            // 而那在远程测试者的截图里很难和别的问题区分开。有这行就能一眼判定。
            Log.i(
                TAG,
                "系统栏 insets: top=${bars.top} bottom=${bars.bottom}" +
                    " left=${bars.left} right=${bars.right}",
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        setContentView(container)

        // 放在 loadUrl 之前：用户读弹窗的时候，页面已经在后面加载，关掉即可直接用。
        showNoticeIfNeeded()

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

    /**
     * 让内容进到系统栏里面，再把系统栏的高度还成内容自己的 padding。
     *
     * Android 15（API 35）起，**targetSdk ≥ 35 的应用被强制 edge-to-edge**：窗口不再
     * 自动让出状态栏和手势条，`fitsSystemWindows` 与 `setDecorFitsSystemWindows(true)`
     * 都会被系统忽略。这不是哪一行代码写错了，是平台行为变了 —— 曾出现过
     * 页面顶部工具条和系统状态栏、厂商搜索胶囊叠在一起，场景名被截断。
     *
     * 所以修法不是「让系统把内容推开」，而是**自己消费 insets**（见 [onCreate] 里挂在
     * 容器上的监听器）。这里显式声明 `false` 是为了让 Android 12–14 也走同一条路：
     * 那些版本上默认仍由 DecorView 自己 inset，若不声明，容器再补一层 padding
     * 就会把内容多推一次。
     */
    private fun applyEdgeToEdge() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
    }

    /**
     * 「关于手机版」说明弹窗。
     *
     * 为什么要有：这个壳砍掉了上游依赖服务端的一整块东西（账号、发布、画廊），
     * 又是**桌面布局塞进窄屏**，第一次打开的人很容易直接得出"这软件怎么这么残"。
     * 与其让人去猜，不如开门见山把局限写清楚，并把出口给出去。
     *
     * 为什么放在**原生壳**而不是注入网页：设备 WebGPU 不可用时页面可能是白屏，
     * 网页里的弹窗根本出不来；原生弹窗在任何情况下都看得见。
     *
     * 每个冷启动都弹一次，勾了「不再提示」就不再弹。判断与落盘都走
     * [PREF_NOTICE_DISMISSED]，键名带 `notice.` 前缀，避免和上游存在
     * localStorage 里的东西混淆（两者存储位置本来也不同）。
     */
    private fun showNoticeIfNeeded() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        if (prefs.getBoolean(PREF_NOTICE_DISMISSED, false)) return

        val gap = (16 * resources.displayMetrics.density).toInt()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(gap, gap / 2, gap, 0)
        }
        content.addView(
            TextView(this).apply {
                text = getString(R.string.notice_body)
                setLineSpacing(0f, 1.15f)
            },
        )
        val dontShow = CheckBox(this).apply { text = getString(R.string.notice_dont_show) }
        content.addView(dontShow)

        // 两个外链做成对话框内的整行按钮：点它们**不关**对话框，
        // 用户可以把两个都点完再关闭。
        content.addView(
            linkButton(R.string.notice_site, SITE_URL),
        )
        content.addView(
            linkButton(R.string.notice_desktop, DESKTOP_URL),
        )

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.notice_title)
            .setView(content)
            .setPositiveButton(R.string.notice_ok, null)
            .create()

        // 落盘只放在 dismiss 这一个出口：按钮、返回键、点外部关掉，三条路都算数，
        // 不会出现「勾了但没生效」。
        dialog.setOnDismissListener {
            if (dontShow.isChecked) {
                prefs.edit().putBoolean(PREF_NOTICE_DISMISSED, true).apply()
            }
        }
        dialog.show()
    }

    /** 外链按钮：整行、非全大写（否则 "PC" 这种拉丁字母会被喊出来）。 */
    private fun linkButton(labelRes: Int, url: String): Button =
        Button(this).apply {
            setText(labelRes)
            isAllCaps = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            setOnClickListener { openExternal(url) }
        }

    /**
     * 用系统浏览器打开外链。
     *
     * 刻意不在壳内预览：这个 WebView 没有地址栏，跳进去就退不回来了；
     * 而且目标站点是别人的（reze.design / GitHub），在壳里加载等于把壳变成浏览器。
     */
    private fun openExternal(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "没有应用能打开 $url", e)
            Toast.makeText(this, R.string.notice_no_browser, Toast.LENGTH_SHORT).show()
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
        // 导出落盘桥：shell.js 拦截到 <a download> 点击后，把 blob 分片喂进来。
        // 必须在 loadUrl 之前注册，否则 document-start 注入的脚本看不到它。
        view.addJavascriptInterface(SaveBridge(), SAVE_BRIDGE)

        injectShim(view)

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
     * 注入兼容层。
     *
     * 必须在页面任何脚本之前执行：上游在模块初始化时就会读 `navigator.gpu`、
     * 建 dock、注册导出路径，晚一步就可能错过。
     * [WebViewFeature.DOCUMENT_START_SCRIPT] 是唯一能保证这一点的公开机制；
     * 不支持时**不做降级**——晚注入的 anchor 补丁会漏掉首批导出，与其给出
     * 一个时灵时不灵的桥，不如在日志里说清楚。
     */
    private fun injectShim(view: WebView) {
        val script = try {
            assets.open(SHIM_ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (e: IOException) {
            Log.w(TAG, "兼容层脚本缺失（$SHIM_ASSET），导出桥不可用", e)
            return
        }

        if (!featureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            Log.w(TAG, "当前 WebView 不支持 document-start 注入，导出桥不可用")
            return
        }

        // 只对本地资源域生效：页面里的其它来源（演示资源在 CDN）不该被改到。
        WebViewCompat.addDocumentStartJavaScript(view, script, setOf(BASE_ORIGIN))
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

    /**
     * 导出落盘桥（M4a）。
     *
     * WebView 里 `<a download>` 是**静默失败**的：点击没有任何反应，也不报错。
     * 兼容层（`assets/shim/shell.js`）把这类点击接管下来，读出卖出的 blob，
     * 按 512KB 分片 base64 调 [write]，由这里写进系统「下载」目录。
     *
     * 落盘位置取 `MediaStore.Downloads` 根目录，**不建子目录**：套壳要的是
     * 「用户能顺手找到文件」，而 `Downloads` 是安卓统一收拢下载物的地方。
     * 走 MediaStore 也意味着 **Android 10+ 不需要任何存储权限**。
     * API 26–28 没有 Downloads 集合，退回应用私有外部目录（不需要权限）。
     *
     * 分片是「边收边写」：首片创建条目并 openOutputStream，逐片 append，
     * 末片关闭并把 `IS_PENDING` 置 0 —— 过程中文件对其它应用不可见，
     * 不会出现「传输中就被相册/文件管理器读到半截文件」。
     */
    private inner class SaveBridge {

        private var out: OutputStream? = null
        private var pendingUri: Uri? = null
        private var legacyFile: File? = null
        private var displayName: String? = null
        private var written = 0L

        /**
         * 兼容层每片调用一次。注意本方法运行在 WebView 的 JavaBridge 线程上
         * （不是 UI 线程），因此文件 IO 可以直接做；UI 操作走 [toastOnUi]。
         *
         * 分片由同一段 JS 串行发出，[Synchronized] 只是把这条前提固定下来，
         * 顺带让「上一份还没收尾」的异常情况有个确定的处理顺序。
         */
        @JavascriptInterface
        @Synchronized
        fun write(name: String, mime: String, idx: Int, total: Int, base64: String, last: Boolean) {
            try {
                if (idx == 0) begin(name, mime)
                val stream = out ?: return
                val bytes = Base64.decode(base64, Base64.DEFAULT)
                stream.write(bytes)
                written += bytes.size
                if (last) finish()
            } catch (t: Throwable) {
                Log.e(TAG, "写入导出文件失败（$name 第 ${idx + 1}/$total 片）", t)
                abort()
                toastOnUi("导出失败：${t.message ?: t.javaClass.simpleName}")
            }
        }

        /** 首片：建条目、开流。上一份没正常收尾的话先丢弃，避免两份数据串在一起。 */
        private fun begin(name: String, mime: String) {
            abort()
            val safeName = sanitizeName(name)
            val safeMime = mime.ifBlank { "application/octet-stream" }
            displayName = safeName
            written = 0

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, safeName)
                    put(MediaStore.Downloads.MIME_TYPE, safeMime)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw IOException("MediaStore 未返回可用条目")
                pendingUri = uri
                out = contentResolver.openOutputStream(uri) ?: throw IOException("无法打开输出流")
            } else {
                val dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                    ?: File(filesDir, "downloads")
                if (!dir.exists()) dir.mkdirs()
                val file = File(dir, safeName)
                legacyFile = file
                out = FileOutputStream(file)
            }
        }

        /** 末片：收尾并提示。 */
        private fun finish() {
            val stream = out ?: return
            out = null
            stream.flush()
            stream.close()

            val name = displayName
            val size = written
            val uri = pendingUri
            pendingUri = null
            flushPending(uri)

            if (uri != null) {
                toastOnUi("已保存到「下载」：$name（${humanSize(size)}）")
            } else {
                toastOnUi("已保存：${legacyFile?.name ?: name}（${humanSize(size)}）")
            }
            Log.i(TAG, "导出完成 $name，$size 字节")
            legacyFile = null
        }

        /** 中途失败：关流、删掉半截条目，别在用户的下载目录里留垃圾。 */
        private fun abort() {
            // 不能因为 out 为空就整体跳过：begin() 可能已经建好条目、
            // 但在开流那一步失败，残留的 pendingUri 同样要清掉。
            val stream = out
            out = null
            if (stream != null) {
                try {
                    stream.close()
                } catch (e: IOException) {
                    Log.w(TAG, "关闭未完成的输出流失败", e)
                }
            }
            val uri = pendingUri
            pendingUri = null
            uri?.let { runCatching { contentResolver.delete(it, null, null) } }
            legacyFile?.let { runCatching { it.delete() } }
            legacyFile = null
        }

        private fun flushPending(uri: Uri?) {
            if (uri == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
            val values = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
            contentResolver.update(uri, values, null, null)
        }

        /** 文件名要过 MediaStore：去掉分隔符与控制字符，兜底并限长。 */
        private fun sanitizeName(name: String): String {
            val cleaned = name.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").trim()
            val fallback = cleaned.ifEmpty { "download" }
            return if (fallback.length > 120) fallback.take(120) else fallback
        }

        private fun humanSize(bytes: Long): String = when {
            bytes >= 1024 * 1024 -> String.format("%.1f MB", bytes / 1024.0 / 1024.0)
            bytes >= 1024 -> String.format("%.1f KB", bytes / 1024.0)
            else -> "$bytes B"
        }

        private fun toastOnUi(message: String) {
            runOnUiThread { Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show() }
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