# 架构与使用说明

> HideComments · 干掉评论区
> 一个 LSPosed 模块，屏蔽抖音 / 快手 / B站的评论区

本文档讲**两部分**：项目架构（代码怎么组织、屏蔽怎么实现）和使用方法（怎么装、怎么配、怎么排查）。
更细的原理推导、踩坑记录、每个函数的职责，见 [TECHNICAL.md](TECHNICAL.md)。

---

## 一、项目概览

| 项目 | 值 |
|---|---|
| 中文名 | 干掉评论区 |
| 英文名 | HideComments |
| 应用 ID | `com.hidecomments` |
| 模块类型 | LSPosed / Vector（Xposed）模块 |
| 实现语言 | Kotlin |
| 最低系统 | Android 8.0（API 26） |
| 验证环境 | OnePlus PLQ110 · Android 16（API 36）· LSPosed 2.2.1 |

### 覆盖的 App

| 产品 | 包名 |
|---|---|
| 抖音 | `com.ss.android.ugc.aweme` |
| 抖音极速版 | `com.ss.android.ugc.aweme.lite` |
| 快手 | `com.smile.gifmaker` |
| 快手极速版 | `com.kuaishou.nebula` |
| 哔哩哔哩 | `tv.danmaku.bili` |
| 哔哩哔哩 HD | `tv.danmaku.bilibilihd` |
| 哔哩哔哩播放版 | `tv.danmaku.biliplayerv2` |
| 哔哩哔哩概念版 | `com.bilibili.app.blue` |
| 哔哩哔哩国际版 | `com.bilibili.app.in` |

---

## 二、模块划分

工程是三个 Gradle 模块，职责严格分层：

```
干掉评论区/
├── core/     纯数据层，无 Android UI、无 hook
│   ├── HideConfig.kt     配置模型 + JSON 序列化
│   └── TargetApp.kt      包名 ↔ 产品映射
│
├── hook/     注入逻辑，只在目标 App 进程里跑
│   ├── HideCommentsModule.kt   模块入口，过滤进程
│   ├── Host.kt                 进程级宿主，分发到 Profile
│   ├── ConfigProvider.kt       跨进程读配置
│   ├── Logger.kt               日志分发
│   ├── engine/                 通用引擎（不认识具体 App）
│   │   ├── ViewClassifier.kt   View 识别
│   │   ├── ViewShield.kt       隐藏 / 兜底 / 恢复状态机
│   │   ├── CoreHooks.kt        全局 hook 挂载点
│   │   ├── PagerGuard.kt       翻页守卫
│   │   ├── DexIndex.kt         运行时类名索引
│   │   ├── DynamicHooks.kt     候选类 → 实际 hook
│   │   ├── NavigationGuard.kt  Intent 判定
│   │   ├── ViewDumper.kt       View 树导出
│   │   └── LogFile.kt          日志落盘
│   ├── profiles/               各 App 专属策略
│   │   ├── BilibiliProfile.kt
│   │   ├── DouyinProfile.kt
│   │   └── KuaishouProfile.kt
│   └── util/                   反射与 hook 辅助
│
└── app/      设置界面 + Xposed 打包入口
    ├── HideCommentsApp.kt       注册框架服务监听
    ├── FrameworkRepository.kt   跨进程读写配置
    ├── BaseSettingsActivity.kt  设置页公共底座
    ├── MainActivity.kt          主页
    ├── SettingsActivity.kt      二级页
    ├── CrashDisplay.kt          崩溃兜底显示
    └── src/main/resources/META-INF/xposed/
        ├── java_init.list       入口类
        ├── module.prop          模块元数据
        └── scope.list           作用域包名
```

### 依赖方向

```
app ──compile──> core
 │
 └──runtimeOnly──> hook ──compileOnly──> core
                          └compileOnly──> libxposed:api
```

两个关键设计：

- **hook 是 `runtimeOnly`**：hook 代码必须打进 APK（框架运行时加载），但设置界面**不允许在编译期引用它**——两者运行在完全不同的进程里，编译期引用只会让人误以为可以直接调用。
- **hook 对 core 是 `compileOnly`**：core 只有数据类，编译期依赖即可。而 `libxposed:api` 必须是 `compileOnly`——它由框架在运行时注入，打进 APK 反而会冲突。

---

## 三、屏蔽是怎么实现的

评论区不是一块静态区域，而是一条链：**入口按钮 → 面板容器 → 数据层**。任何一层单堵都会被绕过，所以做了五道防线。

```
① 文字层    TextView.setText        「评论」两个字根本不让设进去
② 容器层    Tab / 面板整体 GONE      整个 Tab 项和评论区面板一起消失
③ 手势层    onInterceptTouchEvent   手指滑不到评论页
④ 跳转层    performClick / startActivity  点了没反应、深链进不去
⑤ 兜底层    View 树扫描 + 每帧重申   动态插入 / 复用 / 重绘都能盖住
```

### ① 文字层：`TextView.setText`

宿主的典型时序是「先 `addView` 插控件，之后才 `setText` 设文字」。在 `addView` 那一刻读文字，读到的是空字符串，判定必然落空——这就是「评论按钮闪一下」的来源。

处理方式：hook `TextView.setText`，当文字**整体等于**「评论 / 回复 / Comments」时**直接拦下原方法**，文字根本不进入控件。

- 精确匹配不会误伤：正文里的「评论」是标题或段落的一部分，那个 `TextView` 的 text 是整段文字，不等于「评论」两个字。
- 表里还有一组「面板种子文案」（`热门评论`、`最新评论`、`全部评论`…），命中它们说明评论区已经在构建了。

### ② 容器层：整个 Tab 项 / 面板

只藏文字会在标签栏里留下一个几十像素的空位，旁边的评论数（如「126」）也会露出来。所以还要往上找容器：

- **入口**：`entryContainers()` 从文字往上爬，遇到 `TabLayout$TabView` 就停下并把它一起藏掉。
- **面板**：`panelContainer()` 从「热门评论」这类种子往上爬，找到真正的面板容器（B站是 `#comment_container`）。
- **列表项红线**：`isListItem()` —— RecyclerView 的直接子节点代表「一条内容」（搜索结果里的一个视频、评论列表里的一条评论），凡是往上找容器的逻辑碰到它一律刹住。这条线是防「搜索页视频全被藏」的关键。

### ③ 手势层：`onInterceptTouchEvent`

这是整套机制里最关键的一环。

翻页器（ViewPager）内部是一个 RecyclerView，**手势是直接拖这个 RecyclerView 的**，拖到一定程度它才通知翻页器「该切页了」。所以：

| 做法 | 结果 |
|---|---|
| 只拦「切页」 | RecyclerView 已经被拖到半路，画面歪着，怎么回滚都歪 |
| 放行再弹回 | 翻过去再翻回来，宿主的简介页被回收重建，内容没了 |
| **拦触摸分发** | 翻页器不接管手势，RecyclerView 一个像素都没动，两边状态始终一致 |

具体做法：滑向评论页时 `onInterceptTouchEvent` 返回 `false`，翻页器不接管，手势落回下面那个竖向列表，等于什么都没发生。

### ④ 跳转层

- `View.performClick`：分享链接、搜索结果等入口点了不响应。
- `ContextWrapper.startActivity`：评论页如果是独立 Activity，直接拦下。
- `PagerGuard`：翻页器的 `setCurrentItem` 公开入口直接拦。

### ⑤ 兜底层

- **周期扫描**：界面刚起来的前 5 秒每 120ms 扫一次，之后每秒一次。
- **绘制前重申**：`OnPreDrawListener` 里把被 App 重新显示出来的 View 再压回 GONE。这是「隐藏后隔一秒又冒出来」的解法。
- **插即隐**：hook `ViewGroup.addView`，新 View 一进来就判定。
- **dex 动态发现**：运行时枚举 APK 里的类名，找出名字带 `comment` / `cmt` / `reply` 的 ViewGroup 和 Fragment，挂构造 hook。

---

## 四、两个关键机制

### 页码只能从标签栏推

翻页守卫需要知道「第几页是评论页」。有两条路，只有一条可信：

| 推断方式 | 可靠性 |
|---|---|
| ❌ 评论面板恰好在某个翻页器的某一页里 | **不可靠**。抖音的主界面（首页/朋友/消息/我的）本身就是 ViewPager2，评论面板挂在首页那一页里——照此推断会把第 0 页当成评论页，**整个主界面切换被拦死** |
| ✅ 标签栏里明确写着「评论」的那个 Tab 的下标 | 可靠。TabLayout 的第 N 个标签对应翻页器的第 N 页 |

所以 `PagerGuard.noteCommentTab()` 只从 Tab 下标取页码。`noteCommentPage()` 保留但**只做诊断，不据此认定评论页**。

### 按签名找 hook 点，不按名字

**B站把 androidx 也一起混淆了。** 实测 `androidx.viewpager.widget.ViewPager` 的 73 个方法里，页面切换那几条被改名成 `A (IZ)V` / `B (IIZZ)V` / `j (I)V`，`setCurrentItemInternal` 这种名字**一个都不存在**。

因此 `PagerGuard` 按签名找：只认「这个类自己声明的、第一个参数是 int、返回 void」的方法，hook 体里再用页码范围二次确认。

---

## 五、设置界面

### 页面结构

| 页面 | 内容 |
|---|---|
| 主页 | 总开关、四个 App 的开关与「本次放行」、更多设置入口、一行框架状态 |
| 二级页 | 屏蔽内容（入口/面板/跳转）、识别强度、高级（详细日志/导出 View 树）、框架状态、路径速查 |

### 配置怎么跨进程

```
设置 App 进程                         目标 App 进程
──────────────                       ──────────────
FrameworkRepository.save()
    │ XposedService（AIDL）
    ▼
框架的 remote preferences  ────────>  ConfigProvider.get()
（LSPosed 的 SQLite）                  每 2 秒重新读一次
                                       │
                                       ▼
                                   ViewClassifier / ViewShield
```

- 配置整体序列化成**一个 JSON 字符串**，只占一个 key，避免半更新状态。
- hook 侧拿到的 `RemotePreferences` 是**只读**的（框架有意为之）。
- 改了设置不需要重启 App，2 秒内自动生效。

### 视觉规范

界面按 **MIUI / HyperOS** 的视觉语言做的：主色 `#3482FF`、纯白卡片零描边零投影、18dp 大圆角、组内细线左缩进对齐文字、偏冷浅灰背景，深色模式另有一套值。

> **为什么没用 MiuiX 库**：MiuiX（`compose-miuix-ui/miuix`）是纯 Compose 库，且 0.9.x **所有版本都要求 `compileSdk 37`**——而 android-37 目前只在 Google 的 canary 通道里，普通 SDK Manager 装不了。为一个设置界面要求用户装 canary 平台不划算，所以用 Material3 + MIUI 设计变量实现同样的视觉。

---

## 六、使用方法

### 1. 环境要求

| 项目 | 要求 |
|---|---|
| 手机 | 已 root（Magisk / KernelSU / APATCH） |
| 注入框架 | 支持 **libxposed API 101+**（LSPosed 2.2.1 / Vector 2.1+ 实测可用） |
| Zygisk | 需启用（NeoZygisk / Zygisk Next） |
| 系统 | Android 8.0+ |

**为什么用 API 101 而不是 102**：`libxposed:api:102.0.0` 的 AAR 元数据要求 `compileSdk 37`，同样受 canary 平台限制。101 只要求 36（稳定版），在 LSPosed 2.2.1 上行为一致，代价只是失去 `setId` / `detach` / 热重载——对本项目无实质影响。

### 2. 构建

```bash
# 调试包（免签名，直接能装）
./gradlew :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk

# 发布包（需在根目录放 keystore.properties）
cp keystore.properties.example keystore.properties
# 编辑后填入你自己的签名信息
./gradlew :app:assembleRelease
```

构建机需要 JDK 17+、Android SDK 36。`local.properties` 里配 `sdk.dir`。

### 3. 安装

1. 安装 APK
2. 打开 **LSPosed / Vector 管理器** → 模块 → 勾选「干掉评论区」
3. **作用域**里勾选目标 App（默认已预置全部 9 个包名，一般不用改）
4. **强制停止**目标 App，重新打开
5. 打开「干掉评论区」，顶部状态条应显示 `LSPosed 2.2.1 · API 101 · 已授权 4/4`

### 4. 配置

| 开关 | 作用 |
|---|---|
| 总开关 | 全局启用/停用 |
| App 开关 | 按产品单独开关 |
| 本次放行 | 临时恢复当前 App，退出后失效 |
| 隐藏入口按钮 | 不显示评论区入口 |
| 隐藏评论区面板 | 不显示评论区内容 |
| 拦截跳转 | 不响应跳评论页的点击/深链 |
| 识别强度 | 保守 / 标准 / 激进（见下） |

**识别强度**：

| 档位 | 判定依据 | 误伤风险 |
|---|---|---|
| 保守 | 只认资源 ID 名和类名里的 `comment`/`reply`/`cmt` | 最低 |
| 标准（默认） | 上面 + 文字精确匹配 | 低 |
| 激进 | 上面 + 按尺寸位置猜测容器 | 最高，可能误伤大列表 |

### 5. 排查

**日志文件**（手机文件管理器直接打开，不需要 root）：

```
/sdcard/Android/data/<包名>/files/hidecomments/
├── module.log          运行日志
└── viewtree-*.txt      View 树（需在设置里开启）
```

设置界面的二级页里有「显示路径」，列出了每个 App 的完整路径，可长按复制。

**View 树导出**：开启后，进入目标 App 的页面停两秒，会生成带尺寸的完整 View 树。每行格式：

```
类名  #资源ID名  [宽x高]  文字  <== MATCH
```

带 `MATCH` 的是模块认出来的地方。这是定位混淆后真实控件名最快的办法。

**日志里的关键行**：

| 日志 | 含义 |
|---|---|
| `==== HideComments 配置已生效 ====` | 设置成功传进宿主进程 |
| `拦下评论文字「评论」` | 文字层生效 |
| `隐藏评论 Tab：...` | 容器层生效 |
| `学到：评论 Tab 在 UnitedViewPager 的第 1 页` | 页码学习成功 |
| `不让手势滑向评论页` | 手势层生效 |
| `hidden[panel] ...` | 实际藏掉了哪个控件 |

---

## 七、已知限制

| 限制 | 说明 |
|---|---|
| Compose 界面 | 抖音部分新界面用 Compose，View 树遍历无效，只能靠 dex 索引在 Fragment 层兜，覆盖率不确定 |
| 类名漂移 | 硬编码的锚点类名（B站 comment3 等）被上游改名后失效，但失效只是少一层保险 |
| App 外 | 只在 App 进程内做 View 层屏蔽，通知栏、系统分享面板里的评论摘要不在范围 |
| Root 检测 | 不处理绕过检测，需 Shamiko 等方案配合 |
| 国内版未实测 | 实机验证的是 B站**国际版**；国内版结构大概率相同但未验证 |
| 快手正式版未实测 | 实机验证的是快手**极速版**（`com.kuaishou.nebula`） |

---

## 八、开发注意

### 工具链

| 组件 | 版本 |
|---|---|
| Kotlin | 2.4.21 |
| AGP | 8.7.3 |
| Gradle | 8.14.4 |
| compileSdk | 36 |
| minSdk | 26 |
| targetSdk | 34 |

### 三个必须知道的坑

**一、中文路径。** 项目名是中文，必须两处配置（已写进 `gradle.properties`）：

```properties
android.overridePathCheck=true
kotlin.compiler.execution.strategy=in-process
```

第二条更关键：Kotlin 编译守护进程是独立子进程，它把中文路径转义后传给 javac，javac 会报 `source file or directory not found: G:\project\u5220...`。改成进程内编译就没有跨进程的路径转义问题。

**二、`kotlinOptions` 已废弃。** Kotlin 2.4 把 `kotlinOptions { jvmTarget = "17" }` 变成**错误**，必须写：

```kotlin
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
```

**三、`CompoundButton` 的监听顺序。** `performClick()` 是**先跑监听器再 `toggle()`**，所以在 `setOnClickListener` 里读 `isChecked` 拿到的是**旧值**。开关一律用 `setOnCheckedChangeListener`（直接拿到新值）。

### 加一个新 App

1. `core/TargetApp.kt` 里加枚举项（id / 显示名 / 包名列表）
2. `app/src/main/resources/META-INF/xposed/scope.list` 里加包名
3. `hook/profiles/` 下新建 Profile（大部分情况只需 `CoreHooks.install()` + `DexIndex`）
4. `host/Host.kt` 的 `profileFor()` 里分发
