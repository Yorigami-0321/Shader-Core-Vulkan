# h18 · 二分第 2 步：**候选②排除**（跳过 `lighting().setupFor(LEVEL)` 闪烁仍在）+ GAP-010 时序铁证

> 任务来源：`AGENT_CONTEXT.md` §10.23 ⑦（GAP-011 二分）+ 用户报告的「进游戏时提示资源包加载失败」。
> 取证方式：新增诊断开关 `mrt.skipLightingSetup` → 客户端连拍 4 帧 → 用户现场确认闪烁仍在。
>
> 判定：🔴 **候选 ②（`lighting().setupFor`）已排除**；🔴 用户报告的「资源包加载失败」
      **定位到 GAP-010**，且拿到了**精确到秒的时序铁证**。

---

## 〇、一句话结论

按 §10.23 ⑦ 的纪律「**每步只动一处**」，这轮只跳 `gameRenderer.lighting().setupFor(LEVEL)`。
结果：**用户确认闪烁仍在** ⇒ 该调用**不是**泄漏源，候选 ② 排除。
同时，用户报的「资源包加载失败」在日志里找到了对应物 —— 12 条 `resourceLoad/ERROR`，
且**管线注册早于资源构建 1.32 秒** ⇒ 这是 **GAP-010** 的直接证据。

---

## 一、候选② 排除（本轮的主实验）

### 1.1 做法

新增诊断开关 `mrt.skipLightingSetup`（**默认 false = 保持当前行为**），
在 `MrtTerrainPass.drawTerrain` 的 pass 体内按开关跳过 `setupFor(LEVEL)`，并自报一次：

~~~
[INFO] vkdisp: [GAP-011] lighting().setupFor(LEVEL) **skipped** (diagnostic)
~~~

🔖 自报已确认出现（不是「以为跳了」）—— X45 纪律。

### 1.2 判定

| 项 | 值 |
|---|---|
| `lighting().setupFor(LEVEL)` 跳过 | ✅ 已确认（日志自报） |
| 地形 pass 仍在跑 | ✅ `terrain drawn into` 出现 |
| 模组总闸被跳过 | 0 次 ✅ |
| **闪烁** | ❌ **仍在**（用户现场确认；4 连拍仍 2 种画面） |

🔖 ⇒ **候选 ② 排除**。剩下的候选：

| # | 动作 | 状态 |
|---|---|---|
| ① | `RenderSystem.bindDefaultUniforms(renderPass)` | 🟡 待验 |
| ② | `lighting().setupFor(LEVEL)` | ❌ **本轮排除** |
| ③ | `inMrtPass` | 🟡 低（已有 `finally`） |
| **④** | **`draws.renderGroup(..., atlasSampler, atlas, false)` 的全局绑定** | 🔴 **新的头号嫌疑** |

---

## 二、🔴 新的头号嫌疑：`renderGroup` 的**全局绑定**

pass 体里这一行把**方块图集**交给了原版的 `renderGroup`：

~~~java
draws.renderGroup(ChunkSectionLayerGroup.OPAQUE, renderPass, atlasSampler, atlas, false);
~~~

🔖 原版 `ChunkSectionsToRender#renderGroups` 内部**惯例做法**是
`RenderSystem.setShaderTexture(0, atlas)` —— 那是**全局 shader texture 槽 0**，不是 pass 级绑定。
若如此，**原版随后/下一帧的任何从槽 0 取纹理的 pass**（典型就是天空）
拿到的会是**方块图集** ⇒ 天空采样到图集 ⇒ **黑色**。

🔖 **这与 h17 的观测完全吻合**：地形正常（我们自己画，用 pass 里的 atlas）、
**只有天空黑**（走全局槽 0）、**且闪烁**（状态跨帧残留 + 每帧重设时序不定）。

⚠️ **本轮未验证**（`renderGroup` 是原版 API，不能直接跳过 —— 跳过就没有地形了）。
验证方式应是：**读原版 `ChunkSectionsToRender#renderGroups` 的 sources jar 实现**，
确认它是否调 `setShaderTexture`。

---

## 三、🔴 用户报告的「资源包加载失败」= GAP-010（拿到时序铁证）

用户报：*「在刚进入游戏时有提示资源包加载失败」*。日志里找到对应物 ——
**12 条** `resourceLoad/ERROR`：

~~~
[resourceLoad/ERROR] [PipelineBuilder]: Couldn't find source for VERTEX shader
                          (vkdisp_pack:terrain_pack_adapter)
~~~

### 3.1 🔖 时序铁证（精确到毫秒）

~~~
11:50:20.804  [INFO] vkdisp: [GAP-003/A] terrain MRT derived pipelines registered: 6/6
                ← 管���【注册】成功
11:50:22.124  [ERROR] Couldn't find source for VERTEX shader
                            (vkdisp_pack:terrain_pack_adapter)
                ← 资源侧【构建】时，适配层的 VERTEX 源【还不存在】
~~~

🔖 **注册早于构建 1.32 秒** ⇒ 典型的**时序竞争**（GAP-010 的定义）。

### 3.2 为什么它没被当成致命错误

日志里能看出**资源重载了两次**（`composite source ready` 出现在 21.643 与 24.153），
第二次之后 12 条 ERROR 不再出现 ⇒ **自愈**。
🔖 所以 GAP-010 是「**非致命但明确失败**」：它不直接造成闪烁，
但它是**支柱②（稳定）的污点**，而且**用户能在 UI 上看到它**。

🔖 **优先级修正**：我此前把 GAP-011 排在 GAP-010 前面。
这个排序**仍然正确**（用户要求先解决闪烁），但两者应**并行推进**，
不能把 GAP-010 当成「无害的噪声」继续挂起 —— 用户能在界面上看见它。

---

## 四、净结果

| 项 | 状态 |
|---|---|
| 候选 ② `lighting().setupFor(LEVEL)` | ❌ **已排除**（自报确认 + 用户确认闪烁仍在） |
| 新增诊断开关 `mrt.skipLightingSetup` | ✅ 落地（默认关 = 当前行为） |
| 新头号嫌疑：`renderGroup` 的全局纹理绑定 | 🔴 提出，**未验证**（需读原版 sources） |
| GAP-010 时序铁证 | 🔴 **拿到**：注册 20.804 早于构建 22.124 达 **1.32 秒** |
| 「资源包加载失败」提示 | ✅ **对应上了**那 12 条 ERROR |
| 测试 | ✅ **699** 单测全绿 |

---

## 五、稳定（支柱②）

| 判据 | 本轮实测 |
|---|---|
| `resourceLoad/ERROR` | 🔴 **12**（GAP-010） |
| `Render thread/ERROR` | **2** |
| `Render thread/WARN` | 416 |
| `Couldn't parse GLSL` | **0** |
| 客户端崩溃 | 无 |
| 残留游戏进程 | **0** |
| 配置复原 | ✅ 总闸 `enabled=false`，`mrt.*` 全关 |

⚠️ 仍**不**声称「0 validation error」（本机无 Vulkan validation layer）。

---

## 六、测试

`./gradlew compileJava` ⇒ BUILD SUCCESSFUL。**699** 单测全绿（新增的是**诊断开关**，未改默认行为）。

---

## 七、本轮**没有**证明的

1. ⛔ **闪烁的根因仍未定位**（3 个候选排除/待验，新头号嫌疑未验证）。
2. ⛔ **`renderGroup` 是否真的调 `setShaderTexture`** 未读原版源码确认。
3. ⛔ **全黑相位为何连地形都没有**，仍未解释。
4. ⛔ GAP-008 仍未判定；GAP-007 / GAP-009 未动；M-04 仍需用户裁决。

---

## 八、下一轮入口（用户要求：**先解决闪烁**）

1. **验证新头号嫌疑**：读原版 `ChunkSectionsToRender#renderGroups` 的实现，
   确认是否调 `RenderSystem.setShaderTexture(0, atlas)`（**纯静态，不进客户端**）。
2. 若确认 ⇒ 在我方 pass **结束前**恢复槽 0（或改用 pass 级绑定绕开全局槽），
   再用「4 连拍 + 与 h14 对照」验证闪烁是否消失。
3. **GAP-010 并行**：把适配层源登记**提前**到管线注册之前，消掉那 12 条 ERROR
   （用户在 UI 上看得见，属支柱②）。

---

## 九、产物与哈希

| 文件 | sha256 |
|---|---|
| `evidence/h18-images/h18-P-skip-lighting.png` | `3c09ac2e8274e17cc62137e89a36f1cd2fff1a8fd125c3cd7ecb5b3e851a4058` |
| `evidence/h18-images/h18-Q-skip-lighting-2.png` | `7d964094cd1f5642a5c2e847813212739b84154bd8038498e5034955cc35a40a` |
| `run/logs/latest.log`（含 GAP-010 时序铁证） | `e649dde566512777c23fd5131d64564c39a7e11989deafb3c5409ec5f020fbd3` |

⚠️ 本轮截图**混入了暂停菜单**（MCP 无点击工具，无法关���），**不可用于判读天空颜色**；
闪烁判定以**用户现场确认**为准（4 连拍哈希 2 种，仅作为「画面在变」的佐证）。

收尾 `game_procs.sh kill` → 残留 0；配置已复原为默认。
