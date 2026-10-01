#!/usr/bin/env node
/**
 * reze-design-android · 上游前端静态化构建（M1）
 *
 * 目标：把 submodule 里的上游源码，构建成一份可以直接塞进 APK 的静态站点，
 * 落到 app/src/main/assets/web/，供 WebViewAssetLoader 通过
 * https://appassets.androidplatform.net/ 提供服务。
 *
 * 纪律：**上游源码只读**。所有变换都发生在本脚本创建的 staging 副本上：
 *
 *   reze-design/            ← submodule，永不改动
 *      ↓ 拷贝（排除构建重物）
 *   .build/web-staging/     ← 唯一的变换发生地（.gitignore 已忽略）
 *      ↓ 补丁 + 移出服务端路由 + next build
 *   .build/web-staging/out  ← Next 静态导出产物
 *      ↓ 拷贝
 *   app/src/main/assets/web/  ← 最终随 APK 打包（.gitignore 已忽略）
 *
 * 用法：
 *   node scripts/build-web.mjs                # 完整流程
 *   node scripts/build-web.mjs --skip-install # 复用已有 node_modules（改补丁后重跑）
 *
 * 上游一旦更新，本脚本的特征匹配若失配会**立即报错退出**（fail fast），
 * 而不是产出一份悄悄坏掉的站点。失配时请对照 scripts/check-contract.mjs。
 */

import { execFileSync, spawn } from "node:child_process"
import {
  cpSync,
  existsSync,
  mkdirSync,
  readdirSync,
  readFileSync,
  renameSync,
  rmSync,
  statSync,
  writeFileSync,
} from "node:fs"
import { dirname, join } from "node:path"
import { fileURLToPath } from "node:url"

const __dirname = dirname(fileURLToPath(import.meta.url))
const root = join(__dirname, "..")                          // reze-design-android/
const upstream = join(root, "reze-design")                  // submodule（只读）
const staging = join(root, ".build", "web-staging")         // 变换发生地
const assetsWeb = join(root, "app", "src", "main", "assets", "web")  // 产物落地

/** 不参与构建的重物与版本控制数据，拷贝时排除。 */
const EXCLUDE_ENTRIES = [
  "node_modules",
  ".next",
  "out",
  ".git",
  ".claude",
  "screenshot.png",
  "showcase.jpg",
]

/**
 * 服务端路由：静态导出（output: 'export'）无法承载，从 staging 移出。
 *
 * 这些页面全部依赖 Neon DB / better-auth / server-only，对应我们明确砍掉的功能：
 *   app/api/**      → route handlers 在静态导出下根本不产出
 *   app/admin/**    → force-dynamic + requireAdmin
 *   app/[user]/**   → generateStaticParams + revalidate + db
 *   app/analysis/** → force-dynamic + db
 *
 * 只是移出 staging，不删除上游文件；移出后如果 lib/ 下的服务端模块
 * （lib/db、lib/auth、lib/public-pages…）不再被任何保留页面引用，
 * 它们自然不进入打包图。
 */
const SERVER_ROUTES = ["api", "admin", "[user]", "analysis"]

/** 静态导出必需的 next.config.ts 注入项。 */
const CONFIG_INJECTION = `
  // ---- [android-shell 注入] 静态导出：产物直接由 WebView 从 assets 加载 ----
  output: "export",
  // 让每个路由产出 <route>/index.html，配合外壳的目录索引回退
  trailingSlash: true,
  // 静态导出没有图片优化服务，必须关闭
  images: { unoptimized: true },
`

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

function copyEntries(srcDir, destDir, exclude = []) {
  mkdirSync(destDir, { recursive: true })
  for (const entry of readdirSync(srcDir, { withFileTypes: true })) {
    if (exclude.includes(entry.name)) continue
    cpSync(join(srcDir, entry.name), join(destDir, entry.name), { recursive: true })
  }
}

function dirSizeBytes(dir) {
  let total = 0
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const p = join(dir, entry.name)
    total += entry.isDirectory() ? dirSizeBytes(p) : statSync(p).size
  }
  return total
}

function countFiles(dir) {
  let n = 0
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    n += entry.isDirectory() ? countFiles(join(dir, entry.name)) : 1
  }
  return n
}

/** 上游 HEAD，写进日志便于回溯"这份产物出自哪个 commit"。 */
function upstreamCommit() {
  try {
    return execFileSync("git", ["-C", upstream, "rev-parse", "--short", "HEAD"], {
      encoding: "utf8",
    }).trim()
  } catch {
    return "未知"
  }
}

/** 步骤 1：校验上游 submodule 已就位。 */
function prepareStaging() {
  if (!existsSync(upstream)) {
    throw new Error(
      `上游目录不存在：${upstream}\n` +
        `请先执行：git submodule update --init --recursive`,
    )
  }
  if (!existsSync(join(upstream, "package.json"))) {
    throw new Error(`上游目录不完整（缺少 package.json）：${upstream}`)
  }

  console.log("[1/6] 创建 staging 副本…")
  rmSync(staging, { recursive: true, force: true })
  copyEntries(upstream, staging, EXCLUDE_ENTRIES)
  console.log(`      ${staging}`)
}

/** 步骤 2：向 next.config.ts 注入静态导出配置。 */
function patchNextConfig() {
  console.log("[2/6] 注入 next.config.ts…")
  const p = join(staging, "next.config.ts")
  if (!existsSync(p)) {
    throw new Error(`未找到 ${p}：上游可能改了配置文件位置，需同步本脚本`)
  }
  let src = readFileSync(p, "utf8")

  if (/\boutput\s*:/.test(src)) {
    console.log("      已存在 output 配置，跳过注入")
    return
  }

  const anchor = /const\s+nextConfig\s*:\s*NextConfig\s*=\s*\{/
  if (!anchor.test(src)) {
    throw new Error(
      "next.config.ts 中未找到 `const nextConfig: NextConfig = {`，注入失败。\n" +
        "上游可能改写了配置结构，需同步本脚本的锚点正则。",
    )
  }
  src = src.replace(anchor, (m) => m + CONFIG_INJECTION)
  writeFileSync(p, src)
  console.log("      output: export / trailingSlash / images.unoptimized")
}

/** 步骤 3：把服务端路由移出 staging。 */
function pruneServerRoutes() {
  console.log("[3/6] 移出服务端路由…")
  const disabled = join(staging, "_disabled")
  const moved = []
  for (const route of SERVER_ROUTES) {
    const from = join(staging, "app", route)
    if (!existsSync(from)) continue
    mkdirSync(disabled, { recursive: true })
    renameSync(from, join(disabled, route))
    moved.push(`app/${route}`)
  }
  console.log(moved.length ? `      ${moved.join("  ")}` : "      （无匹配，上游结构可能已变）")

  // 移出后仍残留的服务端引用会在 next build 阶段直接报错，不必在此提前判断。
}

/** 步骤 4：安装依赖。 */
async function installDeps(skipInstall) {
  if (skipInstall) {
    console.log("[4/6] --skip-install，跳过依赖安装")
    return
  }
  console.log("[4/6] npm ci（首次较慢）…")
  await run("npm", ["ci", "--no-audit", "--no-fund"], { cwd: staging })
}

/** 步骤 5：静态导出。 */
async function buildSite() {
  console.log("[5/6] next build（静态导出）…")
  await run("npm", ["run", "build"], { cwd: staging })

  const out = join(staging, "out")
  if (!existsSync(join(out, "index.html"))) {
    throw new Error(
      `构建未产出 out/index.html。\n` +
        `若报错涉及 headers()/redirects()，说明它们与 output:'export' 冲突；\n` +
        `若报错涉及 server-only，说明仍有保留页面引用了服务端模块。`,
    )
  }
  return out
}

/** 步骤 6：产物拷入 APK assets（保留 .gitkeep 占位）。 */
function publishToAssets(out) {
  console.log("[6/6] 产物落地 assets/web/…")
  mkdirSync(assetsWeb, { recursive: true })

  for (const entry of readdirSync(assetsWeb, { withFileTypes: true })) {
    if (entry.name === ".gitkeep") continue
    rmSync(join(assetsWeb, entry.name), { recursive: true, force: true })
  }
  copyEntries(out, assetsWeb)

  const files = countFiles(assetsWeb)
  const mb = (dirSizeBytes(assetsWeb) / 1024 / 1024).toFixed(2)
  console.log(`      ${files} 个文件，${mb} MB`)
  if (!existsSync(join(assetsWeb, "_next"))) {
    throw new Error("产物缺少 _next/ 目录（静态资源），WebView 将无法加载 JS/CSS")
  }
}

async function main() {
  const skipInstall = process.argv.includes("--skip-install")
  const commit = upstreamCommit()

  console.log(`\n=== reze-design-android 静态产物构建 ===`)
  console.log(`上游 commit: ${commit}\n`)

  prepareStaging()
  patchNextConfig()
  pruneServerRoutes()
  await installDeps(skipInstall)
  const out = await buildSite()
  publishToAssets(out)

  console.log(`\n完成。产物已就绪，可直接 ./gradlew assembleDebug 打包。\n`)
}

main().catch((err) => {
  console.error(`\n构建失败：${err.message}\n`)
  process.exit(1)
})