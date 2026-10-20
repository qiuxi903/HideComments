# HideComments 技术详解

> 本文档面向要维护、修改或移植本项目的人。
> 它记录的不只是「怎么写的」，更包括**为什么这么写**、以及每一个坑当初是怎么踩进去的。
>
> 快速上手看 [README.md](README.md)，架构与使用看 [ARCHITECTURE.md](ARCHITECTURE.md)。

---

## 目录

1. [问题定义：为什么这件事比看上去难](#1-问题定义为什么这件事比看上去难)
2. [技术选型与版本决策](#2-技术选型与版本决策)
3. [三个 App 的真实结构](#3-三个-app-的真实结构)
4. [整体架构](#4-整体架构)
5. [五道防线详解](#5-五道防线详解)
6. [核心算法](#6-核心算法)
7. [跨进程配置链路](#7-跨进程配置链路)
8. [设置界面实现](#8-设置界面实现)
9. [踩坑全记录](#9-踩坑全记录)
10. [测试与验证方法](#10-测试与验证方法)
11. [扩展指南](#11-扩展指南)
12. [性能与稳定性设计](#12-性能与稳定性设计)

---

## 1. 问题定义：为什么这件事比看上去难

「干掉评论区」听起来是个 UI 操作，实际上要同时对付四个独立的难题。

### 1.1 评论区不是一块 View

在主流视频 App 里，评论区由**三条独立的链**组成：

```
入口按钮  ──点击──>  面板容器  ──渲染──>  数据层（网络/数据库）
   │                    │                      │
   │                    │                      └─ 评论列表、评论数、空态
   │                    └─ 评论区面板、Tab 页
   └─ Tab、图标、文字标签
```

- 只藏**入口**：通过分享链接、搜索结果、深链仍能进入。
- 只藏**容器**：入口还在，点了会打开一个空白页。
- 只藏**数据**：容器还在，显示「暂无评论」。

任何一层单堵都会被绕过，必须多层同时下手。

### 1.2 SPA 式的动态渲染

这些 App 不是静态页面，View 树在持续变化：

- RecyclerView 复用 item（同一个 View 实例换数据）
- Fragment 切换容器
- 无限滚动加载新内容
- 有的界面整块重建

所以「扫一遍藏掉」不够，必须处理**插入、复用、重绘**三种时机。

### 1.3 类名和资源 ID 都被混淆

这是最硬的约束。实测结论：

- **B站把 androidx 也一起混淆了。** `androidx.viewpager.widget.ViewPager` 的 73 个方法里，页面切换那几条被改名成 `A (IZ)V` / `B (IIZZ)V` / `j (I)V`，`setCurrentItemInternal` 这种名字一个都不存在。
- **抖音的类名逐版本漂移。** 社区上流传的类名基本都过期了。
- **快手的评论区不在主 dex 里。** 它是 DVA 动态插件，运行时才装载。

社区上几个成熟的开源模块（douyim、DouyinEnhancer、mbga、kuaishou-comment-filter、Bilibili_Innocent_Lab）无一例外都用了 DexKit 或远程下发类名表，**没有一个把类名硬编码在源码里**。

### 1.4 不同 App 的机制完全不同

| App | 评论区承载方式 | 难点 |
|---|---|---|
| B站 | 详情页 ViewPager 的第 1 页 | ViewPager 被混淆，方法名不可用 |
| 抖音 | panel + VideoEvent 驱动 | 不是 Activity，没有稳定锚点；部分界面用 Compose |
| 快手 | DVA 插件里的 Fragment | 类不在主 ClassLoader 里 |

---

## 2. 技术选型与版本决策

### 2.1 为什么是 Xposed 模块而不是浏览器扩展

用户的原始需求是「安卓手机 root 后装的 lsp 模块」。这个前提决定了整个技术栈：

| 方案 | 能否覆盖 App 内 | 说明 |
|---|---|---|
| 浏览器扩展 | ❌ | 只覆盖网页版，App 内无效 |
| 无障碍服务 | ⚠️ | 能读节点但不能拦截点击/网络，且会持续显示无障碍提示 |
| **Xposed / LSPosed** | ✅ | 进程内 hook，能改 View、拦方法、改参数 |

### 2.2 框架 API：为什么用 101 而不是 102

实测数据（从 Maven 仓库元数据直接读出的）：

| 版本 | `minCompileSdk` |
|---|---|
| `io.github.libxposed:api:102.0.0` | **37** |
| `io.github.libxposed:service:102.0.0` | **37** |
| `io.github.libxposed:api:101.0.1` | 36 |
| `io.github.libxposed:service:101.0.0` | 36 |

而 **android-37 目前只在 Google 的 canary 通道里**——扫了三个 `repository2-*.xml`，37 只出现在 `CANARY` 标记下，普通 SDK Manager 装不到。

结论：钉在 101。代价是失去 102 才有的三个特性：

| 失去的能力 | 影响 |
|---|---|
| `HookBuilder.setId()` | 只影响 hook 的原子替换，本项目不需要 |
| `XposedModule.detach()` | 只是提前停止生命周期回调，不做也无害 |
| 热重载 | 改完模块要重启目标 App，可接受 |

升级路径已写在 `HookExt.kt` 注释里：把日志那行换成 `builder.setId(id)` 即可。

### 2.3 新版框架的工程结构

现代 LSPosed（libxposed API）和老版有两处根本差异，照老教程写会直接失败：

| 项目 | 老版（API 82/93） | 现代（API 101+） |
|---|---|---|
| 入口声明 | `assets/xposed_init` | `META-INF/xposed/java_init.list` |
| 模块元数据 | AndroidManifest 的 `<meta-data>` | `META-INF/xposed/module.prop` |
| 作用域 | `xposedscope` meta-data | `META-INF/xposed/scope.list` |
| 入口类 | `IXposedHookLoadPackage` | 继承 `XposedModule` |
| Hook 模型 | `XC_MethodHook` | 拦截器链 `chain.proceed()` |

`module.prop` 内容：

```properties
minApiVersion=101
targetApiVersion=101
staticScope=true
exceptionMode=protective
```

**`exceptionMode=protective` 是硬要求**：hook 里抛出的异常会被框架捕获，原方法照常执行。模块出 bug 的后果必须是「没屏蔽」，绝不能是「目标 App 闪退」。

> **老教程的陷阱**：`de.rotarycraft.xposed:api:82/100` 那个 GitHub 仓库**已被删除**。照着大量中文教程写会直接构建失败。

### 2.4 版本矩阵

| 组件 | 版本 | 选择原因 |
|---|---|---|
| Kotlin | 2.4.21 | 曾因 libxposed 用 Kotlin 2.2 编译而遇到元数据不兼容，抬到 2.4 后兼容面更广 |
| AGP | 8.7.3 | 稳定且支持 compileSdk 36 |
| Gradle | 8.14.4 | Kotlin 2.4 要求 ≥ 8.14.4 |
| compileSdk | 36 | 稳定版最高 |
| minSdk | 26 | libxposed API 要求 |
| targetSdk | 34 | 保持后台行为稳定 |

### 2.5 仓库镜像

`settings.gradle.kts` 里把阿里云镜像放在了最前面：

```kotlin
maven("https://maven.aliyun.com/repository/public")
maven("https://maven.aliyun.com/repository/google")
```

原因很实际：构建过程中遇到过 `repo.maven.apache.org` 的 **TLS 握手失败**（`Remote host terminated the handshake`），表现为「下载插件失败」，重试就好但很浪费时间。

---

## 3. 三个 App 的真实结构

以下内容来自**实机 View 树导出**，不是猜测。

### 3.1 B站（Theseus 框架）

```
TheseusAncestorLayout
├── FrameLayout  #video_area                     播放器
│   └── FrameLayout  #video_container
│       └── ... SurfaceInternalView ...
├── AppBarLayout  #appbar
│   └── LockableCollapsingToolbarLayout  #collapsing_toolbar
│       └── TouchAwareToolbar  #nav_top_bar      顶栏
└── RoundFrameLayout  #round_container
    └── TouchAwareConstraintLayout  #content_container
        ├── UnitedViewPager  #pager              老版 androidx ViewPager
        │   ├── ConstraintLayout                 第 0 页：简介
        │   │   └── UnitedRecyclerView  #recycler
        │   └── ConstraintLayout                 第 1 页：评论
        │       └── FrameLayout  #comment_container
        │           └── ... #cmt3_swipe_refresh
        │                   #cmt3_recycler  (CmtRecycleView)
        │                   #cmt3_placeholder
        │                   #comment_tag_tab_layout
        │                   #cmt3_fake_input_bar_container
        ├── UnitedTabLayout  #tabs               标签栏
        │   └── TabLayout$SlidingTabIndicator
        │       ├── TabLayout$TabView            简介
        │       │   └── ConstraintLayout > #tab_title("简介")
        │       └── TabLayout$TabView            评论
        │           └── FrameLayout #container > LinearLayout #tab_layout
        │               ├── TintTextView #tab_title("评论")
        │               └── TintTextView #tab_sub_title("126")
        └── ...
```

**关键观察**：

1. `UnitedViewPager` 的继承链是 `UnitedViewPager < ViewPager < ViewGroup < View < Object`（androidx 的 ViewPager）。
2. 评论 Tab 的标题藏在三层之下，**不是 `TabLayout.Tab.setText()`**，所以拦 `addTab` 无效。
3. 评论页是 pager 的第 1 页，简介页是第 0 页。
4. 评论区内部有清晰的 id 前缀 `cmt3_`。

### 3.2 抖音

- 主页的四个 Tab（首页/朋友/消息/我的）本身就是一个 **ViewPager2**。
- 评论面板挂在首页那一页里（panel + VideoEvent 驱动），**不是独立 Activity**。
- 部分新界面用 **Jetpack Compose**——整屏只有一个 `AndroidComposeView`，内部节点不是 View，View 树遍历对它无效。
- 类名逐版本漂移，**没有任何硬编码锚点**，完全依赖通用规则 + dex 索引。

### 3.3 快手

- 评论区是一个 **DVA 动态插件**：`assets/dva_feature/comment_detail-master.zip`。
- 由 `com.kwai.plugin.dva.feature.core.loader.FeaturePluginLoader` 在运行时装载。
- 主 ClassLoader 里**没有** `com.yxcorp.gifshow.comment.*`。
- 加载器的方法名被混淆（形如 `b(...)`），每版还可能换字母。

---

## 4. 整体架构

### 4.1 分层

```
┌─────────────────────────────────────────────────────────────────┐
│ app 模块（设置 App 进程）                                        │
│  MainActivity / SettingsActivity                                │
│  FrameworkRepository  ── XposedService (AIDL) ──> 框架           │
└─────────────────────────────────────────────────────────────────┘
                              │ remote preferences
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│ hook 模块（注入到目标 App 进程）                                  │
│                                                                 │
│  HideCommentsModule  ← 框架回调，按进程过滤                       │
│         │                                                       │
│         ▼                                                       │
│  Host（进程级单例）                                              │
│    ├─ ConfigProvider        每 2s 读一次配置                     │
│    ├─ classLoaders[]        主 dex + 插件 ClassLoader            │
│    └─ Profile               B站 / 抖音 / 快手                    │
│         │                                                       │
│         ├── CoreHooks.install()      通用 hook 全部挂上           │
│         └── Profile 专属 hook                                     │
│                                                                 │
│  engine/（不认识任何具体 App）                                    │
│    ViewClassifier → ViewShield → PagerGuard / NavigationGuard   │
│    DexIndex → DynamicHooks                                      │
└─────────────────────────────────────────────────────────────────┘
```

### 4.2 进程模型

```
框架（LSPosed）启动目标 App
        │
        ├─ 进程 com.ss.android.ugc.aweme（主进程）      ← 我们在这里工作
        ├─ 进程 com.ss.android.ugc.aweme:push          ← detach，不管
        └─ 进程 com.ss.android.ugc.aweme:webview       ← detach，不管
```

`HideCommentsModule.onPackageReady` 里的过滤：

```kotlin
if (processName != packageName) {
    Logger.d("skipping secondary process $processName")
    return
}
```

在多进程 App 的每个子进程里挂 View 树 hook 既没意义，又是白白的性能开销。

### 4.3 ClassLoader 列表

`Host` 维护一个 `CopyOnWriteArrayList<ClassLoader>`：

```kotlin
private val classLoaders = CopyOnWriteArrayList<ClassLoader>()

fun addClassLoader(loader: ClassLoader?) { ... }
fun resolveClass(name: String): Class<*>? =
    Reflect.findClass(name, classLoaders.toList())
```

主 dex 之外，快手的插件加载器一出现就把它的 ClassLoader 追加进去。之后所有 `Class.forName` 都会多试这些 loader。

### 4.4 一个 Profile 的最小实现

```kotlin
object SomeProfile : AppProfile {
    override fun install(host: Host) {
        CoreHooks.install()                       // 五道防线
        DexIndex.ensureScanned(KEYWORDS) { names ->  // 动态发现
            DynamicHooks.installFromCandidates(host, names)
        }
    }
    private val KEYWORDS = listOf("comment", "cmt", "reply")
}
```

三个 App 的 Profile 差异只在「专属锚点」：

| Profile | 专属部分 |
|---|---|
| Bilibili | Gemini ViewHolder、comment3 Adapter、三个实测确认的评论控件类 |
| Kuaishou | DVA 插件加载器 hook + 插件 ClassLoader 注册 |
| Douyin | 无（全动态） |

---

## 5. 五道防线详解

### 5.1 文字层：`TextView.setText`

**为什么需要它**：宿主的时序是「先 addView，后 setText」。在 addView 那一刻读文字是空的，判定必然落空。

```kotlin
// before：拦住原方法，文字根本不设进去
Host.module.hookBefore(method, "hc.setText.block") { chain ->
    val raw = chain.getArg(0) as? CharSequence ?: return@hookBefore false
    if (raw.length !in 1..24) return@hookBefore false          // 便宜的预筛
    if (!maybeCommentLabel(raw.toString())) return@hookBefore false
    if (!ViewClassifier.isCommentLabel(raw)) return@hookBefore false  // 精确匹配
    pendingCommentTexts.add(view)
    hideEntryAndTabItem(view)
    true   // 已消费
}
```

**为什么是「精确等于」而不是「包含」**：

- `text == "评论"` → 判定为入口
- `text == "评论区里的置顶评论"` → 正文，不拦
- 归一化会去掉数字和括号：`"评论(128)"` → `"评论"`

**兜底**：还在 after 分支里挂了一次，万一宿主覆写了 `setText`（before 没生效），至少能事后藏掉。

### 5.2 容器层：种子 → 容器

```kotlin
fun panelContainer(seed: View, root: View): View {
    var current = seed.parent as? View
    while (current != null) {
        if (isListItem(current)) return seed          // 红线：列表项不碰
        if (hasCommentishDescendant(current)) {       // 自证：这片区域真有评论控件
            if (current.height > root.height * 0.6f) break
            if (current.top < root.height * 0.15f) break
            if (PagerGuard.isPageBoundary(current)) break
            return current
        }
        current = current.parent as? View
    }
    return seed
}
```

三道闸门：

| 闸门 | 作用 |
|---|---|
| `isListItem` | RecyclerView 的直接子节点 = 一条内容，绝不藏 |
| `hasCommentishDescendant` | 自证：区域里确实有 id/类名带 comment 的控件 |
| 尺寸 / 位置 / 页边界 | 别爬到全局容器 |

**`hasCommentishDescendant` 是必须的**。没有它时，搜索页排序栏里的「按热度」会命中种子 → 一路往上爬 → 整个搜索结果列表被 GONE → 一个视频都搜不到。

### 5.3 手势层：`onInterceptTouchEvent`

**这是最关键的一层**，也是试错最多的。

翻页器的拖拽链路：

```
手指 → ViewPager.onInterceptTouchEvent() → (接管) → onTouchEvent()
                                                        │
                                                        └─ mRecyclerView.scrollTo(...)
                                                             直接平移内部 RecyclerView
```

三种方案的实测结果：

| 方案 | 结果 |
|---|---|
| 拦 `setCurrentItemInternal`（切页） | RecyclerView 已被拖到半路，画面歪着，回滚也歪 |
| 拦下后调 `setCurrentItem(上一页)` 回退 | ① androidx 内部有 `if (item == mCurItem && mItems.size() != 0) return;` 短路；② 就算生效，画面也追不上 |
| 放行再弹回 | 翻过去再翻回来，宿主的简介页被回收重建，**内容没了** |
| **拦 `onInterceptTouchEvent`** | 翻页器不接管手势，RecyclerView 一个像素都没动 ✅ |

实现：

```kotlin
Host.module.hookExec(method, "hc.pagerTouchGuard") { chain ->
    val event = chain.getArg(0) as? MotionEvent ?: return@hookExec chain.proceed()
    when (event.actionMasked) {
        ACTION_DOWN -> { downX.set(event.x); chain.proceed() }
        ACTION_MOVE -> {
            val delta = event.x - downX.get()
            val position = callInt(pager, "getCurrentItem") ?: 0
            if (delta < 0 && (position + 1) in pages) {
                return@hookExec false   // 不接管，手势落回下面的竖向列表
            }
            chain.proceed()
        }
        else -> chain.proceed()
    }
}
```

### 5.4 跳转层

```kotlin
// View.performClick：看不见也点不着
hookBefore(performClick) { chain ->
    val view = chain.thisObject as? View
    if (classify(view) == ENTRY) true else false
}

// startActivity：深链 / 分享链接
hookBefore(startActivity) { chain ->
    val intent = chain.getArg(0) as? Intent
    if (NavigationGuard.commentRelatedReason(intent) != null) true else false
}
```

`NavigationGuard` 的判定刻意收得很紧——宁可漏一个深链，也不能把正常页面跳转拦了：

```kotlin
// 命中任一即拦
intent.component?.className 含 comment / reply / cmt
intent.action 含 comment
intent.data 含 comment 或 /reply
extras 的 key 含 comment 且值是非空字符串/数字
```

### 5.5 兜底层

#### 周期扫描

```kotlin
// 界面刚起来的前 5 秒：120ms 一次；之后 1 秒一次
val interval = if (elapsed < BURST_DURATION_MS) BURST_INTERVAL_MS else SCAN_INTERVAL_MS
```

为什么需要 burst：详情页从打开到评论入口完成加载，中间有个空窗期。这段时间控件已插进树、但文字还没设上，只有密集扫描才能盖住。

#### 绘制前重申

```kotlin
observer.addOnPreDrawListener(object : OnPreDrawListener {
    override fun onPreDraw(): Boolean {
        runCatching { if (Host.isActive()) ViewShield.reassert() }
        return true   // 永远放行绘制
    }
})
```

`reassert()` 只遍历我们已隐藏的那几十个 View，不碰整棵树。

#### 插即隐

```kotlin
// ViewGroup.addView(view) / addView(view, index) / addView(view, index, params)
hookAfter(method) { _, _ -> hideIfCandidate(chain.getArg(0) as? View) }
```

#### dex 动态发现

见 [6.4](#64-dex-索引与动态发现)。

---

## 6. 核心算法

### 6.1 View 识别器（ViewClassifier）

四档可靠度，从最可靠往下退：

```kotlin
fun classify(view, strength, root): MatchKind {
    // 不可见视图：只认 id 名，且一律按容器处理
    if (view.visibility != VISIBLE) { ... return PANEL }

    // ── 第 1 档：资源 ID 名 ──────────────────────
    // id 的字符串形式（getResourceEntryName），比整数值稳定
    if (idName 含 "comment" / "reply" / "cmt") {
        return if (isRowLike(view, root)) ENTRY else PANEL
    }

    // ── 第 2 档：类名 ────────────────────────────
    // 按 Class 缓存成位掩码，避免每次 addView 都做字符串扫描
    val mask = classMask(view.javaClass)
    if (mask has PANEL) return if (isRowLike) ENTRY else PANEL
    if (mask has ENTRY) return ENTRY

    // ── 第 3 档：文字（GENTLE 档跳过）──────────────
    if (strength != GENTLE) {
        val text = normalizedText(view)
        if (text in PANEL_SEED_TEXTS) return PANEL
        if (text in ENTRY_TEXTS) return ENTRY        // 精确等于，不要求可点击
        if (looksLikeEntryText(text) && hasClickableAncestor(view)) return ENTRY
    }

    // ── 第 4 档：几何猜测（只有 AGGRESSIVE）────────
    if (strength == AGGRESSIVE && looksLikeCommentPanel(view, root)) return PANEL

    return NONE
}
```

**`isRowLike` 的陷阱**：判据是「高度 < 根容器 1/4」。一个 GONE 的视图高度是 **0**，0 比任何正数都小，会被判成「行级控件」——评论区容器因此被误判成入口按钮。

后果很严重：**页码只对 PANEL 学习**，容器被误判成 ENTRY 后页码永远学不到，翻页守卫形同虚设。

修法是两条：

```kotlin
// 1) 不可见视图一律按容器处理
if (view.visibility != VISIBLE && idName 含 comment) return PANEL

// 2) 高度为 0 说明还没测量，不按行级处理
private fun isRowLike(view, root): Boolean {
    if (view.height == 0) return false
    ...
}
```

**归一化规则**：

```kotlin
fun normalizeText(raw: String): String {
    text = text.replace(Regex("[（(【\\[][^）)】\\]]{0,12}[）)】\\]]"), "")  // 去掉 "(128)"
    text = text.replace(Regex("[0-9]"), "")                                 // 去掉数字
    text = text.replace(Regex("\\s+"), "")                                  // 去掉空白
    return text.lowercase()
}
```

### 6.2 隐藏状态机（ViewShield）

```
状态：hidden: Map<View, HiddenState>
      HiddenState(originalVisibility, reason, sticky)

hide(view, kind)
  ├─ 注册页码（如果是 PANEL）      ← 每帧都可能重复调用，必须幂等
  ├─ 注册 Tab 页码（如果是 TabView）
  ├─ 已藏过 → 直接 applyGone 后返回
  └─ 首次 → 记录原可见性 → GONE

reassert()          遍历 hidden，把被 App 改回可见的再压回 GONE
scan()              全树扫描，产出 matched；对不再匹配的恢复
restoreAll()        全部恢复（配置变了 / 关掉模块时）
```

**三个必须处理的细节**：

1. **重入保护**

```kotlin
private val reentry = ThreadLocal<Boolean>()

fun applyGone(view: View) {
    if (reentry.get() == true) return
    reentry.set(true)
    try { view.visibility = GONE } finally { reentry.set(false) }
}
```

我们自己的 `setVisibility(GONE)` 会再次触发 `setVisibility` hook，没有这个保护就是无限递归。

2. **不恢复仍处于 GONE 的视图**

```kotlin
// 扫描时对「没匹配上」的视图恢复，但跳过已经 GONE 的
if (view.visibility == View.GONE) continue
```

为什么：隐藏后视图宽高归零，基于尺寸的判定规则立刻失效。如果照单恢复，就形成「隐藏 → 复查判定不通过 → 恢复 → 绘制前守卫又压回去 → 下一轮再恢复」的抖动，表现为评论区闪一下之后一直留在屏幕上。

3. **sticky 标记**

`TabView` 被标成 `sticky = true`，永不被恢复。原因：App 重建 Tab 时并没有重建它的兄弟节点（评论数 `#tab_sub_title`），放出来就会看到「126」孤零零挂在那里。

### 6.3 翻页守卫（PagerGuard）

#### 页码学习

两条路径，只有一条进 `commentPages`：

```kotlin
// ✅ 可信：标签栏里写着「评论」的那个 Tab 的下标
fun noteCommentTab(tabView: View) {
    val indicator = tabView.parent as? View
    val tabLayout = indicator.parent as? ViewGroup
    val index = indexOfTabView(tabLayout, tabView)   // 要往下翻 SlidingTabIndicator
    val parent = tabLayout.parent as? ViewGroup
    for (child in parent.children) {
        if (isPagerLike(child)) {
            commentPages[child] += index
            installTouchGuard(child)   // 装手势守卫
        }
    }
}

// ❌ 不可信：只做诊断，不写 commentPages
fun noteCommentPage(view: View) { ... }
```

**为什么「评论面板在某页里」不可信**：抖音的主界面本身是 ViewPager2，评论面板挂在首页那一页。照此推断会把第 0 页当成评论页 → **整个主界面切换被拦死**。

#### 计算 Tab 下标要往下翻一层

Material 的 TabLayout 结构：

```
UnitedTabLayout  #tabs
└── TabLayout$SlidingTabIndicator    ← 直接孩子是它
    ├── TabLayout$TabView            ← 标签在这一层
    └── TabLayout$TabView
```

所以 `indexOfTabView` 必须先找直接孩子、再找孙子：

```kotlin
private fun indexOfTabView(tabLayout: ViewGroup, tabView: View): Int {
    var index = 0
    for (i in 0 until tabLayout.childCount) {
        val child = tabLayout.getChildAt(i)
        if (child === tabView) return index
        if (isTabItem(child)) { index++; continue }
        if (child is ViewGroup) {
            for (j in 0 until child.childCount) {
                val sub = child.getChildAt(j)
                if (sub === tabView) return index
                if (isTabItem(sub)) index++
            }
        }
    }
    return -1
}
```

#### 识别翻页器：按结构，不按类名

```kotlin
private fun isPagerLike(view: View): Boolean {
    if (view.javaClass.name == VIEWPAGER2) return true
    val hasCurrent = view.javaClass.methods.any {
        it.name == "getCurrentItem" && it.parameterCount == 0
    }
    val hasSet = view.javaClass.methods.any {
        it.name == "setCurrentItem" && it.parameterCount in 1..2
    }
    return hasCurrent && hasSet
}
```

这两个方法只有翻页器才有，宿主改类名、换基类都改不掉。

#### 找 hook 方法：按签名，不按名字

```kotlin
val candidates = ownMethods(clazz).filter { method ->
    method.parameterCount in 1..4 &&
        method.returnType == Void.TYPE &&
        method.parameterTypes.firstOrNull() == Int::class.javaPrimitiveType &&
        method.name !in IGNORED_PAGER_METHODS
}
```

`ownMethods` 只取这个类自己声明的（到 `ViewGroup` / `View` 就停），把 `onMeasure` / `onSizeChanged` 这类「第一参数是 int」的系统方法天然排除。

B站实测的待挂钩方法：

```
UnitedViewPager 待挂钩方法：A(2), B(4), j(1), onMeasure(2), onSizeChanged(4),
                            p(3), setCurrentItem(1), v(1), w(4), z(4)
```

`onMeasure` / `onSizeChanged` 在 `IGNORED_PAGER_METHODS` 之外还会被执行时的页码范围二次确认挡掉。

### 6.4 dex 索引与动态发现

#### DexIndex

不反编译、不扫方法体，只用 `DexFile.entries()` 读类名：

```kotlin
val classes: Enumeration<String> = dexFile.entries()   // 注意：直接是类名字符串
while (classes.hasMoreElements()) {
    val name = classes.nextElement()
    if (lowered.none { name.lowercase().contains(it) }) continue
    index.getOrPut(lowerName) { ArrayList() }.add(name)
}
```

实测数据（B站国际版）：**走了 277,317 个类，留下 8,650 个候选**。

要点：

- `DexFile.entries()` 返回 `Enumeration<String>`，**没有 `Entry` 对象那一层**。
- 按关键字先过滤再入索引，否则内存吃不消。
- 后台线程扫，完成后回调。
- 插件化 App 用 `registerExtraSource(loader)` 补扫插件 dex（反射 `pathList` → `dexElements[].path`）。

#### DynamicHooks

把候选类变成实际 hook：

```kotlin
for (name in candidates) {
    if (UI_HINTS.none { lower.contains(it) }) continue   // layout/panel/fragment/view/...
    val clazz = Class.forName(name, false, loader)

    when {
        ViewGroup::class.java.isAssignableFrom(clazz) -> hookViewConstructors(clazz)
        fragmentSupers.any { it.isAssignableFrom(clazz) } -> hookFragment(clazz)
    }
}
```

| 类型 | hook 点 | 效果 |
|---|---|---|
| ViewGroup 子类 | 全部构造方法 | 一实例化就 GONE |
| Fragment 子类 | `onCreateView` / `onViewCreated` | 根 View 一出来就 GONE |

上限 80 个 hook，防止把方法内联打爆。

### 6.5 导航判定（NavigationGuard）

判定顺序（命中即返回）：

```kotlin
intent.component?.className   → 含 comment / reply / cmt
intent.action                 → 含 comment
intent.data                   → 含 comment 或 "/reply"
intent.extras                 → key 含 comment/reply/cmt 且值为 String/Int/Long
```

刻意收得很紧：宁可漏一个深链，也不能把正常页面跳转拦了——那比不禁还糟。

### 6.6 View 树导出（ViewDumper）

**必须等布局完成**。界面刚建立时所有尺寸都是 `0x0`，尺寸信息一点用都没有——而尺寸恰恰是判定「这是不是评论区面板」的关键依据。

所以延迟两次：1 秒（稳定态）和 3 秒（内容加载后）。

输出格式：

```
com.bilibili.ship.theseus.united.widget.UnitedViewPager  #pager  [1272x1754]
  android.widget.FrameLayout  #comment_container  [0x0]  vis=GONE   <== MATCH PANEL
```

同时做了两件事：

- 同一个 Activity 不重复抓（否则 `onPostResume` 每次触发都会刷出一堆文件）
- 只留最近 3 份（`pruneOldDumps`）

---

## 7. 跨进程配置链路

### 7.1 为什么不能直接用 SharedPreferences

hook 代码运行在目标 App 的进程里，访问不到模块 App 的私有目录；而直接读模块的 SharedPreferences 又有权限限制。

官方方案是 **Remote Preferences**：

```kotlin
// 设置侧（可写）
val prefs = xposedService.getRemotePreferences("hide_comments_config")
prefs.edit().putString("config", json).commit()

// hook 侧（只读）
val prefs = module.getRemotePreferences("hide_comments_config")
val json = prefs.getString("config", null)
```

注意：**hook 侧拿到的是只读的**。这是框架有意为之——被注入的 App 只能看，不能改设置 App 的数据。

### 7.2 为什么整包成一个 JSON 字符串

```kotlin
val root = JSONObject()
root.put("masterEnabled", masterEnabled)
root.put("enabledApps", JSONArray(enabledApps.toList()))
...
return root.toString()
```

只占一个 key，避免「改到一半被读到」的半更新状态。而且加字段不用改存储层。

### 7.3 刷新策略

`ConfigProvider` 每 2 秒重读一次：

```kotlin
private const val RELOAD_INTERVAL_MS = 2000L

fun get(): HideConfig {
    val now = SystemClock.uptimeMillis()
    if (now - lastReadAt < RELOAD_INTERVAL_MS) return cached
    lastReadAt = now
    cached = HideConfig.fromJson(prefs?.getString(KEY, null))
    Logger.verbose = cached.verboseLog
    return cached
}
```

效果：改完设置**不用重启目标 App**，2 秒内自动生效。

### 7.4 配置变化时先恢复再重扫

```kotlin
val configChanged = configFingerprint != null && configFingerprint != fingerprint
if (configChanged) restoreAll()

fun fingerprintOf(config) = "${config.hideEntry}|${config.hidePanel}|${config.strength.id}"
```

不先恢复的话，旧规则藏掉的东西在新规则下永远撤销不掉。

---

## 8. 设置界面实现

### 8.1 为什么用 View Binding

`findViewById<T>()` 是**泛型擦除的不安全转换**。曾踩过：

```kotlin
// XML 里是 MaterialSwitch
findViewById<SwitchMaterial>(R.id.switch_master)   // 编译通过，运行时崩
// ClassCastException: materialswitch.MaterialSwitch cannot be cast to
//                     switchmaterial.SwitchMaterial
```

Material Components 里有**两个看起来一样的开关类，互不继承**：

- `com.google.android.material.materialswitch.MaterialSwitch`
- `com.google.android.material.switchmaterial.SwitchMaterial`

换成 View Binding 后类型由 XML 生成，写错一个字母编译期就报错。

### 8.2 开关必须用 `setOnCheckedChangeListener`

`CompoundButton.performClick()` 的实现顺序是：

```java
public void performClick() {
    ...
    if (mOnClickListener != null) mOnClickListener.onClick(this);   // ← 监听器先跑
    toggle();                                                        // ← 然后才翻转
}
```

所以 `setOnClickListener` 里读 `isChecked` 拿到的是**旧值**。用 click 监听器会让「怎么点都关不上」。

`setOnCheckedChangeListener` 直接把新值传进来，不依赖实现顺序。

### 8.3 保存与刷新的容错

`BaseSettingsActivity` 把两条路径都包住：

```kotlin
protected fun commit(newConfig: HideConfig) {
    config = newConfig
    try {
        if (!FrameworkRepository.save(config)) toast(保存失败)
    } catch (t: Throwable) { showError("保存失败", t) }
    safeRender()
}

private fun safeRender() {
    try { renderConfig() }
    catch (t: Throwable) { showError("界面刷新失败", t) }
}
```

`showError` 弹一个对话框显示完整堆栈——用户截图就能反馈根因，不用连电脑。

### 8.4 框架异步绑定的时序

`XposedServiceHelper` 是异步的。Activity 起来时可能还没连上，这时读到的是默认值。

```kotlin
// FrameworkRepository
private val listeners = CopyOnWriteArrayList<(Boolean) -> Unit>()

fun addConnectionListener(listener: (Boolean) -> Unit)
fun onBind(service) { ...; notifyConnection(true) }
```

界面在 `onStart` 订阅、`onStop` 退订。框架真正连上时重新读一次配置并刷新。

同时 `loadOrNull()` 区分了「读不到」和「读到默认值」：

```kotlin
fun loadOrNull(): HideConfig? {
    val store = prefs() ?: return null      // 没连上 → null
    return HideConfig.fromJson(store.getString(KEY, null))
}
```

未连接时状态栏明确显示「未连接」，不再假装一切正常。

### 8.5 MIUI 视觉规范

| 元素 | 取值 |
|---|---|
| 主色 | `#3482FF` |
| 浅色背景 | `#F5F5F7` |
| 深色背景 | `#111214` |
| 卡片 | 纯白 / `#1E1F22`，零描边零投影 |
| 圆角 | 18dp |
| 组内分隔线 | 1dp，左缩进 18dp 对齐文字 |
| 行高 | 52~56dp |

用 Material3 主题承载这些值，`values/themes.xml` 与 `values-night/themes.xml` 各一套。

---

## 9. 踩坑全记录

按时间顺序，每条包含：**现象 → 根因 → 修法**。

### 9.1 构建期

| # | 现象 | 根因 | 修法 |
|---|---|---|---|
| 1 | `de.rotarycraft.xposed:api` 拉不到 | 该 GitHub 仓库已删除 | 改用 `io.github.libxposed:api:101.0.1` |
| 2 | `minCompileSdk=37` 报错 | libxposed 102 要求 android-37，而它只在 canary 通道 | 降到 101 |
| 3 | AGP 拒绝构建：路径含非 ASCII | AGP 的安全检查 | `android.overridePathCheck=true` |
| 4 | `source file or directory not found: G:\project\u5220...` | Kotlin 编译守护进程把中文路径转义后传给 javac | `kotlin.compiler.execution.strategy=in-process` |
| 5 | `Module was compiled with an incompatible version of Kotlin` | 库用 Kotlin 2.2 编译，工程用 2.0 | Kotlin 抬到 2.4.21 |
| 6 | `jvmTarget: String` 变成错误 | Kotlin 2.4 废除了 `kotlinOptions` | 改 `kotlin { compilerOptions { ... } }` |
| 7 | 插件下载 TLS 握手失败 | `repo.maven.apache.org` 偶发 | 阿里云镜像放最前 |
| 8 | `Unresolved reference 'databinding'` | Binding 类按 namespace 生成 | `com.hidecomments.app.databinding` |

### 9.2 运行时崩溃

| # | 现象 | 根因 | 修法 |
|---|---|---|---|
| 9 | 点图标就崩：`ClassCastException` | XML 用 `MaterialSwitch`，Kotlin 声明 `SwitchMaterial` | 全量改 View Binding |
| 10 | 5 个开关点了全崩 | `status_ok` 有 4 个占位符，只传了 3 个参数 → `MissingFormatArgumentException` | 补齐参数 |
| 11 | 开关「怎么点都关不上」/ 总显示关闭 | `performClick` 先跑监听器后 toggle，click 里读到旧值 | 改 `setOnCheckedChangeListener` |
| 12 | `Application.attach` NPE | `getApplicationContext()` 在 attach 那一刻还是 null | `?: context` 兜底 |
| 13 | 随机 `ConcurrentModificationException` / 卡死 | `WeakHashMap` 被主线程扫描、`setVisibility` hook、构造 hook 三条线程同时读写 | 全部 `Collections.synchronizedMap` + 锁内取快照 |
| 14 | 宿主偶发闪退 | `OnPreDrawListener` 不在框架的 PROTECTIVE 保护范围内 | 整段 `runCatching` 兜住 |

### 9.3 逻辑错误

| # | 现象 | 根因 | 修法 |
|---|---|---|---|
| 15 | 原方法被执行两次 | `hookBefore` 用「返回值非 null」当哨兵，而 void 方法的 `proceed()` 返回 null | 改成返回 `Boolean`（true = 已消费） |
| 16 | 只有第一个界面有绘制前守卫 | `guardAttached` 布尔值永不复位 | 记 `guardedRoot` 弱引用 |
| 17 | 翻页后扫描停摆 | 周期扫描的 Runnable 抛异常，`postDelayed` 不再执行 | 挪进 `finally` |
| 18 | 评论 Tab 闪一下才消失 | `addView` 时文字还没设 | hook `setText` 同帧拦下 |
| 19 | 评论数「126」残留 | 只藏了文字，TabView 框架和兄弟节点还在 | 整块藏 TabView + sticky |
| 20 | 顶边栏整个消失 | 上溯爬过了 TabView，爬进标签栏本身 | 爬到 TabView 就 break |
| 21 | 简介页内容空白 | 上溯爬过页边界，把兄弟页一起藏了 | 加 `isPageBoundary` |
| 22 | 搜索页视频全没了 | 卡片里 `#tv_right_comment_text` 触发 id 规则 | 列表项红线 |
| 23 | 抖音主界面切不动 | 评论面板在 pager 第 0 页 → 第 0 页被当成评论页 | 页码只从 Tab 下标学 |
| 24 | 翻页守卫只挂上 1 个 hook | 按方法名找，而 androidx 被混淆成 `A/B/j/z` | 按签名找 |
| 25 | 回退不生效 | `setCurrentItem` 内部 `if (item == mCurItem && mItems.size() != 0) return` 短路 | 改拦 `onInterceptTouchEvent` |
| 26 | 回退后画面歪着 | RecyclerView 已被拖走，状态与画面对不上 | 同上，根本不让它动 |
| 27 | 放行再弹回后简介内容丢失 | 翻过去再翻回来，宿主回收重建了简介页 | 同上 |
| 28 | 页码学不到 | 容器创建时 parent 为 null，且 `hide()` 有提前返回 | 把学习动作移到提前返回之前 |
| 29 | 算子页码失败 | TabView 是 `SlidingTabIndicator` 的孩子，不是标签栏直接孩子 | 下标计算往下翻一层 |
| 30 | dump 目录不存在 | `Host.appContext` 为 null 时静默 return | 改用 Activity 上下文 |
| 31 | dump 全是 0x0 | 布局完成前抓的 | 延迟 1s / 3s |
| 32 | dump 刷出几十个文件 | `onPostResume` 每次都触发两遍 | Activity 去重 + 只留 3 份 |
| 33 | 列表行与 App 错位 | 插入分隔线后子节点下标不再对应 | 显式记录 `appRows` |
| 34 | `DexFile.entries()` 编译错误 | 它返回 `Enumeration<String>`，不存在 `Entry` 内部类 | 直接当字符串用 |

### 9.4 几个反复出现的模式

**模式一：被隐藏的视图不能再用尺寸判断。**

隐藏 → 宽高归零 → 基于尺寸的判定失效 → 判定「不是评论区」→ 恢复 → 又露出来。凡是「隐藏后复查」的逻辑，都要跳过已经 GONE 的视图。

**模式二：文案是最不可靠的特征。**

「按热度」在搜索页是排序词，在评论区也是排序词。拿它当容器种子，就会把搜索结果列表整个藏掉。文案只适合做**辅助信号**，且必须配合「自证」（这片区域里真有 comment 控件）。

**模式三：协议层比 UI 层稳。**

「评论面板在某页里」这种推断看着合理，实际上跨 App 完全不成立。只有**明确的语义锚点**（标签栏里写着「评论」的 Tab）才可信。

---

## 10. 测试与验证方法

### 10.1 构建验证

```bash
./gradlew clean
./gradlew :app:assembleDebug :app:assembleRelease
```

### 10.2 APK 内容验证

```bash
# Xposed 元数据必须在
unzip -l app/build/outputs/apk/debug/app-debug.apk | grep META-INF/xposed
unzip -p app/build/outputs/apk/debug/app-debug.apk META-INF/xposed/module.prop

# hook 类必须被打进去（但设置界面不能编译期引用它）
unzip -l app/build/outputs/apk/debug/app-debug.apk | grep classes

# libxposed:api 的桩类不能被打进 APK（会冲突）
# 用 dexdump 检查没有 io/github/libxposed/api/* 即可
```

### 10.3 实机验证清单

| 场景 | 预期 |
|---|---|
| 点图标 | 进主界面，状态栏显示框架名 + API + 授权数 |
| 点开关 | 不崩，状态可保持，退出重进仍是新值 |
| B站详情页 | Tab 行只剩「简介」，无评论数，简介内容完整 |
| B站**快速**点开视频 | 评论 Tab 不闪 |
| B站往左滑 | 画面纹丝不动，日志打 `不让手势滑向评论页` |
| B站搜索页 | 视频卡片全部正常 |
| 抖音首页 | 视频正常播放，右下评论入口消失 |
| 抖音切换底部 Tab | 能正常切换（不被拦） |
| 快手极速版 | 评论入口消失 |
| 深链 | 不弹评论页 |

### 10.4 日志抓取

```bash
# 实时
adb logcat -s HideComments

# 文件（不用 root）
adb shell "cat /sdcard/Android/data/<包名>/files/hidecomments/module.log"
```

### 10.5 View 树导出

设置里开启后，进入目标页面停 2 秒，文件落在同一目录。每行：

```
类名  #资源ID名  [宽x高]  [vis=...]  [clickable]  [text="..."]  <== MATCH
```

---

## 11. 扩展指南

### 11.1 新增一个 App

1. **core**：`TargetApp.kt` 加枚举项

```kotlin
NEW_APP(
    id = "new_app",
    displayName = "新应用",
    packages = listOf("com.example.app"),
),
```

2. **打包**：`app/src/main/resources/META-INF/xposed/scope.list` 加包名

3. **Profile**：`hook/profiles/NewAppProfile.kt`

```kotlin
object NewAppProfile : AppProfile {
    override fun install(host: Host) {
        CoreHooks.install()
        DexIndex.ensureScanned(listOf("comment", "cmt", "reply")) { names ->
            DynamicHooks.installFromCandidates(host, names)
        }
    }
}
```

4. **分发**：`Host.profileFor()` 加分支

5. **验证**：先跑通用规则，再按 View 树导出补锚点

### 11.2 收紧或放宽识别

改 `ViewClassifier` 的三个表：

| 表 | 作用 | 调紧 → |
|---|---|---|
| `PANEL_ID_TOKENS` | 资源 ID 关键字 | 只留 `comment`，删 `cmt`/`reply` |
| `PANEL_CLASS_TOKENS` | 类名特征 | 只留最明确的那几个 |
| `ENTRY_TEXTS` | 入口文字 | 删掉容易撞的 |

**调紧的代价是可能盖不住，调松的代价是误伤。** 改完务必回归测试搜索页（最容易误伤的地方）和抖音主页（翻页器最容易误判的地方）。

### 11.3 处理 Compose 界面

Compose 整屏只有一个 `AndroidComposeView`，内部节点不是 View，View 树遍历无效。可选路径（由稳到脆）：

1. **数据层**：hook ViewModel / Repository，把目标条目从列表里剔除
2. **Adapter 层**：hook LazyListItemProvider 的取项方法
3. **语义树**：反射取 `SemanticsOwner`，按 `testTag` / text 匹配
4. **Composer 层**：hook `@Composable` 生成的 `$composer` 方法（耦合极重，不推荐）

本项目目前只做到「靠 dex 索引找 Fragment / ViewGroup 兜底」，Compose 内部还未覆盖。

### 11.4 移植到其他框架

本项目的 hook 逻辑用了 `io.github.libxposed.api` 的拦截器模型：

```kotlin
hook(method).setExceptionMode(PROTECTIVE).intercept { chain ->
    // ...
    chain.proceed()
}
```

要移植到老版 Xposed（`XC_MethodHook`），需要改写 `util/HookExt.kt` 的三个函数（`hookExec` / `hookAfter` / `hookBefore`），其余 engine 代码基本不变。`chain.getArg(i)` → `param.args[i]`，`chain.thisObject` → `param.thisObject`。

---

## 12. 性能与稳定性设计

### 12.1 稳定性三条铁律

1. **所有 hook 用 `PROTECTIVE` 异常模式** —— 模块出错的结果必须是「没屏蔽」，不是「目标 App 崩」。
2. **所有反射「找不到就返回 null」** —— 类改名是常态，不能因此抛异常。
3. **所有框架外的回调自己兜异常** —— `OnPreDrawListener`、`Handler` 里的 Runnable 都**不在**框架保护范围内。

### 12.2 性能措施

| 措施 | 目的 |
|---|---|
| 类名特征按 `Class` 缓存成位掩码 | `addView` 是最热方法之一，避免每次字符串扫描 |
| `quickCandidate()` 前置预筛 | 大部分控件在这里就被排除，不进入完整判定 |
| `setText` 先做「长度 + 关键词」预筛 | `setText` 极热，绝大多数调用被挡在门外 |
| 扫描节点上限 20000 | 防止在超深 View 树上卡住主线程 |
| `reassert()` 只遍历已隐藏的几十个 View | 每帧都跑，必须便宜 |
| dex 索引后台线程 + 关键字预过滤 | 27 万类只留 8650 个候选 |
| 动态 hook 上限 80 个 | 防止把方法内联打爆 |
| 周期扫描先 burst 后降频 | 兼顾启动期覆盖率和稳态开销 |

### 12.3 内存

- `hidden` 用 `WeakHashMap`，不阻止 View / Activity 被回收。
- `rootRef`、`guardedRoot` 用 `WeakReference`。
- `tabOwners`、`idNameCache`、`classMaskCache` 要么弱引用要么按 Class 缓存（Class 本身不会泄漏）。
- 日志缓冲上限 500 行。

### 12.4 线程模型

| 工作 | 线程 |
|---|---|
| View 树扫描 / 隐藏 / 重申 | **必须主线程**（View 操作） |
| dex 索引 | 后台线程 |
| 配置读取 | 调用方线程（带 2s 缓存） |
| 日志落盘 | 主线程 Handler，1.5s 一次 |
| `hidden` / `commentPages` / 各类缓存 | 全部加锁 |

---

## 附录：关键常量速查

```kotlin
// ViewShield
MAX_NODES            = 20000
SCAN_INTERVAL_MS     = 1000L      // 稳态扫描间隔
BURST_INTERVAL_MS    = 120L       // 启动期密集扫描
BURST_DURATION_MS    = 5000L      // 密集扫描持续

// ConfigProvider
RELOAD_INTERVAL_MS   = 2000L      // 配置重读间隔

// ViewDumper
MAX_NODES            = 4000
MAX_DEPTH            = 60
延迟                  = 1000L / 3000L
保留份数              = 3

// LogFile
INTERVAL_MS          = 1500L
最大行数              = 500

// DynamicHooks
MAX_HOOKS            = 80

// ViewClassifier
CONTAINER_HEIGHT_CEILING = 0.6f   // 容器高度上限（占根容器比例）
```
