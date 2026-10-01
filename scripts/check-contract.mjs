#!/usr/bin/env node
/**
 * reze-design-android · 上游「寄生契约」检查
 *
 * 本工程不修改上游，但**依赖**上游的若干实现细节。上游一旦改动这些，
 * 外壳可能悄悄失效——尤其是导出功能：它走 `<a download>` + blob URL，
 * 在 WebView 里失败时**不报错**，只表现为"点了没反应"。
 *
 * 所以每次 bump 上游 commit 之后，先跑这个脚本：
 *
 *   node scripts/check-contract.mjs
 *
 * 全绿才继续 build-web.mjs；有红项说明需要同步适配。
 *
 * 检查的是 **上游 submodule（原始状态）**，不是 staging。
 */

import { existsSync, readFileSync } from "node:fs"
import { dirname, join } from "node:path"
import { fileURLToPath } from "node:url"

const root = join(dirname(fileURLToPath(import.meta.url)), "..")
const upstream = join(root, "reze-design")

function read(rel) {
  const p = join(upstream, rel)
  return existsSync(p) ? readFileSync(p, "utf8") : null
}

/** pattern 命中 = ok；absent 为 true 时，未命中才 = ok。 */
function match(rel, pattern, { absent = false } = {}) {
  const src = read(rel)
  if (src === null) return { ok: false, why: `文件不存在：${rel}` }
  const hit = new RegExp(pattern, "m").test(src)
  if (absent) return hit ? { ok: false, why: `${rel} 中出现了不应存在的：${pattern}` } : { ok: true }
  return hit ? { ok: true } : { ok: false, why: `${rel} 中未找到：${pattern}` }
}

/** 需要整份文件里都不含某模式的检查（如"保留页面不得有动态 API"）。 */
function fileExists(rel) {
  return existsSync(join(upstream, rel))
    ? { ok: true }
    : { ok: false, why: `目录/文件不存在：${rel}` }
}

const DYNAMIC_API =
  "export\\s+const\\s+(dynamic|revalidate)\\b|force-dynamic|generateStaticParams|generateMetadata|\\bcookies\\(|\\bheaders\\(\\)|draftMode"

/** build-web.mjs 会移出的服务端路由。 */
const SERVER_ROUTES = ["api", "admin", "[user]", "analysis"]

/** 移出服务端路由后，仍然保留在产物里的页面。 */
const KEPT_PAGES = [
  "app/layout.tsx",
  "app/page.tsx",
  "app/error.tsx",
  "app/global-error.tsx",
  "app/privacy/page.tsx",
]

const CHECKS = [
  // ---- 构建前提（决定 output:'export' 能否成立）----
  {
    level: "高",
    desc: "主编辑器是客户端组件",
    run: () => match("app/page.tsx", '^"use client"'),
    impact: "静态导出失败或页面空白",
  },
  {
    level: "高",
    desc: "next.config.ts 有注入锚点",
    run: () => match("next.config.ts", "const\\s+nextConfig\\s*:\\s*NextConfig\\s*=\\s*\\{"),
    impact: "build-web.mjs 注入 output:'export' 失败",
  },
  {
    level: "高",
    desc: "服务端路由仍在这 4 处",
    run: () => {
      const missing = SERVER_ROUTES.filter((r) => !fileExists(join("app", r)))
      return missing.length
        ? { ok: false, why: `app/ 下缺少：${missing.join(", ")}（上游可能重构了路由结构）` }
        : { ok: true }
    },
    impact: "build-web.mjs 的 SERVER_ROUTES 清单需同步",
  },
  {
    level: "高",
    desc: "保留页面不含动态 API",
    run: () => {
      const bad = KEPT_PAGES.filter((p) => {
        const src = read(p)
        return src !== null && new RegExp(DYNAMIC_API, "m").test(src)
      })
      return bad.length
        ? { ok: false, why: `下列页面出现动态 API，静态导出会失败：${bad.join(", ")}` }
        : { ok: true }
    },
    impact: "output:'export' 构建报错",
  },
  {
    level: "中",
    desc: "layout 仍用 next/font",
    run: () => match("app/layout.tsx", "next/font/google"),
    impact: "字体补丁策略需重新评估（当前网络可达 Google Fonts，未打补丁）",
  },

  // ---- 运行时外壳依赖（失效多为静默）----
  {
    level: "高",
    desc: "导出走 downloadBlob（<a download> + blob）",
    run: () => match("lib/scene-file.ts", "export\\s+function\\s+downloadBlob"),
    impact: "导出失败且**静默无提示**；需在 shim 里接管保存",
  },
  {
    level: "高",
    desc: "render-panel 探测 showSaveFilePicker",
    run: () => match("components/editor/render-panel.tsx", "showSaveFilePicker"),
    impact: "导出走了非预期分支（能力探测结果与预期不符）",
  },
  {
    level: "中",
    desc: "PNG 序列探测 showDirectoryPicker",
    run: () => match("components/editor/render-panel.tsx", "showDirectoryPicker"),
    impact: "PNG 序列导出报『需要文件夹选择器』",
  },
  {
    level: "中",
    desc: "导出默认值 key 为 reze-design.export",
    run: () => match("components/editor/render-panel.tsx", '"reze-design\\.export"'),
    impact: "预置的移动端默认值失效（非致命）",
  },
  {
    level: "低",
    desc: "演示资源基址 assets.reze.one",
    run: () => match("lib/default-scene.ts", "https://assets\\.reze\\.one"),
    impact: "演示场景加载失败（默认空场景，影响小）",
  },
  {
    level: "高",
    desc: "auth-client 是纯客户端模块",
    run: () => {
      const isClient = match("lib/auth-client.ts", '^"use client"')
      if (!isClient.ok) return { ok: false, why: "lib/auth-client.ts 不再标记 \"use client\"" }
      const src = read("lib/auth-client.ts") ?? ""
      return /server-only/.test(src)
        ? { ok: false, why: "lib/auth-client.ts 引入了 server-only，主编辑器将无法打包" }
        : { ok: true }
    },
    impact: "主编辑器被打包时拖入服务端模块",
  },
]

function main() {
  console.log(`\n=== 上游寄生契约检查 ===`)
  console.log(`上游：${upstream}\n`)

  if (!existsSync(upstream)) {
    console.error("上游目录不存在，请先 git submodule update --init --recursive\n")
    process.exit(1)
  }

  const failed = []
  for (const c of CHECKS) {
    let r
    try {
      r = c.run()
    } catch (e) {
      r = { ok: false, why: `检查抛异常：${e.message}` }
    }
    const mark = r.ok ? "✓" : "✗"
    console.log(`${mark} [${c.level}] ${c.desc}`)
    if (!r.ok) {
      console.log(`    ${r.why}`)
      console.log(`    影响：${c.impact}`)
      failed.push(c)
    }
  }

  console.log("")
  if (failed.length === 0) {
    console.log(`全部 ${CHECKS.length} 项通过，契约成立。\n`)
    process.exit(0)
  }
  console.error(`${failed.length}/${CHECKS.length} 项失配，请先同步适配再构建。\n`)
  process.exit(1)
}

main()