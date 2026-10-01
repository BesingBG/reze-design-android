#!/usr/bin/env node
/**
 * reze-design-android · 一条命令出可分发 APK（M6）
 *
 *   node scripts/build-release.mjs              完整流程（重建前端产物 → 打签名包）
 *   node scripts/build-release.mjs --skip-web   复用现有 assets/web，只重打壳（改 shim / Kotlin 时用）
 *
 * 做的事情：
 *   1) scripts/build-web.mjs            生成上游静态产物到 app/src/main/assets/web/
 *   2) ./gradlew :app:assembleRelease   打签名 APK
 *   3) 拷到 dist/RezeDesign-Android-<versionName>-<versionCode>.apk
 *
 * 版本号**不在这里计算**：唯一来源是 app/build.gradle.kts（读上游 package.json），
 * 这里只是把它读回来用于命名，避免两处各算一遍、公式漂移。
 *
 * 产出目录用 dist/ 而不是 build/ —— ./gradlew clean 会把 build/ 整个删掉。
 */

import { execFileSync, spawn } from "node:child_process"
import { createHash } from "node:crypto"
import { copyFileSync, existsSync, mkdirSync, readFileSync, statSync } from "node:fs"
import { dirname, join } from "node:path"
import { fileURLToPath } from "node:url"

const __dirname = dirname(fileURLToPath(import.meta.url))
const root = join(__dirname, "..")                                    // reze-design-android/
const upstream = join(root, "reze-design")                            // submodule（只读）
const assetsIndex = join(root, "app", "src", "main", "assets", "web", "index.html")
const apkDir = join(root, "app", "build", "outputs", "apk", "release")
const distDir = join(root, "dist")

const gradlew = join(root, "gradlew")

function run(cmd, args, opts = {}) {
  return new Promise((resolve, reject) => {
    const child = spawn(cmd, args, {
      stdio: "inherit",
      shell: process.platform === "win32",
      ...opts,
    })
    child.on("error", reject)
    child.on("exit", (code) =>
      code === 0 ? resolve() : reject(new Error(`${cmd} ${args.join(" ")} → 退出码 ${code}`)),
    )
  })
}

/** 版本号的唯一权威来源，见 app/build.gradle.kts 顶部的派生说明。 */
function readBuildInfo() {
  const out = execFileSync(gradlew, ["-q", ":app:printBuildInfo"], {
    cwd: root,
    encoding: "utf8",
  }).trim()
  const [versionName, versionCode] = out.split(/\s+/)
  if (!versionName || !versionCode) {
    throw new Error(`:app:printBuildInfo 输出无法解析：${JSON.stringify(out)}`)
  }
  return { versionName, versionCode }
}

function upstreamCommit() {
  try {
    return execFileSync("git", ["-C", upstream, "rev-parse", "--short", "HEAD"], {
      encoding: "utf8",
    }).trim()
  } catch {
    return "未知"
  }
}

function sha256(file) {
  return createHash("sha256").update(readFileSync(file)).digest("hex")
}

function mb(bytes) {
  return (bytes / 1024 / 1024).toFixed(2)
}

async function main() {
  const skipWeb = process.argv.includes("--skip-web")

  // 先做两个前置检查：都失败得很快，不用等几分钟构建完才发现打不出签名包。
  if (!existsSync(join(root, "keystore.properties"))) {
    throw new Error(
      "缺少 keystore.properties，打不出签名包（只会得到 app-release-unsigned.apk）。\n" +
        "生成方式见开发计划 §M6；密钥与口令必须和 reze-release.jks 一起备份，\n" +
        "丢了就再也无法覆盖升级已安装的包。",
    )
  }
  if (skipWeb && !existsSync(assetsIndex)) {
    throw new Error(
      `--skip-web 但 ${assetsIndex} 不存在：还没建过前端产物，去掉该参数重跑。`,
    )
  }

  if (skipWeb) {
    console.log("[1/3] --skip-web，复用现有 app/src/main/assets/web/")
  } else {
    console.log("[1/3] 生成上游静态产物…")
    await run(process.execPath, [join(__dirname, "build-web.mjs")], { cwd: root })
  }

  console.log("\n[2/3] assembleRelease（签名 APK）…")
  await run(gradlew, [":app:assembleRelease"], { cwd: root })

  const signed = join(apkDir, "app-release.apk")
  if (!existsSync(signed)) {
    const unsigned = join(apkDir, "app-release-unsigned.apk")
    throw new Error(
      existsSync(unsigned)
        ? "只产出未签名 APK：keystore.properties 里的字段可能不完整，或 Gradle 没读到签名配置。"
        : `没有找到 ${signed}，构建可能被跳过了。`,
    )
  }

  const { versionName, versionCode } = readBuildInfo()
  const apkName = `RezeDesign-Android-${versionName}-${versionCode}.apk`
  const out = join(distDir, apkName)
  mkdirSync(distDir, { recursive: true })
  copyFileSync(signed, out)

  console.log(`
[3/3] 产物已就绪
  ${out}
  版本    ${versionName}（构建号 ${versionCode}）
  上游    ${upstreamCommit()}
  大小    ${mb(statSync(out).size)} MB
  SHA256  ${sha256(out)}

把 APK 发给测试者即可。同一次发版请在 gradle.properties 里把 shellRevision 加一，
否则 versionCode 不变、装不上新版。
`)
}

main().catch((err) => {
  console.error(`\n发布失败：${err.message}\n`)
  process.exit(1)
})
