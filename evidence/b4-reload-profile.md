# B4 资源重载成分剖析 —— 3260ms 里 vkdisp 自己占 37%，且其中约 1.1 秒像是白花的

> Round 12 定位到「端到端 3294ms 里资源重载段占 3260ms（99%）」，但**没回答那 3260ms 是什么**。
> 本文件回答它，并给出一个**具体可修**的发现。
>
> ⚠️ **本机是 lavapipe（软件 Vulkan）**，驱动编译耗时可能严重失真 —— 量化见第 5 节。

## 环境

| 项 | 值 |
|---|---|
| 机器 | AMD Ryzen 7 8745H / Linux amd64 / WSL2 |
| 客户端 | `./gradlew runClient`，Vulkan 后端 + **lavapipe**（`tools/vulkan-local/env.sh`） |
| 采样 | `jcmd <pid> JFR.start settings=profile`（**动态起录，只录游戏 JVM**，3.82MB） |
| 触发 | 改 `run/config/vkdisp-client.toml` 的 `shaderPack`（FML FileWatcher，500ms 去抖） |
| 样本 | **4 次切包**（§7.1 要求 ≥5，本轮**未达标**，见「没有证明的事」） |
| 栈深 | 默认 64 **不够**（会把入口截掉），重取时用 `--stack-depth 256` |

## 一、结论先行

**端到端的中位数 2926ms 里，资源重载段被拆成四块：**

| 成分 | 中位数 | 占比 | 归属 |
|---|---:|---:|---|
| 🔴 **`openResources` / `generateSources()`** | **705 ms** | **24.1%** | **vkdisp —— 本该是微秒级** |
| 原版资源重载（字体 / 模型 / 纹理） | **1743 ms** | 59.6% | 原版，与 vkdisp 无关 |
| 🔴 **`scanAndLog`（又一次完整扫描 + 编译）** | **388 ms** | **13.3%** | **vkdisp** |
| 驱动 GLSL→SPIR-V（190 条编译） | ~35 ms | ~1.2% | vkdisp → 驱动 |
| **GC 停顿（另列，见第 4 节）** | **161 ms/窗** | **5.5%** | 运行时 |

⇒ **vkdisp 自己占 705 + 388 ≈ 1093ms ≈ 37.4%。**
⇒ **其中 `generateSources` 的 705ms 与 `scanAndLog` 的 388ms 高度疑似「本不该发生的工作」**（见第 6 节）。

## 二、四次窗口的完整分解

用 vkdisp 自己的日志标记切时间轴（`precompile done` → `B4 end-to-end`）：

| 窗口 | 总计 | `generateSources` | 原版资源重载 | `scanAndLog`+编译 |
|---|---:|---:|---:|---:|
| #1 | 3134 ms | 715 ms | 2032 ms | 384 ms |
| #2 | 2969 ms | 859 ms | 1719 ms | 389 ms |
| #3 | 2883 ms | 694 ms | 1767 ms | 420 ms |
| #4 | 2779 ms | 676 ms | 1714 ms | 387 ms |
| **中位数** | **2926 ms** | **705 ms** | **1743 ms** | **388 ms** |

四次形态高度一致 ⇒ 不是偶然。

### 窗口 #1 的逐毫秒时间线（原文照抄）

```
12:17:32.255  pack precompile done in 855 ms          ← 窗口起点
12:17:32.258  composite source generation start       ← vkdisp openResources 开始
12:17:32.973  builtins layout parsed                  ← 715ms 后 vkdisp 这段做完
              …（原版重载：字体 / 模型 / 纹理 atlas）…
12:17:34.995  pack scan done                          ← VkDispPackScan 开始收尾
12:17:35.007  190 条 "pack program compiled OK" 爆发   ← 全部集中在 35ms 内
12:17:35.389  client resources loaded
12:17:35.389  B4 pack switch end-to-end: 3989 ms       ← 窗口终点
```

## 三、两条独立方法互相印证

**(a) 日志时间轴**（上面那张表）——用 vkdisp 自己的标记切分，毫秒级精确。

**(b) JFR 栈归属**（786 个落在窗口内的 ExecutionSample，栈深 256）：

| 调用链 | 采样 | 占比 | 换算 |
|---|---:|---:|---:|
| 经 `VkDispVirtualPack.openResources → generateSources → load` | 179 | 22.8% | ≈ 667 ms |
| 经 `VkDispPackScan.scanAndLog` | 159 | 20.2% | ≈ 591 ms |
| 其它 vkdisp 路径 | 6 | 0.8% | ≈ 23 ms |
| 非 vkdisp | 442 | 56.2% | ≈ 1645 ms |

两法对 `generateSources`（24.1% vs 22.8%）与「非 vkdisp」（59.6% vs 56.2%）的估计接近 ⇒ 归因可信。

窗口内的原版重载特征帧：`FontManager` / `FontSet` / `UnihexProvider` / `ModelBakery` /
`FaceBakery` / `ModelDiscovery` / `SpriteLoader` / `MipmapGenerator` —— **全是原版资源子系统**。

### 🔴 我在这一步犯过一个错，值得记

第一次归因只拿到 `openResources` 1.0%、「渲染主循环」43.3%。原因是**我的脚本按点号匹配类名
（`dev.vkdisp.pack`），而 JFR 的类型名是斜杠形式（`dev/vkdisp/pack`）** ⇒ vkdisp 的采样几乎全被漏掉，
反倒把渲染线程里的 vkdisp 工作误判成「渲染主循环」。
🔖 **归一化类型名（`/` → `.`）之后再归因**，结论才成立。已记入本文件。

## 四、🔴 GC 必须单列（Round 6/7 栽过两次的那件事）

**`ExecutionSample` 的栈归属里一个 GC 采样都没有** —— GC 跑在别的线程上。

| 窗口 | 时长 | GC 停顿 | 占窗口 | 事件数 |
|---|---:|---:|---:|---:|
| #1 | 3134 ms | 183.7 ms | 5.9% | 14 |
| #2 | 2969 ms | 151.7 ms | 5.1% | 11 |
| #3 | 2883 ms | 184.6 ms | 6.4% | 13 |
| #4 | 2779 ms | 126.9 ms | 4.6% | 10 |
| **合计** | **11765 ms** | **647.0 ms** | **5.5%** | 48 |

（全程 62 次 GCPhasePause 共 843.8ms，其中 647ms 落在这 4 个重载窗口内。）

⇒ **本文件的归因不含 GC**。若把 GC 单列后重新分配，「vkdisp 占 37.4%」这个数只会**下降**。

## 五、🔴 lavapipe 失真：**倍数量不出来，但能证明它不是解释**

任务要求量化 lavapipe（软件 Vulkan）相对真实硬件的失真倍数。**诚实的答案是：量不出来。**

**为什么量不出来**：本机没有真实 GPU，没有任何可比对的硬件数据。
任何「倍数」都得靠外部文献或凭空假设，本项目不接受这种数。

**但可以证明它不是那 2.9 秒的成因**，三条独立证据：

1. **190 条 `pack program compiled OK` 全部集中在 35ms 内爆发**（12:17:35.005→.040）
   ⇒ 驱动侧编译占窗口 **≈1.2%**。即便 lavapipe 让它慢了 10 倍，也只到 ~12%。
2. **窗口内的 561 个 `NativeMethodSample` 里，没有任何 lavapipe / LLVM / glslang 帧**。
   最大的 353 个是 `LinuxWatchService.poll`（配置监听的空闲轮询，不是工作），
   其余是 `Inflater.inflateBytesBytes`（解 zip）、`STBImage.nstbi_load_from_memory`（解码图片）。
3. **JFR 的 Java 栈里 `com.mojang.renderpearl`（驱动桥）只占 2.5%**。

⇒ **失真倍数不可知，但方向可判**：驱动编译在本机就已经只占 ~1.2%，
**无论真实硬件上是 0.1% 还是 50%，它都不足以解释 2.9 秒。** 这 2.9 秒的主体是
**原版资源重载**（字体/模型/纹理）与 **vkdisp 自己的两段工作**。

## 六、🔴 两个疑似「白花」的地方（本轮只测不改）

### ① `openResources` / `generateSources()` = 705ms，本该是微秒级

`PackPrecompileScheduler` 的类注释写的是：「编译完成后再回调渲染线程执行 `reloadResourcePacks()`
—— 那时 `openResources` **命中缓存，微秒级返回**」。

**实测 705ms，不是微秒级。** 两个证据指向「缓存没命中、在重新编译」：

- 705ms 与预编译本身的耗时（718–859ms）**同一量级** ⇒ 它是在**重做一遍**预处理，不是「返回缓存」；
- 每个窗口的缓存统计都是 `entries=4, misses=4` —— **每窗固定 4 次未命中**，
  而 `hits` 在累加（1→6→11→16）⇒ 键在增长但条目数不涨，**存的不是同一个键**。

> ⚠️ 这与 Round 11 `b4-pack-switch.md` 查到的是**同一个机制**：
> 切包路径经 `diffAgainstDefaults(...)` 喂进来的 overrides 非空，而预编译用的是另一套键
> ⇒ **两条路径算的是两个键**。
> 🔖 但要注意：Round 11 说的是「复用闸门被关」，本轮看到的是「**缓存整体没命中、于是重编译**」。
> **二者是不是同一件事，本轮没有验证。**

### ② `scanAndLog` 每次资源重载后都跑一遍 = 388ms

`VkDispPackScan` 监听 `ClientResourceLoadFinishedEvent`，每次资源重载完成就跑
「全量扫描 + 全量编译」（日志 `pack scan done: packs=3 programs=9`，
本窗口 `cold path timing: scan=724 ms compile=…`）。

而切包链路**已经**在 precompile 阶段做过一次同样的工作。
⇒ 这 388ms 看起来是**同一份工作在同一趟用户操作里做第二遍**。

### ③ 附带观察：752 条诊断日志/窗口

每个重载窗口打出 378 条 `composite source diagnostic` + 374 条 `pack diagnostic`
（含 WARN 级）。这部分 I/O 未单独计时，**已含在上面各段里**。

## 🔴 本文件**没有**证明的事

| 未覆盖 | 说明 |
|---|---|
| **样本只有 4 组** | §7.1 要求 ≥5，**未达标**。切包每趟数分钟，4 组是本轮现实上限。四次形态一致，但**不等于统计显著** |
| **四组不是完全同质的** | 脚本按 `BSL → 空 → BSL → 空` 切换；实际每个窗口都仍有 190 条 compiled OK ⇒ **"切到空包"可能没真正生效**（FML 是否接受空串未验证）。四组因此可视为同质的「重载 BSL」 |
| **lavapipe 失真倍数不可知** | 见第 5 节。本文件只能证明它**不是**成因，给不出倍数 |
| **原版重载那 1743ms 没再细拆** | 只知是字体/模型/纹理 atlas。**再往下拆需要改 `src/` 加埋点，本轮是纯测量** |
| **GC 的 647ms 没归因到具体分配点** | 只报了总量；`ObjectAllocationSample` 有 5139 条但未做分配点归因 |
| **没有硬件对照** | 所有数字都来自 lavapipe |
| **归因是采样** | 786 个样本按 14.97ms/样本换算成时间。**采样占比 ≠ 可优化空间**（P1 已吃过这个亏），本文件只把它当成分定位，不当优化空间 |
| **「白花」的判断是推断** | 第六节两条都只是**证据链**，未做实验验证。**本轮不改代码** |

## 七、建议（供 Lead 决定，本轮不实施）

按「证据强度 × 收益」排序：

1. **让 `generateSources` 与预编译用同一个缓存键**（705ms/次）。
   Round 11 已定位到切包路径的 overrides 与启动路径不同；本轮补充「后果是每次重载都重编译一遍」。
   🔴 **但要先确认它与 Round 11 的发现是不是同一件事** —— 本轮没有验证。
2. **让 `scanAndLog` 在「已经预编译过」时跳过全量重扫**（388ms/次）。
3. **给原版重载那 1743ms 加更细的埋点**再决定 —— 它占 59.6%，
   但**vkdisp 只提供着色器**，能改的空间未知（可能是「不该触发全量 `reloadResourcePacks`」这种方向）。
4. 诊断日志：752 条/窗口是否必要，值得单独看一眼（未计时）。

## 八、一行复现

```bash
source tools/vulkan-local/env.sh
export JAVA_TOOL_OPTIONS="-Djava.net.preferIPv4Stack=true"
./gradlew runClient &
GAME=$(ps -eo pid,comm,args --no-headers | awk '$2=="java" && /net\.neoforged\.devlaunch\.Main/ {print $1}')
jcmd "$GAME" JFR.start name=reload settings=profile filename=/tmp/reload.jfr   # ⚠️ duration 不能写 0s
# 改 run/config/vkdisp-client.toml 的 shaderPack，重复 ≥4 次，每次等 ~55s
jcmd "$GAME" JFR.stop name=reload
kill "$GAME"
# ⚠️ 归因时栈深必须加大，否则入口会被截掉
jfr print --stack-depth 256 --events ExecutionSample --json /tmp/reload.jfr
```

> 🔖 踩过的坑：`jcmd JFR.start duration=0s` 会**静默失败**（要求 ≥1s），
> 脚本却照样跑完切包 —— **若没检查「JFR 大小」就写证据，本轮会是零数据**。