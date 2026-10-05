# h36 · 🔴 首次在 **Vulkan 后端**取证：P0.2 终于达成

> **日期**：2026-10-05
> **性质**：**环境解锁 + 验证**，不是功能改动（本轮 `.java` 一行未改）。
> **verdict = 本项目有史以来第一次拿到 Vulkan 产物。**
> **`P0.2`（`01-DEV-LOOP` §10「确认跑在 Vulkan 后端」）自此达成** ——
> 此前三轮（`h33`/`h34`/`h35`）的所有取证**全部是 OpenGL/llvmpipe 产物**。
> 证据：`evidence/h36-…`（含两张 Vulkan 截图 + 一条**被拒绝的无效对照**）。

---

## 一、一页版结论

| 项 | 前三轮（OpenGL） | 本轮（**Vulkan**） |
|---|---|---|
| **P0.2 `backend=`** | ❌ `OpenGL`（每轮一条 ERROR 断言失败） | ✅ **`Vulkan, device=llvmpipe (LLVM 22.1.8, 256 bits)`** |
| vkdisp ERROR 总数 | 1（就是那条 P0.2 断言） | **0** |
| `gbuffer terrain pass failed` | 0（`h33` 修后） | **0** |
| `setUniform` 异常 | 0（`h33` 修后） | **0** |
| `gbuffer terrain targets ready` | 1（`h34` 修后） | **1**（未复发） |
| `terrain slot clear` | NEUTRAL | NEUTRAL |
| pass 帧数 | 600 | **1800** |
| mixin 命中 | — | **x2,500,000** |
| 退出方式 | 手动 kill | **正常存档**（`Gathered mod list to write to world save`） |

🔖 **最关键的一条**：`vkdisp ERROR` 从「每轮必有 1 条」变成 **0 条** ——
因为那条唯一的 ERROR 就是「你不是跑在 Vulkan 上」本身。

---

## 二、环境是怎么解锁的（**不是本轮做的**）

⚠️ **必须说清楚归属**：`tools/vulkan-local/` 下的 prefix（`libvulkan.so.1.4.357` +
`libvulkan_lvp.so` + `lvp_icd.json`）、`preflight.sh`、`run-client.sh`，
以及 `build.gradle` 里那段接线，**都是同一工作区里另一条并行线（env-1）的成果**，
**至今未提交**（见 §五）。

本轮做的是：**发现它就绪了，于是第一次用它取证**。

```
$ bash tools/vulkan-local/preflight.sh
[ OK ] loader: .../tools/vulkan-local/prefix/usr/lib/libvulkan.so.1
[ OK ] ICD:    .../tools/vulkan-local/prefix/usr/share/vulkan/icd.d/lvp_icd.json
[ OK ] 设备探测通过：llvmpipe (LLVM 22.1.8, 256 bits) (api 1.4.98)
[WARN] 软件光栅化（CPU）⇒ Vulkan 语义正确，但帧率不代表 GPU 表现
[ OK ] preflight PASS —— Vulkan 可用
```

⚠️ **「软件 Vulkan」的准确含义**（不要含糊）：
设备是 `llvmpipe` via **lavapipe**（Mesa 的 CPU Vulkan 实现），
**不是** RTX 4060 —— WSL2 的 GPU 直通只覆盖 CUDA/D3D12，NVIDIA 不投放 Vulkan ICD。
⇒ **Vulkan 语义是真的**（API 1.4.98，真 loader、真 ICD、`VK_EXT_debug_utils` 等扩展可用），
但**帧率完全不代表真实硬件**，支柱③ B1/B3/B4 本轮**仍然无结论**。

---

## 三、🔴 一条**被拒绝的对照**（本轮第二个值得记的点）

本轮顺手做了一次「同一存档、同一机位、`dayTime=6000`、仅后端不同」的跨后端像素对照。

原始数字：

```
mean abs diff per channel : 12.3338
max  abs diff             : 250
pixels differing >2       : 238180 / 409920 = 58.104%
pixels differing >8       : 184215 / 409920 = 44.939%
pixels differing >32      :  57979 / 409920 = 14.144%
```

58% 看起来像「大问题」。**但按屏幕分带一看，这个对照就不成立了**：

| 屏幕带 | 平均差 | >8 的像素 | 说明 |
|---|---|---|---|
| **HUD（y=440..479）** | 5.937 | **30.62%** | 🔴 **HUD 是原版 UI，根本不经过 vkdisp** |
| hotbar | 10.451 | 54.03% | 同上 |
| 地形 | 19.722 | 73.58% | — |
| 天空 | 4.415 | 16.88% | — |

⇒ **连原版 HUD 都有 30% 的像素差 >8**。若差异来自 vkdisp 的着色路径，HUD 不该受影响。
⇒ 差异的主导因素是**两次独立会话之间的运行期差异**（各自的 in-game time 仍在推进、
云层/光照在动、MSAA/字体光栅化的后端差异），**不是后端语义差异**。

🔖 **结论：不据此下任何判断。**
这与 `h34` 拒绝「两次运行 75.29% 像素差」是**同一条纪律** ——
**一个连对照组都不可信的数字，不能用来支持任何结论。**

⚠️ **要做一次真正成立的跨后端对照，需要**：可复现的世界状态
（固定 time **且** weather **且** 玩家坐标/朝向）+ 一个**确定性静物参照**。
本项目目前两者都没有 ⇒ 这条对照**暂时做不出来**，如实登记。

---

## 四、🔴 本轮**没有**做到的事（如实列出）

| 项 | 状态 |
|---|---|
| **任何 `.java` 改动** | ❌ **本轮一行未改**（纯取证轮） |
| 真正成立的跨后端像素对照 | ❌ **做不出来**（见 §三），已说明需要什么 |
| **validation layer** | ❌ **仍然没有**。设备支持 `VK_EXT_debug_utils (I)`，但 vkdisp 的 drain 仍报 `channel unavailable or empty` ⇒ **不得**据此说「无 validation error」（`07` X35 禁令） |
| **性能结论（支柱③ B1–B7）** | ❌ **仍然无**。CPU 软件 Vulkan，帧率无意义 |
| 提交 env-1 的 prefix / build.gradle 接线 | ❌ **不做**，不属于本轮（见 §五） |
| QD-03 / QD-06 / `drawFullscreen` 拆分 | ❌ 未动 |

---

## 五、⚠️ 工作区里的并行改动（提请注意）

同一工作区里另一条并行线正在改 `build.gradle`（P0.2 Vulkan 预检 + prefix 接线）与
`tools/vulkan-local/`。**本轮的 0 个提交都不包含它们**。

需要知道的后果：那段改动把 `vulkanPreflight` 加成了 `runClient` 的**前置任务**，
prefix 不就绪时会**硬失败**（这是好事：它挡住了此前三轮那种「静默退回 OpenGL」的取证污染）。
⇒ 后续任何一轮要跑客户端，都**应当走 `bash tools/vulkan-local/run-client.sh`**，
而不是裸 `./gradlew runClient`。

---

## 六、复算 / 自检命令

```bash
# Vulkan 可用性
bash tools/vulkan-local/preflight.sh

# P0.2 判据（日志原文）
grep 'vkdisp: backend=' run/logs/latest.log

# 后端与设备扩展
grep -E 'Using graphics backend|Using graphics device extensions' run/logs/latest.log

# 前三轮结论在 Vulkan 上是否仍成立
grep -c 'gbuffer terrain pass failed' run/logs/latest.log                  # 0
grep -c 'textureView and sampler must both or neither be null' run/logs/latest.log  # 0
grep -c 'gbuffer terrain targets ready' run/logs/latest.log                # 1
grep -c 'terrain slot clear = NEUTRAL' run/logs/latest.log                 # 1

# 跨后端分带对照（§三）
python3 /tmp/xbands.py
```

---

## 七、自检

- [x] **归属说清楚**：环境解锁是并行线 env-1 的成果，不是本轮做的
- [x] 「软件 Vulkan」写明是 **lavapipe（CPU）**，并据此明确**不作性能结论**
- [x] 跨后端对照**做了、报了原始数字、又明确拒绝下结论**，理由是**连原版 HUD 都差 30%**
- [x] validation layer 的缺失**如实登记**，没有拿 `channel unavailable` 说「无错误」
- [x] 本轮**零 `.java` 改动**，不假装做了功能
- [x] 未提交并行线的改动，并在 §五 提醒后续轮次应走 `run-client.sh`
