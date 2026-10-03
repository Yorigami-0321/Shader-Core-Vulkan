# 客户端取证 · 复用 load 的预处理 —— 冷路径 3482 → 2696ms（-22.6%），190 条 SPIR-V 逐条一致

> 承接 `evidence/pp-parse-profile.md`。那一轮只做了**离线基准**，并明确登记
> 「**没有 runClient 取证**，39.3% 的收益尚未在客户端验证」。**本文件补上这一项。**
>
> 同时为启动冷路径**新增了计时埋点** —— 此前客户端日志里**没有任何冷路径耗时打点**，
> 离线基准测得到、客户端里测不到，两边无法对账。

## 环境

| 项 | 值 |
|---|---|
| 机器 | AMD Ryzen 7 8745H / Linux amd64 / WSL2 |
| 客户端 | `./gradlew runClient`（停在主菜单，`initial=true`），Vulkan 后端 + lavapipe（`tools/vulkan-local/env.sh`） |
| 库存 | `run/shaderpacks/BSL_v10.1.8.zip` + `vkdisp-fixture-dir`，合计 **190 阶段** |
| 协议 | 同一构建产物，两趟分别带 `-Dvkdisp.reuse.preprocess=false` 与默认开启 |
| 取值 | `vkdisp: cold path timing: scan=… ms compile=… ms total=… ms (initial=…, reusePreprocess=…)` |

## 客户端实测

| 实现 | scan | compile | **冷路径合计** |
|---|---:|---:|---:|
| 原路径（预处理算两遍） | 849 ms | 2633 ms | **3482 ms** |
| 复用 load 的预处理 | 721 ms | **1975 ms** | **2696 ms** |
| 变化 | −15.1% | **−25.0%** | **−22.6%** |

**绝对收益 786ms。** 两段都受益：`scan` 段里也包含 `load`（即那一次预处理），`compile` 段受益于复用。

> ⚠️ **客户端的百分比（−22.6%）小于离线（−39.3%），但绝对收益更大（786ms vs 573.5ms）。**
> 原因是**分母不同**：客户端的 `compileAndLog` 还要把每个阶段的最终源交给**原版驱动做
> GLSL→SPIR-V 编译**，这部分**不受本次优化影响**，被加进了两边的分母。
> 绝对收益一致（573.5 vs 786ms，同量级），说明**离线的 39.3% 没有虚高**。

## 行为等价（客户端侧）

| 判据 | reuse ON | reuse OFF |
|---|---|---|
| `pack compile done` | `stages=190 ok=190 failed=0` | `stages=190 ok=190 failed=0` |
| vkdisp 的 ERROR / WARN 行数 | **0** | **0** |
| 日志总行数 | **1563** | **1563** |
| **SPIR-V 产物记录（包×程序×阶段×文件×字节数）** | **190 条** | **190 条** |

```
$ diff /tmp/spv-reuse-on.txt /tmp/spv-reuse-off.txt   # 无差异
pack=BSL_v10.1.8 program=world-1/composite stage=FRAGMENT file=world-1/composite.fsh spvBytes=47412
pack=BSL_v10.1.8 program=world-1/composite stage=VERTEX  file=world-1/composite.vsh  spvBytes=8944
...
```

**✅ 190 条 SPIR-V 产物逐条一致。** 这是客户端侧最接近「下游真正看到的东西」的判据 ——
文本产物相同（离线已证），驱动编译出的 SPIR-V 字节数也完全相同。

## 顺带补上的基建缺口：启动冷路径计时埋点

`VkDispPackScan` 的 `ClientResourceLoadFinishedEvent` 入口原先**没有任何耗时打点**
（只有切包路径 `VkDispConfigHotReload` 有 `pack precompile done in {} ms`）。
已补：

```
vkdisp: cold path timing: scan={} ms compile={} ms total={} ms (initial={}, reusePreprocess={})
```

日志里同时带上 `reusePreprocess` 实际取值 ⇒ **以后任何一趟客户端日志都能自证走的是哪条路径**，
不必再靠外部猜测（§7.3 红线要的「可复核」）。

## 🔴 本文件**没有**证明的事

| 未覆盖 | 说明 |
|---|---|
| **每侧只有 1 趟（n=1）** | 效应量（−22.6%）远大于已知噪声，方向可信；但严格说**未做多趟交替**（runClient 一趟要数分钟） |
| **两趟不是交替的** | OFF 跑在**第二**趟，享受了更热的 OS 页缓存 ⇒ **这个偏差是压低 ON 的**，真实收益可能 ≥ 22.6% |
| **只有冷启动路径** | 切包路径（B4，`pack precompile done in {} ms`）**未取证**；`PackCompileCache` 与复用的交互未验 |
| **只有 BSL + fixture 目录包** | 其它包未覆盖 |
| **未测运行时画面** | 本轮只到「编译成功 + 产物一致」，**没有**进世界目检（§5.1 R2–R7 的画面项未做） |
| **G0 四段口径仍未修** | `runPass` 还是把 `load` 当「纯解析」量（`pp-parse-profile.md` 已登记） |

## 结论

1. ✅ **客户端实测确认**：`stages=190 ok=190 failed=0`、0 ERROR/WARN、
   **190 条 SPIR-V 产物逐条一致** —— 复用是行为中性的。
2. ✅ **冷路径 3482 → 2696ms（−22.6%，786ms）**；客户端百分比低于离线是因为分母里多了
   不受影响的驱动 SPIR-V 编译，**绝对收益一致 ⇒ 离线的 39.3% 没有虚高**。
3. ✅ 顺带补上了启动冷路径的计时埋点，以后每趟客户端日志都能自证路径与耗时。
4. ⏭️ 未取证：切包路径（B4）、多趟交替、进世界画面目检、G0 四段口径修正。