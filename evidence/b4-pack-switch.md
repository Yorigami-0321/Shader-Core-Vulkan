# 切包路径（B4）取证 —— 冷切 BSL 2262–2986ms、缓存命中 703–740ms，两侧都超 B4 预算；Round 7 的复用收益在这条路上默认**不生效**

> G-01 文本摘要：日志关键行原文 + sha256 + 一行复现 + 判定。
> 被摘要的日志本体在 gitignored 路径（`run/logs/`），本文件只存可复核的事实。
> 本轮**没有改动任何生产代码** —— `dev.vkdisp.VkDispConfigHotReload` 的既有埋点直接可用。
>
> 承接 `evidence/client-verify-preprocess-reuse.md`：那一轮明确登记
> 「**切包路径（B4）未取证**；`PackCompileCache` 与复用的交互未验」。**本文件补上这一项。**

## 一行复现

```bash
source tools/vulkan-local/env.sh
export JAVA_TOOL_OPTIONS="-Djava.net.preferIPv4Stack=true"   # A/B 臂追加 -Dvkdisp.reuse.preprocess=false
./gradlew runClient --console=plain                            # 停在主菜单
# 切包：改 run/config/vkdisp-client.toml 的 shaderPack（无输入注入，走 FML nightconfig FileWatcher）
# 强制冷编译：touch run/shaderpacks/BSL_v10.1.8.zip（改 mtime，内容逐字节不变 ⇒ PackCompileCache 换键）
grep -E 'pack precompile done in|cold path timing|pack compile done' run/logs/latest.log
```

| 证据 | sha256（`run/logs/latest.log` 归档） |
|---|---|
| A1 `reuse=ON` 选项存储有值 | `d3dfb5f2422c644504c657b4ea1e4b8dd741f4f829a87adba0845c2c217b57ba` |
| B1 `reuse=OFF` 选项存储有值 | `98769a749659ae7182767e06e07b3161b4730a7ed8761d3ac89f8bd35c9c7c58` |
| A2 `reuse=ON` 选项存储有值 | `4b8398ca18b91a7b830f8e1fe9164e0d218c3103d5e6efbb472d32e338469714` |
| B2 `reuse=OFF` 选项存储有值 | `8640ae020083f499dd5151c780f23839fb005224cebca616599d8974a9014e95` |
| C1 `reuse=ON` **选项存储清空** | `3def2c81250281373d54e02f14ea189d8f236ec20776531525244ad25c22f921` |
| C2 `reuse=OFF` **选项存储清空** | `0355dc8156540a1a6e4253b2b49ce347a4504a1020298811b33e8d0464f74604` |

## 环境与方法

| 项 | 值 |
|---|---|
| 机器 | AMD Ryzen 7 8745H / Linux amd64 / WSL2（电源与频率未锁 —— §5.2 噪声红线同源） |
| 客户端 | `./gradlew runClient`，停在主菜单；Vulkan + lavapipe（`tools/vulkan-local/env.sh`） |
| 库存 | `BSL_v10.1.8.zip`（**190 阶段**）、`vkdisp-fixture-dir`、`vkdisp-fixture-zip` |
| 驱动切包 | 改 `run/config/vkdisp-client.toml` 的 `shaderPack` → FileWatcher → `ModConfigEvent.Reloading` → `scheduleReloadWithPrecompile` |
| 样本量 | **6 趟 runClient × 24 次切包 = 144 个样本**；每趟固定序列：冷×6 → 温×6 → `none` 基线×2 → 复位 |
| 交替 | 会话顺序 **ON/OFF/ON/OFF**，再补 ON/OFF（§7.3 红线：两侧同机交替，不成块） |
| 取值 | `vkdisp: pack precompile done in {} ms -> resource reload (cache entries={}, hits={}, misses={})` |

**冷/暖的判据不是"我以为是冷"**：`PackCompileCache.missCount` 是累计计数，**只有真的编了一次才 +1**，
脚本按 miss 增量分类，并交叉核对时间分布。BSL 的两个模态分离极干净（32 个样本里没有任何一个落在
**1025ms 与 2698ms 之间**），故 1500ms 是安全的分界。

### 🔴 强制冷编译是怎么做的（这条决定了整份文件的可信度）

`PackCompileCache.Key` = 路径 + 种类 + 大小 + **修改时间**（`ShaderPackScanner.java:46-55`）。
所以 `touch` 包文件就能换键、强制一次真编译，而**输入内容逐字节不变**（同一个 zip）。
这比"另换一个包"更干净：变的是缓存状态，不是被测输入。

> ⚠️ **前置条件（不做这一步，测到的全是缓存命中）**：启动时 `VkDispPackScan.compileAndLog`
> 会遍历库存里**每一个包**并 `PackCompileCache.getOrCompile(..., Map.of())`
> （`VkDispPackScan.java:192-198`）⇒ 停到主菜单时三个包**都已缓存**。
> 直接切包量到的是"缓存后的等待"，**不是冷编译**。本文件两组数字都报了。

## 数据

### 一、B4 埋点本身（`pack precompile done in {} ms`）

BSL 冷切（这一次真的编了 190 阶段）：

| 选项存储 | 切包路径上复用闸门 | reuse=ON | reuse=OFF | 差 |
|---|---|---:|---:|---:|
| **有值**（默认状态） | **关闭**（overrides ≠ `{}`） | **2986 ms** (n=4: 3065, 2698, 3295, 2906) | **2894 ms** (n=4: 3019, 2886, 2901, 2877) | **+3.2%（无效应）** |
| **清空** | **打开**（overrides == `{}`） | **2262 ms** (n=2: 2520, 2003) | **2714 ms** (n=2: 2836, 2591) | **−16.7%** |

BSL 温切（缓存命中，窗口内**零编译**，四个条件 12 个样本全部落在 692–1025ms）：

| 条件 | n | 样本 (ms) | median |
|---|---:|---|---:|
| 选项存储有值 · reuse=ON | 4 | 748, 735, 746, 713 | **740 ms** |
| 选项存储有值 · reuse=OFF | 4 | 716, 1025, 749, 706 | **732 ms** |
| 选项存储清空 · reuse=ON | 2 | 720, 730 | **725 ms** |
| 选项存储清空 · reuse=OFF | 2 | 714, 692 | **703 ms** |

其余包与基线（合并四组会话）：

| 目标 | n | median | 备注 |
|---|---:|---:|---|
| `vkdisp-fixture-dir` + `-zip` | 32 | **13–16 ms** | 🔴 冷/暖**不可分**（都 < 40ms，见下） |
| `none`（保留名，不编译任何包） | 48 | **10–11 ms** | 纯调度/重载开销的地板 |

### 二、🔴 埋点在"用户等完"之前就停了 —— 这条比上面所有数字都重要

`scheduleReloadWithPrecompile` 的计时器在 `minecraft.reloadResourcePacks()` **之前**停表
（`VkDispConfigHotReload.java:241-247`）。但渲染线程要等资源重载真正结束才恢复：

| 段 | n | median | max |
|---|---:|---:|---:|
| 埋点 `done in` → `client resources loaded`（**`none`**，下界） | 48 | **2120–2181 ms** | 2883 ms |
| 同上，BSL 冷切 | 12 | **3811 ms** (ON) / **4196 ms** (OFF) | 4657 ms |

**端到端（埋点 + 尾巴）＝ 用户真正等待的墙钟**：

| 目标 | reuse=ON | reuse=OFF |
|---|---:|---:|
| BSL 冷切（选项存储有值） | **6981 ms** (max 7271) | **7282 ms** (max 7676) |
| BSL 冷切（选项存储清空） | **5253 ms** (max 5620) | **5748 ms** (max 6207) |

> **B4 预算（`17-NATIVE.md` §5：≤ 2 秒，缓存命中 ≤ 0.5s）**：
> - 冷切 BSL：**2262–2986 ms，全部超标**（最好的一臂也超 13%）；
> - 缓存命中：**703–740 ms，超标 41–48%**；
> - 端到端：**5.3–7.3 秒，是预算的 2.6–3.6 倍**。
>
> ⚠️ P4.5 记录的 1861ms 与本轮同埋点的 2262–2986ms 对不上。**本文件不裁决谁对**
> （机器状态、选项存储、测量点都可能不同），只登记这个差距本身。

## 机制：为什么 Round 7 的 −22.6% 在这条路上"测不出来"

`ShaderPackCompiler.compile` 的复用闸门是 `REUSE_PREPROCESS && overrides.isEmpty()`
（`ShaderPackCompiler.java:157`）。而切包路径喂进去的 overrides 来自
`diffAgainstDefaults(...)`（`PackCompositeSource.java:259`）。

本机选项存储 `run/config/vkdisp-pack-options.properties` 里：

```
BSL_v10.1.8.SHARPEN=3                    # BSL 的 SHARPEN default = -1  ⇒ 3 ≠ -1
vkdisp-fixture-dir.SHADOW_DARKNESS=0.20  # default = 0.10             ⇒ 0.20 ≠ 0.10
```

⇒ **只要用户改过任何一个选项，切包路径的 overrides 就非空，复用直接被关掉。**
启动路径不受影响，因为 `compileAndLog` 写死传 `Map.of()`：

| 路径 | 传入的 overrides | 闸门 | reuse=ON | reuse=OFF | 差 |
|---|---|---|---:|---:|---:|
| 启动冷路径 `compileAndLog` | 恒为 `{}` | 一直打开 | **3516 ms** (n=3) | **4058 ms** (n=3) | **−13.4%** |
| 切包路径（选项有改动） | `diffAgainstDefaults` | 关闭 | 2986 ms | 2894 ms | +3.2% |
| 切包路径（选项全默认） | `{}` | 打开 | **2262 ms** | 2714 ms | **−16.7%** |

🔴 **这解释了 Round 7 的收益为什么"从未在切包路径上验证过"**：不是收益不存在，
而是**默认状态下它根本没在这条路上运行**。清空选项存储后，−16.7% 立刻出现
（两组区间**不重叠**：2003–2520 vs 2591–2836）。

## 顺带查出：`PackCompileCache` 的容量在本库存下几乎见底

`MAX_ENTRIES = 8`，超限时 `put()` 直接 `CACHE.clear()` **清空整张表**
（`PackCompileCache.java:104-106`，容量常量在 `:41`）。选项存储有值时，每个包会占**两个键**
（热路径的 `diffAgainstDefaults` 键 + 取证侧 `compileAndLog` 的 `{}` 键），
3 个包 = 6 键，**只剩 2 个余量**：

| 会话 | 选项存储 | entries 峰值 | 整表被清空的切包序号 |
|---|---|---:|---|
| A1 / B1 / A2 / B2 | 有值 | **8 / 8** | **第 7 次切包** |
| C1 / C2 | 清空 | **8 / 8** | 第 11 次切包 |

6 趟全部撞顶、清空一次。后果是**一次清空之后，下一次切包要把整包重新编一遍** ——
对 B4 是直接的成本。用户装的包越多（每包 1–2 键）撞顶越早。

## 行为等价（6 趟一致，两侧都验）

| 判据 | A1 | B1 | A2 | B2 | C1 | C2 |
|---|---:|---:|---:|---:|---:|---:|
| `pack compile done: stages=190 ok=190 failed=0` | 25 | 25 | 25 | 25 | 25 | 25 |
| 出现 `failed=[1-9]` 的行 | 0 | 0 | 0 | 0 | 0 | 0 |
| vkdisp 的 ERROR / WARN 行 | **0** | **0** | **0** | **0** | **0** | **0** |
| `pack program compiled OK` 行数 | 4750 | 4750 | 4750 | 4750 | 4750 | 4750 |

**✅ 144 次切包、两侧、两种选项状态，行为完全一致**（25 次重载 × 190 阶段 = 4750 条驱动级编译成功行）。

## 🔴 本文件**没有**证明的事

| 未覆盖 | 说明 |
|---|---|
| **决定性一臂 n=2（每侧）** | 「选项存储清空」只有 C1/C2 两趟，各 2 个冷样本 ⇒ **低于 §7.1 的样本 ≥5**。区间不重叠，方向可信，**但不能声称统计显著**。有值臂 n=4/侧，同样低于 5 |
| **未做预热剔除** | 每个会话的第 1 个冷样本就是该 JVM 的第一次编译。按 §7.1 应剔除，但剔了有值臂只剩 n=2 ⇒ 本文件**原样保留全部样本**。剔除后有值臂差值从 **+3.2% 变 −2.8%**（ON 2802ms / OFF 2881.5ms），**符号翻转但仍在噪声内，两种算法都不支持"有效应"** |
| **未做同机 CPU 频率锁定** | 与 `g0-caliber-fix.md` 同一条噪声红线；§5.2 的 20% 裁决阈值在此**不能直接套用**（本轮 −16.7% 已低于该阈值） |
| **"冷/暖"两个 fixture 不可分** | 两个 fixture 包冷切与缓存命中都 < 40ms，**本文件拒绝给它们分冷暖**，只报合并值。要分辨需要更大的包 |
| **只有 3 个包、都是本仓库自带的** | 只覆盖 BSL + 2 个 fixture。其它包（Iris/OptiFine 等）未覆盖 |
| **没进世界目检画面** | 本轮只到"编译成功 + 产物一致"，**没有**验证切换后画面正确（§5.1 R2–R7 画面项未做） |
| **未测 `packProfile` 切换** | 只切了 `shaderPack`；profile 变更走同一闸门但未采样本 |
| **埋点本身没有被改** | 本文件指出"埋点在用户等完前就停表"，但**没有**新增一个覆盖端到端的打点 ⇒ 下轮若要长期盯 B4，得先补埋点 |
| **第一趟 pilot 已作废** | 早期一趟 harness 的 `touch` 用错了文件名，在 `run/shaderpacks/` 留下两个 0 字节文件污染了库存，**已删除**；该趟数据**未进入本文件任何表格** |

## 结论

1. 🔴 **B4 超标，且是两条腿都超**：冷切 BSL 2262–2986ms（预算 2s）、
   缓存命中 703–740ms（预算 0.5s）。**端到端 5.3–7.3 秒，是预算的 2.6–3.6 倍** ——
   因为既有埋点在 `reloadResourcePacks()` **之前**就停了表，还差一整段尾巴（1.9–4.2 秒）。
2. 🔴 **Round 7 的预处理复用在这条路上默认不生效**：`diffAgainstDefaults` 只要非空就关闸门
   （本机 BSL `SHARPEN=3` ≠ 默认 `-1`）。默认状态下 A/B 测得 +3.2%（无效应）；
   **清空选项存储后 −16.7%，区间不重叠**。启动路径恒传 `{}`，所以那边一直是 −13.4% ——
   **两边数字不矛盾，是两条不同的闸门**。
3. ✅ **切包路径的行为等价已验证**：144 次切包、6 趟、两侧各 `stages=190 ok=190 failed=0`、
   0 ERROR/WARN、4750 条 SPIR-V 成功行完全一致。
4. ⚠️ **`PackCompileCache` 在 3 包库存下就撞顶 8/8 并清空整表**；每个有选项改动的包占两个键。
   这是 B4 上的直接成本，建议与上面第 2 条一并排期。
5. ⏭️ 未做：补覆盖端到端的埋点、n≥5 的决定性一臂、更多包、profile 切换、进世界画面目检。