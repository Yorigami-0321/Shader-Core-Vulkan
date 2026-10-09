# 01 · 开发测试流程

> **读者：你，执行开发的 AI。**
> 本文告诉你每一次改动之后必须做什么。按顺序做，不要跳步，不要提前下结论。
>
> 配套：`07-CONSTRAINTS.md`（红线，不可违反）、`05-VERSION.md`（版本，不可漂）、
> `08-TESTING.md`（每个阶段的验收细则）、`13-GAP-REGISTRY.md`（要自行补充特性时的登记表）、
> `17-NATIVE.md`（性能预算 + 参考先行 + 原生加速决策树）。

---

## 0. 核心循环（记住这一句就够了）

```
改代码 → 构建 → 用真实产物跑 runClient → 看真实输出 → 按错误修 → 再构建再跑
                                                     ↑                    │
                                                     └────────────────────┘
                                                    直到本次目标达成，再进下一个任务
```

**没跑过 `runClient` 的改动，一律视为未完成。** 编译通过不等于功能正确。
本项目所有关键功能都发生在运行时（渲染管线注册、着色器编译、pass 执行），
**只有把游戏真的跑起来、看到真实产物，才知道做没做对。**

---

## 1. 开工前的固定动作

```
1. 读 05-VERSION.md          → 确认 gradle.properties 的版本号与它一致，没漂
2. 读 07-CONSTRAINTS.md §四   → 把本次会触碰的红线抄进你的任务笔记
3. 读 13-GAP-REGISTRY.md      → 判断本次改动是否需要新增缺口登记（要就先登记再写代码）
4. 【参考先行】本轮要动的那部分，找到参考了吗？
      去 17-NATIVE.md §1.3 的默认参考清单里查
      🔴 先做合规核对（17-NATIVE.md §1.1.1）——这是第 0 步，不是最后一步：
         · 查参考项目的 LICENSE 文件（只信仓库里的文件，不信平台页面）
         · 判「能否并入本项目 MIT」→ 不能就换参考，别再读它的代码
         · 无 LICENSE 文件 = ARR = 不可用；「GPL + 例外条款」= 按禁止处理
      然后写下：【参考调研】注释块第 0 条（合规）+ 1/2/3/4/5 条
      没写或第 0 条写「未核实」→ 不许动手（07 T13）
5. 【性能定位】本轮改动落在热路径还是冷路径？（17-NATIVE.md §3.2）
      冷路径 → 清晰优先，不要做性能优化
      热路径 → 先测出基线（17-NATIVE.md §7）
6. git status                → 确认工作区干净；不干净先提交或说明
7. 确认 JDK 在 PATH 上        → java -version 输出的版本要与 05-VERSION.md 一致
```

第 7 步的检查命令：

```bash
java -version          # 版本号必须与 05-VERSION.md 的 Java 版本一致
echo $JAVA_HOME
```

### 1.1 本机环境前提：代理根证书必须导入 JDK 信任库

本机通过 `127.0.0.1:12334`（Steamcommunity302 加速代理）出网，该代理对
`github.com` / `release-assets.githubusercontent.com` 等域名做 **TLS 中间人**，
出示自签证书。Windows 证书存储信任它（浏览器正常），但 **Java 不使用 Windows 存储**，
只读 `$JAVA_HOME/lib/security/cacerts`。

不修的话，`./gradlew` 会在**下载 Gradle 发行包**这一步就死掉——因为
`services.gradle.org` 会把请求 307 重定向到 GitHub Release：

```
Downloading https://services.gradle.org/distributions/gradle-9.4.1-bin.zip
javax.net.ssl.SSLHandshakeException: PKIX path building failed:
  unable to find valid certification path to requested target
```

**修复（幂等，可反复执行）：**

```bash
fix-java-proxy-ca            # 从 Windows 存储导出代理根证书 → 导入所有 JDK 信任库
fix-java-proxy-ca --dry-run  # 只看会做什么
```

**什么时候要重跑**：代理工具轮换根证书（如新增 `Steamcommunity302 - 20XX ECC Root`）、
JDK 升级/重装覆盖了 `cacerts`、或构建再次报 `PKIX` / `SSLHandshakeException`。

**注意**：导入后该代理 CA 可对本 JDK 做中间人，这是使用加速代理的固有代价；
不要把它提交进仓库。

**JDK 位置**：`~/jdk/jdk-25.0.4.1+1`（已写入 `~/.bashrc`，并在 `~/.local/bin` 建了
`java`/`javac`/`keytool` 软链接，保证非交互 shell 也能找到）。

**不要**在 `gradle.properties` 里加
`-Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts`：系统信任库**不含**该代理 CA，
反而会让 Gradle daemon 的依赖下载失败。

> ⚠️ **已发生过一次（2026-09-29）**：P0.1 期间有人为"修 CA 问题"顺手往
> `gradle.properties` 的 `org.gradle.jvmargs` 里加了这一行，被代码评审拦下后撤回。
> **正确做法永远是 `fix-java-proxy-ca`（导入 JDK 信任库），不是改 Gradle 配置。**
> 这个改动还会污染仓库 —— 代理 CA 的信任路径是本机特有的，提交上去对别人只有害处。

### 1.2 🔴 取证必须走 `run-client.sh`，不要裸跑 `./gradlew runClient`（2026-10-05）

> **背景（实测，`h36`）**：本机（WSL2）**系统级没有 Vulkan ICD**。
> 而 `runClient` 带了 `--graphicsBackend VULKAN` 时，Minecraft 在 loader 缺失时
> **不会崩也不会退出** —— 它只打两行
> ```
> WARN  NativeLibrariesBootstrap: Failed to load Vulkan loader
> ERROR Minecraft: Failed to create backend Vulkan
> ```
> 然后 `Using graphics backend OpenGL, using drivers: 4.6 …` **静默退回 OpenGL 继续跑满取证帧数**。
> ⇒ 采到的帧与截图**全是 OpenGL 产物**，而日志里除那两行外一切正常
> ⇒ `h33` / `h34` / `h35` **三轮取证都踩在这条静默降级上**（`P0.2` 每轮断言失败就是唯一的征兆）。

**正确入口**：

```bash
bash tools/vulkan-local/preflight.sh        # 自检：Vulkan 可用 → 退出码 0
bash tools/vulkan-local/run-client.sh -PquickPlay   # 主车道；会硬失败而不是静默降级
bash tools/vulkan-local/run-client.sh iso -PquickPlay  # 隔离车道（run/h27）
```

`run-client.sh` 做四件裸跑不会做的事：① 启动前 preflight 硬失败；
② 查残留客户端（两会话共享 `run/` 会互相顶掉，见 `build.gradle` 隔离车道注释②）；
③ 接上 prefix 环境（免 root loader + lavapipe ICD）；④ 起跑后**断言后端**。

**P0.2 当前状态（2026-10-05）**：✅ **已达成**
（`vkdisp: backend=Vulkan, device=llvmpipe (LLVM 23.1.8, 256 bits)`，证据 `evidence/h36-…`）。
⚠️ 设备是 **lavapipe（CPU 软件 Vulkan）**，不是独显 ⇒
**Vulkan 语义是真的，但帧率不代表任何真实硬件**（支柱③ B1–B7 仍无结论）。

### 1.3 🔖 像素级判据走 `mrt.pixelProbe`，不必再手动截图 + 跑脚本（2026-10-05）

h22～h42 取数字的办法是「MCP 截图 + 外部 python 脚本」，它有三个实测缺陷
（详见 `evidence/h43-…` §二）：需要人在运行之外再跑脚本、采样区写死、
**数字不在日志里**（跨会话的读者是 AI，读不到截图里没有的数字）。

现已内置进程内回读：

```toml
[mrt]
  pixelProbe = true        # 默认关（GPU→CPU 拷贝 + 内存映射，不是每帧可做的事）
  pixelProbeEvery = 300    # 节流间隔帧
```

开启后每 N 帧在日志里给两行（**主目标**与**当前 colortex 槽**各一行），
外加一条**只在结论变化时**报的四分判定：

| 结论 | 含义 |
|---|---|
| `NOT_ON_MAIN` | colortex 有内容、主目标全黑 ⇒ 地形 draw **没落到主目标**（落点/接线问题） |
| `SLOT_BLACK` | 主目标有内容、colortex 全黑 ⇒ **我方 pass 写进 colortex 的地形是黑的**（GAP-008 本体） |
| `BOTH_BLACK` | 两者都全黑 |
| `BOTH_HAVE_CONTENT` | 两者都有内容 ⇒ 主目标链路正常 |

🔖 **统计口径与历史证据逐字一致**（采样区中心 `x∈[45%,65%]/y∈[15%,75%]`、黑阈值 8、
Rec.709 luma，取自 `evidence/tools/flicker_ratio.py` 的 h22 校准结果），
且采样区**按比例**换算 ⇒ 换窗口尺寸后仍与 h22/h31 的数字可比。
外部脚本仍可用来复核（`python3 tools/vulkan-local/flicker_ratio.py <png>`）。

⚠️ **三处位置/口径约束**（都是实测踩出来的，守卫 `PixelProbeWiringTest` 钉住）：
① 必须在所有 render pass 关闭之后；② 必须在**地形 MRT pass 之后**
（否则 `terrainToMain=true` 档读到的是写入前的内容）；③ 必须在诊断 blit 之前。
⚠️ `terrainToMain=true` 档下附件 0 已被换成主目标视图 ⇒ `colortex0` **不是附件**，
该档的对照槽由代码自动改选，只有一个附件时**明确不产出两源对照结论**。

---

## 2. 每次改动的执行顺序

```
① 先改文档（如果本次改动影响组件/接口/约束 —— 见 15-ITERATION.md 第 2 步）
② git commit 当前可用状态（改坏能退）
③ 改代码
④ 构建：./gradlew build
⑤ 检查构建产物（§3）
⑥ 跑真实产物：bash tools/vulkan-local/run-client.sh（§1.2 / §4）
⑦ 观察真实输出：日志 + 画面（§5）
⑧ 有错误 → 定位（§6）→ 回到 ③
⑨ 全部通过 → 交证据（§7）→ 写变更记录 → commit
⑩ 进下一个任务，回到 ①
```

**禁止**：跳过 ④ 直接说"改完了"；跳过 ⑥⑦ 直接说"应该能跑"。

---

## 3. 构建与产物检查

### 3.1 构建

```bash
cd <项目根>
./gradlew build
echo "exit=$?"
```

**退出码必须是 0。** 不是 0 就不要往下走，先修编译错误。

### 3.2 检查产物（不是看"构建成功"四个字）

```bash
JAR=$(ls build/libs/*.jar | head -1)
echo "jar: $JAR"
unzip -l "$JAR"
unzip -l "$JAR" | grep -c '\.class'        # 必须 > 0
```

**必须核对 jar 内容：**

```
[ ] .class 数量 > 0（本项目早期踩过"jar 里一个 class 都没有"的坑）
[ ] 含 META-INF/neoforge.mods.toml，且 modId 与 gradle.properties 的 mod_id 一致
[ ] 含 LICENSE（本项目是 MIT，必须随分发）
[ ] 不含 net/minecraft/**
[ ] 不含 com/mojang/**
[ ] 不含 net/caffeinemc/**、dev/vitrail/**
```

一条不满足 → 先修产物，不要往下走。

---

## 4. 跑真实产物（本流程最关键的一步）

**这是唯一能证明代码真的生效的手段。** 编译通过、单测通过，都不能替代这一步。

### 4.1 起客户端

```bash
./gradlew runClient
```

首次运行会下载 Minecraft + NeoForge 依赖，**耗时较长，属正常**。让它跑完。

### 4.2 起客户端前先做一件事：打开诊断日志

查 `src/main/java/dev/vkdisp/VkDispConfig.java`，把 `debugLog` 默认为 `true`
（或起游戏后在配置界面打开）。

**没有日志的 runClient 等于白跑**——你无法判断代码是否被走到。

### 4.3 你要在游戏里做的动作

按当前任务的目标来，但**至少包含**：

```
[ ] 启动到主菜单，不崩
[ ] 进入一个世界（超平坦最快）
[ ] 走到能看见天空、地形、水的位置
[ ] 若本次改动涉及渲染，截图
[ ] 退出世界（检查资源释放路径）
[ ] F3+T 重载资源（检查管线重建路径）
```

### 4.4 收集输出

runClient 的日志默认在：

```
run/logs/latest.log          ← 主要看这个
run/logs/debug.log           ← debug 级
```

先 grep 你自己的前缀和所有错误：

```bash
grep -n "vkdisp:" run/logs/latest.log | head -50
grep -nE "ERROR|Exception|Mixin apply failed|validation error" run/logs/latest.log | head -50
```

---

## 5. 怎么判断"做对了"

**三重证据，缺一不可：**

| 证据 | 怎么看 | 不合格的样子 |
|---|---|---|
| **日志走到过** | 你埋的每条诊断日志都出现了 | 只有"启动成功"，没有任何自己的日志 |
| **计数对得上** | 注册数 == 编译成功数；扫描到 N 个 → 实际处理 N 个 | 数字对不上但没报错 |
| **画面/行为真的变了** | 截图对比，或行为可观测 | "看起来差不多" |

### 5.1 埋点要求（不满足就不算做完）

| 埋点 | 位置 | 必须输出什么 |
|---|---|---|
| 注入点 | 每个 mixin 注入方法体第一行 | `vkdisp: [注入点名] hit` |
| 注册点 | 管线 / 资源注册处 | 注册计数 |
| 编译点 | 每次编译尝试 | 成功计数；失败时**打印错误原文** |
| 降级点 | 任何 fallback 分支 | `WARN` + 走这条分支的原因 |
| 自行补充 | `platform/` 内的补充路径 | `vkdisp: [GAP-xxx] using self-supplemented path` |

**自检标准**：把日志从头读到尾，你应该能**不看画面**就说出
"哪些环节走到了、哪些没走到、哪一步数字对不上"。

做不到 → 回去补埋点，补完再重新跑 runClient。

> 为什么这么严：本项目最大的风险不是"报错"，而是**静默失败**——
> mixin 没生效时游戏不报错，只是"什么都没发生"。
> 见 `07-CONSTRAINTS.md` T10 / T11 / X11。

---

## 6. 出错了怎么定位（严格按序，不许跳）

```
第 1 步  先看日志原文
         grep -nE "ERROR|Exception|Caused by|Mixin apply failed|validation" run/logs/latest.log
         → 有异常就顺着 Caused by 读到底，不要只看第一行

第 2 步  看你自己的埋点在不在
         不在 → 代码根本没被走到
              → mixin 是否被加载？（mixins.json 的 compatibilityLevel 必须是 JAVA_25）
              → mixins.json 是否在 neoforge.mods.toml 的 [[mixins]] 里声明了？
              → 注入点方法签名是否还匹配？（用 javap 核对）
              → mod 是否真的被加载？（看日志里的 mod 列表）

第 3 步  计数对不对？
         注册数 != 编译成功数 → 逐个查编译失败的 program，看编译器给出的错误原文
         扫描数 != 处理数     → 查过滤条件，不要猜

第 4 步  编译成功、代码也走到了，但画面没变
         → 目标对象是同一个实例吗？（你改的和游戏用的是不是同一个）
         → 是不是画到了错误的渲染目标？
         → pass 顺序对吗？（后写的覆盖了前面的？）
         → 深度 / 裁剪状态对吗？

第 5 步  画面变了但不对（错位 / 彩色尖刺 / 全黑 / 全白）
         → 顶点 stride 是否与管线声明一致？（07 T9）
         → 纹理格式对吗？
         → 坐标系 / 深度范围对吗？
         → UV / attribute 命名与着色器声明是否字面一致？

第 6 步  只有特定场景错
         → 资源重载路径（F3+T）清理干净了吗？
         → 状态恢复了吗？（改过的渲染状态有没有还原）
         → 切包 / 切世界的残留？

第 7 步  **画面全对，但帧率不达标**
         → 先测：是哪个环节？（17-NATIVE.md §7.2 的手段）
         → 该环节是热路径吗？（§3.2 分级表）
             冷路径 → 不用优化，问题在别处（大概率是重复计算 / 缓存没生效）
             热路径 → 继续往下
         → 有没有重复计算？（同一帧内算了几次同样的东西）
         → 有没有不必要的分配？（每帧 new 对象 → GC 压力）
         → 有没有走原生库？原生库真的被选中了吗？（日志应打印所选后端）
         → 最后才考虑：上原生（必须先走 17-NATIVE.md §5 六问）
```

### 6.1 定位纪律

```
禁止 在没定位到根因前就"换个写法试试"
禁止 一次改多个不相关的点（你会不知道是哪处生效）
禁止 用 try/catch 吞掉异常让它"看起来能跑"（07 X11）
禁止 用 System.out 代替诊断日志
```

**改一处 → 重新构建 → 重新 runClient。** 一次只验证一个假设。

---

## 7. 交付证据（交任务时必须贴全）

```
[ ] git commit hash
[ ] ./gradlew build 的退出码
[ ] jar 路径 + class 数 + §3.2 六项核对结果
[ ] runClient 的启动结果（进到哪一步：主菜单 / 世界 / 可交互）
[ ] 相关日志片段（必须含 §5.1 的埋点输出，不能只贴"启动成功"）
[ ] 视觉证据：截图路径（无视觉变化就明写"本次无视觉变化"）
[ ] 08-TESTING.md 里对应阶段的验收项逐条勾选
[ ] 【参考调研】注释块内容（07 T13）
[ ] 性能相关：实测数据 + 是否在 17-NATIVE.md §2 预算内（07 T14）
[ ] 本次改动的文件清单
[ ] 未解决 / 存疑的问题（没有就写"无"）
```

**以下措辞一律拒收：** "已完成"、"应该好了"、"看起来正常"、"理论上没问题"。

---

## 8. 每轮必须回答的三个问题

```
1. 目标达成了吗？
   达成 → 贴出达成依据（日志行 / 截图 / 计数）
   没达成 → 卡在 §6 的第几步，下一步打算验证什么假设

2. 有没有静默失败的可能？
   问自己：如果这段代码根本没生效，我能不能从日志里看出来？
   答"看不出来" → 埋点不合格，回去补

3. 有没有越界？
   - 碰了"不做"清单吗？
   - 新增了未登记的自行补充实现吗？（07 T12）
   - 顺手改了用户没点名的地方吗？（07 X12）
```

---

## 9. 循环的终止条件

**只有下面全部成立，本次任务才算结束：**

```
[ ] 本次目标对应的验收项（08-TESTING.md）全部通过
[ ] 构建退出码 0，jar 内容六项核对通过
[ ] runClient 真实跑过，日志无 ERROR、无 validation error
[ ] 所有埋点都出现过，计数对得上
[ ] 性能在 17-NATIVE.md §2 的预算内（或已记录偏差与原因）
[ ] 回归清单（08-TESTING.md §9）全过
[ ] 证据已按 §7 交齐
[ ] 变更记录已写（15-ITERATION.md 第 5 步）
[ ] 已 commit
```

**任何一项没勾上，任务就是没完成**，继续循环，不要开始下一个任务。

---

## 10. 任务清单（按此顺序推进，一次一个）

> ⛔ **本表已过时（2026-10-04）**：项目已从「P0.1→P4.3 单链里程碑」转为
> **「GAP 缺口主线」（见 `13-GAP-REGISTRY.md`）**，不再按此表推进。
> 下表仅保留作历史记录，当前进度请以 `CHANGE_LOG.md` 顶部 + `13-GAP-REGISTRY.md` 为准。
>
> | 阶段 | 任务 | 状态 |
> |---|---|---|
> | ~~P0.1~~ | 空模组能构建能跑 | ✅ 已达成 |
> | ~~P0.2~~ | 确认跑在 Vulkan 后端 | ✅ 已达成（`h36`，2026-10-05） |
> | ~~P0.3~~ | 首个可见产物 | ✅ 已达成 |
> | ~~P0.4~~ | bridge 包隔离落地 | ✅ 已达成 |
> | ~~P1.x~~ | uniform 传递 / 管线计数 | ✅ 已达成 |
> | ~~P2.x~~ | 包解析 / `#include` / composite | ✅ 已达成 |
> | ~~P3.x~~ | shadow / gbuffers / deferred | 🟡 地形部分达成，非地形未接 |
> | ~~P4.1~~ | 主流包视觉基线 | 🟡 BSL 编译全通，渲染正确性未闭环 |
> | ~~P4.2~~ | 切包回归 | ✅ 已落地（2026-10-01） |
> | ~~P4.3~~ | 选项 GUI | ✅ 已落地（2026-10-01） |

**当前工作主线**：按 `13-GAP-REGISTRY.md` 逐条关闭缺口，每次变更前先登记（T12）。

---

## 11. 硬规矩

```
必须跑测试才能说完成
必须有埋点才能交付
必须一次只验证一个假设
必须先登记再自行补充（07 T12）
必须先找参考再动手（07 T13）
必须先测量再优化（07 T14）

禁止静默降级到"什么都不做"（07 X11）
禁止在业务包直接 import com.mojang.renderpearl.*（07 T5）
禁止顺手改用户没点名的地方（07 X12）
禁止把"待确认项"用猜的值填（07 X9）
禁止吞异常让它"看起来能跑"
禁止用"理论上"代替"实测过"
禁止没调研就写实现（07 X13）
禁止拿"感觉慢"当性能证据（07 X14）
禁止在 G 系列闸门走完前上原生（07 T16 / X17）；冷路径上 Rust 需先实测 ≥20% 净收益（07 X27）
禁止让原生库成为启动的必要条件（07 T15 / X16）
```

**遇到下面四种情况，停下来问，不要自行决定：**

```
- 需要引入新的第三方依赖（含任何 C++/Rust 原生库）
- 需要改 bridge/ 之外的版本相关硬编码
- 发现 OF 语义与原版能力冲突、需要自行补充
- 任务涉及对外可见的命名 / 许可证
```
