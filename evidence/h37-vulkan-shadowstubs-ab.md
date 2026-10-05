# h37 · 真 Vulkan 上的功能 A/B：`shadowStubs` 修复 vs **故意 UB** —— 两者画面**逐像素相同**

> **日期**：2026-10-05
> **性质**：功能验证（按用户指令「只验证功能，不考虑性能」）。本轮 `.java` **一行未改**。
> **verdict = 本项目第一次在真 Vulkan 后端上跑完一组受控 A/B。
> 结果是一个**否定式但很重要的结论**：`mrt.shadowStubs` 这个修复
> **在 lavapipe 上测不出任何差别**，GAP-011 的闪烁在**两条臂上都复现不出来**。**

---

## 一、一页版结论

| 组 | `mrt.shadowStubs` | 后端 | 黑色占比（6 帧） | spread |
|---|---|---|---|---|
| **A** | `true`（**修复**：shadowtex 绑专用 1×1 桩） | Vulkan/lavapipe | 23.225% – 23.266% | **0.042 pp** |
| **B** | `false`（**故意 UB**：绑本 pass 的读写附件） | Vulkan/lavapipe | 22.999% – 23.044% | **0.044 pp** |

**A vs B 同机位同时间逐像素对比**：

| 屏幕带 | 平均差 | >8 的像素 |
|---|---|---|
| **HUD** | **0.000** | **0.00%** |
| **hotbar** | **0.000** | **0.00%** |
| **地形** | **0.000** | **0.00%** |
| 天空 | 1.942 | 6.62% |

🔖 **地形逐像素完全相同**（0.000）。天空那点差是两次采图之间**云层在动**，
不是开关造成的（地形与 HUD 都没动可以佐证）。

---

## 二、这两个否定式结论意味着什么

### 2.1 `mrt.shadowStubs` 这个修复**在本机测不出价值** —— 但**不应因此撤掉**

- **测出的**：在 lavapipe（CPU 软件 Vulkan）上，把 `shadowtex0/1`、`shadowcolor0`
  绑到本 render pass 自己的读写附件，与绑专用 1×1 桩，**画面逐像素相同**。
- **该保留的理由仍然成立**：Vulkan 规范明文禁止「同一 image 既作读写附件又作采样器」，
  这是**未定义行为** —— 规范允许驱动做任何事，包括丢 draw、给垃圾。
  `lavapipe` 恰好处理了，**不等于其它驱动也会**。
- 🔖 **但必须说清的边界**：本项目此前若给这个修复写过「修复后闪烁消失」之类的因果结论，
  **那是没有证据的**。本条只提供**规范层面**的依据，**不提供本机的复现证据**。
  （与 `h27` 撤回 `h24` 归因是同一条纪律：**实测没有的东西不要写成结论**。）

### 2.2 GAP-011 闪烁：**两条臂都复现不出来**

- A（修复）与 B（故意 UB）各连拍 6 帧，黑色占比 spread 分别为 **0.042 / 0.044 pp**
  ⇒ **没有任何闪烁**。
- ⇒ **本轮排除掉一个候选**：闪烁**不是**「shadowtex 绑读写附件这个 UB」造成的
  （至少在 lavapipe 上不是）。
- ⇒ GAP-011 的真实成因**仍未定位**，且与本轮之前一样**不可复现**。

---

## 三、🔖 方法学收获：这次对照是**受控的**，而 `h36` 那次不是

同一个存档、同一机位、同样 `dayTime=6000` + `weather=clear`：

| 对照 | HUD 差异 | 判定 |
|---|---|---|
| `h36` OpenGL vs Vulkan | **30.62%** 像素差 >8 | 🔴 **不成立** —— 连原版 HUD（根本不经过 vkdisp）都差 30% |
| **本轮 A vs B** | **0.00%** | ✅ **成立** —— HUD / hotbar / 地形全部 0.000 |

🔖 **HUD 恰好是不受本项目影响的原版 UI**，因此它是「这两次采图是否对齐」的天然对照物。
本轮 HUD 差 0.000 ⇒ 两次采图**确实对齐**；
`h36` HUD 差 30% ⇒ 那次**根本没对齐**，据此下的任何结论都不成立。
**这反过来验证了 `h36` 拒绝下结论是对的。**

---

## 四、实验设计（为什么这组是单变量）

- `diff /tmp/ab-base.toml run/config/vkdisp-client.toml` 输出**恰好一行**：
  ```
  52c52
  < 	shadowStubs = true
  ---
  > 	shadowStubs = false
  ```
  ⇒ 两组配置**逐项列出全部差异并证明只有一个变了**（X52）。
- 时间 / 天气 / 机位用 MCP 固定：`set_time 6000`、`set_weather clear`、`look yaw=90 pitch=8`。
- 两组都走 `run-client.sh`（Vulkan 硬断言），日志里 `backend=Vulkan` 各确认一次。
- 取证后 `cp /tmp/ab-base.toml` 还原配置，`diff` 输出 `IDENTICAL`。

---

## 五、🔴 本轮**没有**做到的事（如实列出）

| 项 | 状态 |
|---|---|
| **性能结论** | ❌ **按用户指令不做**。且设备是 lavapipe CPU 软件 Vulkan，帧率本就无参考价值 |
| **validation layer** | ❌ **仍然没有**（已查：系统与 prefix 均无 `VkLayer_khronos_validation`）。设备支持 `VK_EXT_debug_utils` 但 vkdisp 的 drain 仍报 `channel unavailable or empty` ⇒ 按 X35 **不得**说「无 validation error」 |
| GAP-011 真实成因 | ❌ **仍未定位**，本轮只是又排除一个候选 |
| 其它驱动的行为 | ❌ 测不到（本机只有 lavapipe 一个 Vulkan 实现） |
| `.java` 改动 | ❌ 本轮**零改动**（纯功能验证轮） |

---

## 六、复算 / 自检命令

```bash
# 两组的配置差异（必须恰好一行）
diff /tmp/ab-base.toml run/config/vkdisp-client.toml

# 两组的后端（必须都是 Vulkan）
grep 'vkdisp: backend=' run/logs/latest.log

# 连拍帧的黑色占比与 spread
python3 /tmp/flicker.py

# A vs B 分带对比
python3 /tmp/abdiff.py

# 单变量纪律本身也有守卫
./gradlew test --tests 'dev.vkdisp.VulkanEvidenceDisciplineTest'
```

---

## 七、自检

- [x] A/B **配置差异恰好一行**，且把 diff 原文贴了出来（X52 单变量对照）
- [x] 时间 / 天气 / 机位用 MCP 固定并写进 §四
- [x] 结论是**否定式**的也照实写：修复在本机测不出差别、闪烁两条臂都复现不出
- [x] 明确说清「测不出 ≠ 该撤掉」，并区分**规范依据**与**本机复现证据**
- [x] 顺带用 HUD 差异（0.000 vs 30.62%）**验证了两组对照的成立性差异**
- [x] 用户指令「只验功能、不考虑性能」已落成 `00-INDEX` 的取证铁律 + 构建期守卫
- [x] 如实列出本轮**没做的事**（§五）


---

## 八、补充核实：全盘搜到的 validation layer 是 **Windows 版**

`h37` 提交后补了一次**全盘** `find`（此前只搜了 prefix 与系统路径）：

```
$ find / -iname 'VkLayer_khronos_validation*' -not -path '/proc/*'
/mnt/d/APP/steam/bin/cef/cef.win64/VkLayer_khronos_validation.dll
/mnt/d/APP/steam/bin/cef/cef.win7/VkLayer_khronos_validation.dll
/mnt/d/APP/steam/bin/cef/cef.win7x64/VkLayer_khronos_validation.dll
```

🔖 **全是 Windows `.dll`**（Steam 自带 CEF 的），**Linux 的 Vulkan loader 加载不了 `.dll`**
⇒ 本机**没有任何可用的 validation layer**（prefix 与系统路径都没有 Linux 版 `.so`）。

记录这一点是因为：否则将来有人搜到这三个文件会以为「本机是有 validation layer 的」，
从而据此对 UB 下「跑过了没报错」的错误结论。
