import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// ===========================================================================
// 版本号（M6）
//
// 唯一版本源是**上游 submodule 的 package.json**，与桌面版同一套做法：
// versionName 原样取它（当前 1.0.1），保持"干净"，方便和上游发布对齐。
//
// versionCode 由它派生 + 壳自己的修订号，必须**严格递增**（不递增就装不上新版）：
//     versionCode = 主*1_000_000 + 次*10_000 + 修订*100 + 壳修订号
// 例：上游 1.0.1 的壳第 1 次发版 → 1000101；上游升到 1.1.0 → 1010000。
// ⇒ 每轮壳改动发版，只需把 gradle.properties 里的 shellRevision 加一。
//
// 壳修订号**不进 versionName**（要的是干净名字），它体现在 versionCode，
// 以及「关于手机版」弹窗的构建信息里 —— 见 MainActivity.showNoticeIfNeeded()。
// ===========================================================================

val repoRoot = rootProject.layout.projectDirectory.asFile
val upstreamDir = File(repoRoot, "reze-design")

/** 上游版本取自 submodule 的 package.json；submodule 没就位就 fail fast。 */
fun readUpstreamVersion(): String {
    val pkg = File(upstreamDir, "package.json")
    if (!pkg.isFile) {
        throw GradleException(
            "找不到 ${pkg.path}：上游 submodule 未就位。\n" +
                "请先执行：git submodule update --init --recursive",
        )
    }
    val found = Regex("\"version\"\\s*:\\s*\"([^\"]+)\"").find(pkg.readText())
        ?: throw GradleException("${pkg.path} 里没有 version 字段，上游可能改了格式")
    return found.groupValues[1].removePrefix("v")
}

val appVersionName = readUpstreamVersion()
val upstreamParts = appVersionName.split(".").map { it.toIntOrNull() ?: 0 }
val upstreamMajor = upstreamParts.getOrElse(0) { 0 }
val upstreamMinor = upstreamParts.getOrElse(1) { 0 }
val upstreamPatch = upstreamParts.getOrElse(2) { 0 }

// 派生公式给次/修订各留两位十进制（0–99）。上游真跳到三位数时这里会拦下来，
// 免得 versionCode 悄悄算错、发出去才发现装不上。
if (upstreamMinor > 99 || upstreamPatch > 99) {
    throw GradleException("上游版本 $appVersionName 的次/修订号超过 99，versionCode 派生公式需同步调整")
}

val shellRevision = (providers.gradleProperty("shellRevision").orNull ?: "0").toInt()
val appVersionCode =
    upstreamMajor * 1_000_000 + upstreamMinor * 10_000 + upstreamPatch * 100 + shellRevision

/** 上游 commit：让拿到包的人能核对"这个包基于哪次提交"。 */
val upstreamCommit: String = if (File(upstreamDir, ".git").exists()) {
    providers.exec {
        commandLine("git", "-C", upstreamDir.absolutePath, "rev-parse", "--short", "HEAD")
    }.standardOutput.asText.get().trim()
} else {
    "unknown"
}

/** 构建时刻。versionName 是干净的，构建时间就是区分两个包最直接的信息。 */
val buildDate = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())

/**
 * 发布签名口令放仓库根的 keystore.properties（本机文件，已在 .gitignore）。
 *
 * 读不到时**只警告、不中断**：在别的机器上 clone 下来应该照样能 assembleDebug，
 * 不该被一把只存在于作者机器上的密钥卡住。代价是 assembleRelease 产出未签名包，
 * 脚本 scripts/build-release.mjs 会在这里直接报错拦下。
 */
val keystoreProperties = Properties().apply {
    val f = File(repoRoot, "keystore.properties")
    if (f.isFile) f.inputStream().use { load(it) }
}
if (keystoreProperties.isEmpty()) {
    logger.warn(
        "⚠️ 未找到 keystore.properties：assembleRelease 会产出未签名 APK" +
            "（assembleDebug 不受影响）。需要发布时请先按开发计划 §M6 生成密钥。",
    )
}

android {
    namespace = "com.rezedesign.android"

    // 只使用本机已安装的 SDK 组件（android-35 / build-tools 35.0.0），
    // 避免 AGP 自动向共享 SDK 目录下载或修改组件而影响其它工程。
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.rezedesign.android"

        // minSdk 26：本方案不打包任何 native 库，真正的运行门槛是
        // 「系统 WebView >= 121 且设备有 Vulkan 1.1」，由运行时门禁负责判定。
        minSdk = 26
        targetSdk = 35

        versionCode = appVersionCode
        versionName = appVersionName

        // 构建信息进 BuildConfig，由「关于手机版」弹窗展示（M6）。
        // 版本号是干净的 1.0.1，拿到包的人靠这三项核对"我装的是哪一版"。
        buildConfigField("String", "UPSTREAM_VERSION", "\"$appVersionName\"")
        buildConfigField("String", "UPSTREAM_COMMIT", "\"$upstreamCommit\"")
        buildConfigField("String", "BUILD_DATE", "\"$buildDate\"")
    }

    signingConfigs {
        if (keystoreProperties.isNotEmpty()) {
            create("release") {
                storeFile = File(repoRoot, keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            // 没有 keystore.properties 时这里是 null → 产出 app-release-unsigned.apk
            signingConfig = signingConfigs.findByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures {
        // AGP 8 起默认关闭，弹窗要读版本信息必须打开
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    androidResources {
        // 上游产物包含 .vmd / .pmx 等自定义扩展名，以及字体与 wasm，
        // 关闭压缩以便 WebView 直接从 assets 读取。
        noCompress += listOf("wasm", "vmd", "pmx", "pmd", "vpd")

        // AAPT2 默认的 ignoreAssetsPattern 里有一条 `<dir>_*`，会忽略所有
        // 下划线开头的目录。Next 静态导出恰恰把全部 JS/CSS/字体放在 `_next/`
        // （另有 `_not-found/`），于是产物被打包时被静默剔除 —— APK 里
        // 只剩 html 和 txt，页面必然白屏，且构建期毫无警告。
        //
        // 这里覆盖为 AGP 默认值去掉 `<dir>_*`（保留 `.*`，继续忽略点开头文件）。
        ignoreAssetsPattern = "!.svn:!.git:!.ds_store:!*.scc:.*:!CVS:!thumbs.db:!picasa.ini:!*~"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.core)
}

/**
 * 供 scripts/build-release.mjs 取版本号给 APK 命名用。
 *
 * 版本号只在本脚本里算一次，发布脚本读结果而不是自己重算一遍 ——
 * 免得两处公式漂移，出现"包名写的版本和 APK 里的版本不一致"。
 *
 * 壳修订号也一并输出：发布 tag 的 `-N` 后缀就是它，从与 Gradle 相同的来源出去，
 * CI 就不必再去 grep gradle.properties、也不会跟这里的读取方式走岔。
 */
tasks.register("printBuildInfo") {
    group = "build"
    description = "打印 versionName / versionCode / shellRevision（供发布脚本与 CI 命名产物）"
    doLast { println("$appVersionName $appVersionCode $shellRevision") }
}