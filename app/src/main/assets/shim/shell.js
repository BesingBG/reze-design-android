/**
 * reze-design-android · 运行时兼容层（shim）
 *
 * 由 MainActivity 通过 WebViewCompat.addDocumentStartJavaScript 在**页面任何脚本之前**
 * 注入页面主世界，用来补齐 WebView 相对桌面浏览器缺失的能力。
 *
 * 本文件只做运行期适配，不修改上游一行代码；上游更新后这里失配的话，
 * 各段都是「检测不到就什么都不做」，最坏结果是退回原生行为，而不是把页面改坏。
 *
 * 三段彼此独立，任一段的前置条件不满足都不影响其它段：
 *   M4a  导出桥：接管 `<a download>` 的落盘
 *   M4b  文件选择器对齐：让渲染视频离开 WebView 里不可用的 FSA picker
 *   M5a  触屏可达性：让触屏够不着的控件显形
 */
(function () {
  "use strict";

  if (window.__rezeShimInstalled) return;
  window.__rezeShimInstalled = true;

  /**
   * 在文档里塞一段样式。document-start 时 `<head>` 可能还不存在，
   * 所以退一步挂到 documentElement；两者都没有才等到 DOM ready。
   */
  function injectStyle(css) {
    var el = document.createElement("style");
    el.setAttribute("data-reze-shim", "");
    el.textContent = css;
    var root = document.head || document.documentElement;
    if (root) {
      root.appendChild(el);
    } else {
      document.addEventListener(
        "DOMContentLoaded",
        function () {
          (document.head || document.documentElement).appendChild(el);
        },
        { once: true },
      );
    }
  }

  // ══════════════════════════════════════════════════════════════════════════
  // M5a · 触屏可达性
  // ══════════════════════════════════════════════════════════════════════════

  /**
   * ① 左侧 dock 默认展开。
   *
   * 上游把 dock 的初始开合状态存在 `reze-design.dockOpen.<版本>` 里，**没存过**
   * 才回落到设备规则 `!matchMedia("(pointer: coarse)").matches`
   * （`app/page.tsx` 的 `useState` 初始化）——触屏被判定为 coarse，于是首帧就是收起的。
   * 收起后只剩一条带场景名和箭头的小横条，角色列表（也就是**导入入口**）整个看不见，
   * 而触屏没有 hover，桌面用户"鼠标移过去看一眼"这条隐性路径不存在。
   *
   * 修法是回答那一次「没存过」的读取：只对 `reze-design.dockOpen.` 前缀、
   * 且**确实没有存过值**的键回 `"1"`。用户自己折叠过之后真实值就存在了，
   * 我们的回答不再生效，偏好照旧由用户说了算。
   *
   * 这里刻意不写死版本号（`reze-design.dockOpen.0.5.0`）：上游 `lib/storage.ts` 的
   * `STORAGE_VERSION` 是会随版式变化而跳的，写死就意味着上游一升级，dock 又悄悄收起，
   * 而症状是"导入入口不见了"——很难往这里联想。
   *
   * 也刻意不去改 `matchMedia`：同一个 `(pointer: coarse)` 还被
   * `components/scene/material-sidebar.tsx` 用来关掉 HTML5 拖拽（否则滑动手势会被
   * 拖拽吃掉、列表滚不动），以及 `TIMELINE_ROOM_QUERY` 用来给时间轴让位，
   * 全局改掉 pointer 判定会同时打坏这两处。
   */
  function defaultDockOpen() {
    var DOCK_KEY_PREFIX = "reze-design.dockOpen.";
    var proto = window.Storage && window.Storage.prototype;
    if (!proto || typeof proto.getItem !== "function") return;

    var origGetItem = proto.getItem;
    proto.getItem = function (key) {
      var value = origGetItem.call(this, key);
      if (value === null && typeof key === "string" && key.indexOf(DOCK_KEY_PREFIX) === 0) {
        return "1";
      }
      return value;
    };
  }

  /**
   * ② 行内按钮强制显形。
   *
   * 角色/物料行右侧的「上传替换 / 选项 / 删除」是一组 hover 才浮现的按钮
   * （`app/page.tsx` 的 `CastLine`：`opacity-0` + `group-hover:opacity-100`
   * + `focus-within:opacity-100`），而它们**没有** `pointer-events-none`。
   * 在触屏上这有两重后果：一是永远不浮现，用户根本看不到有这些操作；
   * 二是它们虽然不可见却照样能点中，误触一下就落在「删除」上。
   *
   * 与其给不可见控件加 `pointer-events:none`（那等于这些功能在手机上直接消失），
   * 不如让它们显形：手机上本来就没有 hover 可以"先看一眼再点"，
   * 操作常驻可见才是这个输入方式下唯一可用的形态。
   *
   * 选择器只认这一组类名组合，不碰页面上其它 `opacity-0`（淡入动画、提示层等）。
   * 上游的样式表在 `@layer utilities` 里，未分层样式天然优先，`!important`
   * 只是把这层保证固定下来，避免上游换构建方式后悄悄失效。
   */
  function revealHoverOnlyActions() {
    injectStyle(
      [
        'span[class~="opacity-0"][class~="focus-within:opacity-100"]{',
        "  opacity: 1 !important;",
        "}",
      ].join("\n"),
    );
  }

  defaultDockOpen();
  revealHoverOnlyActions();

  // ══════════════════════════════════════════════════════════════════════════
  // M4b · 文件选择器对齐（渲染视频点了没反应的那条路）
  // ══════════════════════════════════════════════════════════════════════════

  /**
   * 为什么要有这一段：**渲染视频和截图在壳里走的不是同一条路**。
   *
   * 截图（`captureStill`）直接产出 blob，交给 M4a 的锚点桥落盘 —— 一直是好的。
   * 渲染视频（`components/editor/render-panel.tsx` 的 `start()`）在开始渲染**之前**
   * 先问一句"存哪儿"：`"showSaveFilePicker" in window` 为真就调
   * `showSaveFilePicker()` → `handle.createWritable()`，编码出的每一帧流式写进
   * 这个 handle；只有它**抛出非 AbortError 的异常**时，上游才回落到内存 blob
   * （那条路才轮到 M4a 的锚点桥）。PNG 序列同理，走 `showDirectoryPicker`。
   *
   * 缺口在于：WebView 确实**暴露**了这个 API（MDN：WebView Android 132+ Full support），
   * 却给不出可用的落盘目标 —— 系统文件选择器那套 SAF `content://` URI 与 FSA 需要的
   * 可写 handle 对不上。而上游把 `AbortError` 一律当作"用户取消"，**静默 return**：
   * 于是表现就是"点了渲染视频，按钮动了一下，下面既没有进度、也没有报错" ——
   * 因为 `setExporting(true)`（进度条与红色「取消」按钮的唯一开关）在那之后才执行。
   *
   * 修法是把这两个 picker 从页面里拿掉：上游用 `in window` 探测，探测不到就走它自己
   * 写好的回落分支（源码注释原话："Picker unavailable/failed for another reason —
   * fall back to memory."）。渲染视频于是回到内存 blob → M4a 锚点桥 → 系统「下载」目录，
   * 进度条也就回来了。PNG 序列没有内存回落，拿掉 picker 会让上游给出它自己的
   * 「需要选择文件夹」文案，而不是点了没反应。
   *
   * 只在**有壳桥**时动手：把同一份产物挂到普通 Chromium 浏览器里调试时，FSA 是真的
   * 可用，这段就该什么都不做。
   */
  function neutralizeFilePickers() {
    var saveBridge = window.RezeSave;
    if (!saveBridge || typeof saveBridge.write !== "function") return;

    /** 拿不掉（属性不可配置）时的兜底：立刻以非 AbortError 拒绝，同样落到回落分支。 */
    function unavailable() {
      return Promise.reject(
        new DOMException("File picker is not available in this WebView", "NotSupportedError"),
      );
    }

    function drop(name) {
      // 属性挂在 Window.prototype 上，自己的影子属性与原型上的都要删。
      try { delete window[name]; } catch (e) {}
      try { delete Object.getPrototypeOf(window)[name]; } catch (e) {}
      if (!(name in window)) return true;
      try {
        Object.defineProperty(window, name, {
          configurable: true,
          writable: true,
          enumerable: false,
          value: unavailable,
        });
        return true;
      } catch (e) {
        console.warn("[reze-shim] 无法接管 " + name + "：" + (e && e.message ? e.message : e));
        return false;
      }
    }

    drop("showSaveFilePicker");
    drop("showDirectoryPicker");
  }

  neutralizeFilePickers();

  // ══════════════════════════════════════════════════════════════════════════
  // M4a · 导出桥
  // ══════════════════════════════════════════════════════════════════════════

  /**
   * 上游的全部导出（场景 zip / 场景 JSON / VMD / 相机 VMD / AE jsx /
   * 视频非流式回退）收敛在唯一函数 `lib/scene-file.ts#downloadBlob()`：
   * createObjectURL → 建 `<a download>` → appendChild → click() → remove()。
   * 也就是说，**接管 `HTMLAnchorElement.prototype.click` 这一个点，
   * 全部导出格式一次性覆盖**。
   *
   * WebView 里 `<a download>` 没有任何落盘行为（静默失败，不报错），
   * 所以这里把它换成「读 blob → 分片 base64 → 交给壳的 RezeSave 桥 →
   * 壳写进系统「下载」目录」。
   */
  var bridge = window.RezeSave;
  // 没有壳桥（例如把同一份产物挂到普通浏览器里调试）：保留原生下载行为，不做任何接管。
  if (!bridge || typeof bridge.write !== "function") return;

  /** 每片的原始字节数。base64 后约 683KB/次跨桥，手机上量级合适。 */
  var CHUNK = 512 * 1024;

  /** btoa 的可变参数上限约 65535，分块喂进去避免栈溢出。 */
  function toBase64(bytes) {
    var s = "";
    var BLK = 0x8000;
    for (var i = 0; i < bytes.length; i += BLK) {
      s += String.fromCharCode.apply(null, bytes.subarray(i, i + BLK));
    }
    return btoa(s);
  }

  /** 文件名要过 MediaStore：去掉路径分隔符与控制字符，兜底并限长。 */
  function sanitize(name) {
    var n = String(name == null ? "" : name)
      .replace(/[\\/:*?"<>|\u0000-\u001f]/g, "_")
      .trim();
    if (!n) n = "download";
    return n.length > 120 ? n.slice(0, 120) : n;
  }

  /** 逐片读取并送出；页面内存里始终只留一片。 */
  function send(blob, name) {
    var mime = blob.type || "application/octet-stream";
    var size = blob.size;
    // 空文件也要有一次调用，否则壳不会创建文件。
    var total = Math.max(1, Math.ceil(size / CHUNK));

    function step(idx) {
      if (idx >= total) return Promise.resolve();
      var start = idx * CHUNK;
      var end = Math.min(start + CHUNK, size);
      return blob
        .slice(start, end)
        .arrayBuffer()
        .then(function (ab) {
          bridge.write(name, mime, idx, total, toBase64(new Uint8Array(ab)), idx === total - 1);
          return step(idx + 1);
        });
    }

    return step(0);
  }

  /**
   * 用 fetch 立刻取回 blob，而不是把 `blob:` URL 交给壳：URL 不可跨进程传递，
   * 而且上游在 10s 后会 revokeObjectURL。此刻取回可以完全避开那次回收——
   * blob 拿到后我们全程持有引用，revoke 与否都不再影响读取。
   */
  function handleDownload(anchor) {
    var url = anchor.getAttribute("href") || anchor.href;
    var name = sanitize(anchor.getAttribute("download"));
    fetch(url)
      .then(function (r) {
        return r.blob();
      })
      .then(function (blob) {
        return send(blob, name);
      })
      .catch(function (e) {
        console.error("[reze-shim] 导出失败: " + (e && e.message ? e.message : e));
      });
  }

  var proto = HTMLAnchorElement.prototype;
  var origClick = proto.click;

  /**
   * 只拦「带 download 属性的 blob: 链接」——正是上游 downloadBlob() 的唯一形态，
   * 其余点击（普通导航、面板里的按钮）一律原样放行。
   */
  proto.click = function () {
    try {
      var href = this.getAttribute("href") || "";
      if (this.hasAttribute("download") && href.indexOf("blob:") === 0) {
        handleDownload(this);
        return;
      }
    } catch (e) {
      // 判定过程出任何岔子都落回原生行为，绝不把页面的点击吃掉
    }
    return origClick.apply(this, arguments);
  };
})();