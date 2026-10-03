# Reze Design Android

[English](README.md)

基于 [reze-design](https://github.com/AmyangXYZ/reze-design)（WebGPU MMD 动画编辑器）封装的非官方安卓版。**用于编辑、播放和渲染 MMD（MikuMikuDance）模型与 VMD 动画片段，通过 WebGPU 实时本地渲染。** 上游前端被静态导出后打进 APK，**完全离线运行**，不依赖任何外部服务。

套壳哲学：Kotlin + 系统 WebView 裸壳，**不修改上游任何源码**。所有适配只发生在构建期（`scripts/`）和运行期（`app/src/main/assets/shim/`），因此能跟着上游更新走，而不是分叉出一套自己的 UI。

## 截图

![Reze Design Android 总览](docs/screenshots/overview.webp)

> 截图素材：模型《丽塔—窈窕谍影》作者：神帝宇 · 动作：Digitrevx · 截图提供：History_exe
> 导入模型：请把模型文件夹（.pmx + 贴图）打成 zip 压缩包后导入 zip；直接选 .pmx 文件无法正常使用（贴图靠 zip 内的相对路径解析）。
> 语言：界面支持中英双语，首次启动跟随系统语言，可随时在命令面板（右上搜索框）里切换。

## 已实现

- **完全离线**：整个编辑器在 APK 里，经 `https://appassets.androidplatform.net/` 提供给 WebView —— 用真实 https 源是为了让 WebGPU 拿到安全上下文
- **文件导入**：模型、动作、音乐、场景走标准文件选择器。模型文件夹需要压成 zip —— 这是上游自己预留的移动端路径（贴图靠 zip 内的相对路径解析）
- **导出落盘**：上游用 `<a download>` + blob URL 导出，而 WebView 会**静默丢弃**、不报任何错；壳接管这条路径并分片写进 `MediaStore.Downloads`（Android 10+ 无需存储权限；API 26–28 退回应用私有外部目录）
- **触屏可达性**：角色栏默认展开（上游对粗指针设备默认收起，会连带藏起所有导入入口），行内只在悬停时出现的按钮强制常显
- **首启说明弹窗**：原生弹窗，写明设备要求、本包构建信息，并给出上游网站、桌面版、本项目主页与发布页入口
- **edge-to-edge**：由壳的容器消费系统栏 insets —— Android 15（targetSdk ≥ 35）起这是强制要求

## 不包含

需要服务端的功能已全部移除（与桌面版同档次降级）：账号登录、发布、画廊、点赞、`/api/**`、后台管理、已发布场景页。

## 设备要求

- **Android 12 或更高**，且**系统 WebView 121+** —— 两关都要过才有 WebGPU；单升级 WebView **不能**绕过安卓版本这道门槛
- GPU 大致在 Adreno 6xx 级及以上

APK 声明的 `minSdk 26`，所以在更老的设备上也能安装：界面能打开，但模型渲染不出来。

## 安装

到 [Releases](https://github.com/BesingBG/reze-design-android/releases) 下载 APK 直接安装（侧载）。没有应用商店，更新需要手动（应用内的「检查更新」就是打开发布页）。

> debug 包与 release 包签名不同，**不能**用 release 包覆盖升级 debug 包，需要先卸载。

## 构建

前置：Node 22（实测 v22.23.2）、JDK 17、Android SDK（`android-35` / build-tools `35.0.0`）。

```bash
git clone --recurse-submodules <repo-url>
cd reze-design-android

node scripts/build-web.mjs                   # 把上游前端静态导出到 app/src/main/assets/web/
node scripts/build-release.mjs               # build-web + assembleRelease -> dist/RezeDesign-Android-<版本>-<构建号>-<上游 commit>.apk
node scripts/build-release.mjs --skip-web    # 只改壳时复用现有前端产物
```

发布签名读仓库根的 `keystore.properties`（不入库）：

```properties
storeFile=reze-release.jks
storePassword=…
keyAlias=reze
keyPassword=…
```

没有这个文件也能构建：只是 `assembleRelease` 产出未签名 APK（`build-release.mjs` 会拒绝发布它），`assembleDebug` 完全不受影响。

### 版本号

`versionName` 跟随上游 submodule 的 `package.json`，**刻意不带壳修订号**。`versionCode` 由它派生，保证严格递增：

```
versionCode = 主*1_000_000 + 次*10_000 + 修订*100 + 壳修订号
```

壳修订号在 `gradle.properties` 的 `shellRevision`，**每次壳发版必须 +1** —— 不递增就装不上新版。

产物名是 `RezeDesign-Android-<versionName>-<versionCode>-<上游 commit>.apk`：因为 `versionName` 与上游一致、分不出构建批次，所以把**构建号**和**上游 commit** 都缀在后面 —— 光看文件名就知道"这是哪一版、基于上游哪次提交"（桌面版同样把 sha 放进产物名）。同样的信息也写在 `dist/build-info.json` 里，供 CI 读取。

## 云端构建（GitHub Actions）

不想在本地配 JDK / Android SDK？用 Actions 在云端出包：**Actions** → `Build APK` → **Run workflow**。

两个输入：

- `upstream_ref`：留空 = 打仓库已适配的上游版本；`latest-commit` = 上游默认分支最新提交（想先试试上游新版、又不想改 submodule 时用这个）；`latest` = 上游最新 tag；也可以填具体 tag / commit SHA；
- `upload_release`：默认勾选 —— 构建完以 **Pre-release** 发布到 Releases（tag 形如 `v1.0.1-1`，`-1` 是壳修订号，同名时覆盖，方便发完再改文案）。取消勾选则只产出 Actions Artifacts（下载需要登录 GitHub）。

一次运行依次做：上游契约检查 → 静态导出 → 签名 APK → `apksigner` 校验 → 上传 Artifact。运行页面 Summary 里有版本、构建号、大小与 SHA256，Release 页也会附上。

首次使用要配两个 Secret（**Settings → Secrets and variables → Actions**），密钥不进仓库：

```bash
# KEYSTORE_BASE64 ← reze-release.jks 的单行 base64
base64 -i reze-release.jks | tr -d '\n' | pbcopy

# KEYSTORE_PROPERTIES ← keystore.properties 的完整文本
cat keystore.properties | pbcopy
```

分别粘进两个 Secret 即可。装了 `gh` 的话一条命令：

```bash
gh secret set KEYSTORE_BASE64 --repo BesingBG/reze-design-android < <(base64 -i reze-release.jks | tr -d '\n')
gh secret set KEYSTORE_PROPERTIES --repo BesingBG/reze-design-android < keystore.properties
```

> ⚠️ `reze-release.jks` 与里面的口令**必须一起备份**：keystore 决定 App 的身份，丢了就再也无法覆盖升级已安装的包。

流水线不响应 push 与 tag。每天定时（**Auto Build on Upstream Update**，UTC 08:08 / 北京 16:08）会对比上游最新提交与最新 Release，**有新提交才**自动出包并发 Pre-release，也可以手动触发。

## 目录结构

```
app/                       Android 工程（Kotlin + 系统 WebView 裸壳）
  src/main/assets/shim/    运行期兼容层，document-start 注入
  src/main/assets/web/     上游前端的静态导出产物（构建产物，不入库）
scripts/build-web.mjs      上游 -> 静态导出（唯一发生变换的地方）
scripts/build-release.mjs  签名 APK 流水线
scripts/check-contract.mjs 对"壳依赖的上游实现细节"做回归扫描
reze-design/               git submodule（上游 reze-design，只读）
.github/workflows/         手动触发的出包流水线（build-apk.yml）
```

## 许可

[AGPL-3.0-or-later](LICENSE)。本项目打包了 AGPL-3.0 授权的上游 reze-design 前端，因此整个仓库以相同条款发布。

## 致谢

感谢 [AmyangXYZ](https://github.com/AmyangXYZ/) 创作并维护 reze-engine MMD WebGPU 渲染器及相关项目。
