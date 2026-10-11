# dsh-mobile-layout

> **v5（2026-10-11）**：加了 canary 与 composer 触控/字号下限，并**收回仓库**
> （见文末「为什么它现在住在仓库里」）。

DSH WebUI **always-mobile 抽屉布局**插件（纯客户端 overlay）。

任何视口宽度都运行移动布局——不依赖官方源码修改，`dsh` 升级不覆盖：

- 三列网格锁定为 `0 / 1fr / 0`：聊天区永远占满整个可用宽度
- 侧栏收起时完全隐藏，左下角悬浮 **☰ FAB**（44px 圆钮，位于输入框上方）
- 点 ☰ → 官方侧栏以 **fixed 280px 抽屉**从左侧滑入 + 半透明遮罩（点击遮罩/抽屉顶部开关关闭）
- 输入卡胶囊圆角收紧；`viewport-fit=cover` 安全区适配（刘海屏 / Home 条）

## 实现方式

客户端 cordis 插件（worker `lib/client.js` 通过 `__ModuleLoader__` 注册），
零框架依赖、零官方源码改动：

1. 等待 shell 挂载（`[data-shell-overlay]`），给 frame 打类 `dshml-frame`
2. CSS 以 `!important` 覆盖官方 inline 网格与侧栏列（稳定 DOM 锚点：
   `[data-shell-overlay]` / `[data-sidebar-collapsed]` / sidebar toggle 按钮 aria-label）
3. 注入 FAB 与遮罩（44px 圆钮 + 半透明遮罩），点击只切插件内部状态
4. **纯视觉接管（v3, 2026-09-11）**：抽屉开合 = 插件内部状态（FAB/遮罩点击切换），
   **完全不读不写官方侧栏状态**；250ms reconciler 轮询维护 frame 标记与列类（remount 自愈）。
   —— v1/v2 曾「自动点击官方折叠按钮 + 镜像 data-sidebar-collapsed」：真实环境下
   合成点击可能丢失（React 事件未就绪等），官方保持展开而 CSS 已锁 0 →
   用户既没有侧栏也没有 FAB（矛盾态，实测报告）。v3 移除全部官方状态依赖：
   列始终打 `dshml-sidebar` 类（关闭隐藏 / 打开为 280px fixed 抽屉）；
   宽屏下官方本就渲染展开列内容，抽屉直接展示；窄屏 best-effort 点一次官方展开
   让列内容就绪（失败仅意味着小屏抽屉为空，其余功能不受影响）
5. **grid 轨道钉死（v3.2, 2026-09-11）**：关闭态用 `visibility: hidden`（而非
   `display: none`）隐藏侧栏列 —— 保留其 grid 占格；并给三列显式
   `grid-column: 1/2/3`。修复「Details 页泄漏」：侧栏列 display:none 后
   自动放置使 center/details 前移一格，详情列吃到全宽格，「官方收起状态的
   Details 面板」被整页展示（用户实测报告）

## 安装

```bash
cd ~/.dsh/profiles/web
pnpm add file:/home/yuchen_wang/workspace/dsh-mobile-layout
```

组合包层（`dsh.bundle.patch` → `cordis.patch.yml`）会自动插入插件行，
无需手改 patch。然后重启 DSH 生效：

```bash
~/start_dsh.sh stop && ~/start_dsh.sh start
```

验证：任意浏览器打开 WebUI，刷新后应看到聊天全屏 + 左下角 ☰。

## 回滚

```bash
cd ~/.dsh/profiles/web
pnpm remove dsh-mobile-layout
~/start_dsh.sh stop && ~/start_dsh.sh start
```


---

## v5 新增（2026-10-11）

对照四个社区移动端插件后只补了**纯增量**的部分 —— v4 是实测可用的，
一个表现层插件不该为了更好看去冒回归的风险。

### 1. canary：官方 DOM 漂移时自我卸载

v1…v4 **每一次**都是被官方改版搞坏的，而每一次都是**人眼发现界面坏了**才修的：

| 版本 | 被什么搞坏 |
|---|---|
| v1/v2 | 合成点击丢失 → 官方保持展开而 CSS 已锁 0 宽（既没侧栏也没 FAB） |
| v3.2 | 第一个 grid item 消失导致串轨 → Details 页整页泄漏 |
| v4 | 官方 0.1.2 改 locale（打开侧栏→打开侧边栏）；0.2.0 右侧栏改 absolute |

canary 让插件自己发现：**先健康过一轮**，之后若连续 40 次 reconcile（约 10 秒）
三列锚点全部丢失，就判定官方 DOM 已经变了 → **移除自己注入的 CSS 与 DOM，
交还官方原生布局**。

> 判据是 **fail-safe 而不是 fail-broken**：针对一个不存在的 DOM 写的 CSS
> 不是「部分可用」，而是有害的。

两个刻意的设计：
- **冷启动不计数**：shell 挂载了但列还没渲染完是正常的，此时触发会让每次启动都误报
- **短暂抖动不触发**：有一次布局闪断就自杀是灾难；连续 10 秒才算漂移

### 2. composer 触控与字号下限（借鉴 dsh-webui-mobile / dsh-mobile-theme）

- 输入框字号 **≥16px**：低于此值时移动端 WebView 会在聚焦时自动缩放，而且
  这个缩放**不一定会退回去** —— 结果整页永久被放大
- 触控目标 **≥44px**（WCAG 2.5.5）：只作用于 composer 行与抽屉，
  **不做全局规则** —— 那会重排我们并不拥有的官方行
- composer 底部 safe-area（全面屏手势条会压在上面）
- 抽屉滚动链隔离（`overscroll-behavior: contain`）

### 3. 为什么保留「always-mobile」

社区插件多数是「≤1023px 生效、桌面零影响」。本插件**任何视口都跑移动布局**，
这是刻意的：Android WebView 的视口宽度会随横屏、平板、分屏、系统字体缩放而变，
一旦越过断点就会退回桌面布局 —— 而用户明明在手机上。
always-mobile 让「手机上永远是移动布局」成为一个不依赖视口的事实。

### 4. 为什么它现在住在仓库里

它原本以 `pnpm add file:/home/yuchen_wang/workspace/dsh-mobile-layout` 安装，
而**那个源目录已经不存在了** —— 整个插件一度只剩 profile 的 `node_modules` 里那一份。
现在它随 APK 发货（`app/src/main/assets/plugins/dsh-mobile-layout/`），
首启装配时装进 profile 并注册进 `dsh.profile.bundles`。

`history/` 里留着 v3/v4 时期的 `.bak`，是当时实测的中间产物。
