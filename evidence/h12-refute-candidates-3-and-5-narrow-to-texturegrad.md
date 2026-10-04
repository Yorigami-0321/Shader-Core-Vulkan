# h12 · 候选 3 与候选 5 **双双证伪**（开关自报已生效）⇒ 范围收敛到**一个算子**：`textureGrad`

> 任务来源：`AGENT_CONTEXT.md` §10.17 ⑧（重跑实验 2，判读这次要可信）。
> 取证方式：X45 自报生效 → MCP 客户端单变量 A/B → 逐行静态比对默认路径与高级路径。
>
> 判定：🟡 **重大收窄**：三个候选被实验否掉，范围从「整条光照链」缩到**唯一一个算子**。
> 但该算子**尚未被实验验证**（需要再一趟客户端），GAP-008 仍开着。

---

## 〇、一句话结论

本轮两次实验都**拿到了可信判读**（日志自报开关已开启），两次都**证伪**了目标候选：
候选 3（`lmCoord`/天光）与候选 5（视差分支）**都不是压零项**。
最后靠**逐行比对工作路径与坏路径**，锁定唯一一个「坏路径有、工作路径没有」的 `albedo` 算子：
**`albedo = textureGrad(texture_0, newCoord, dFdx(texCoord), dFdy(texCoord))`。**

---

## 一、实验 A：候选 3（`lmCoord` 天光）⇒ **证伪**

🔖 这一趟的判读是**可信的**（X45 生效）：

~~~
vkdisp: 顶点适配层已生成：varyings=15（常量供值 7 条：…） **lmCoord=满光照诊断开关=已开启**
integratedServer = true
vkdisp: [GAP-003/A] terrain drawn into 8 attachment(s) pass
~~~

画面**仍是纯黑剪影**。⇒ `lmCoord` 的天光通道**不是**压零项。

推导复核（说明这不一次「猜错」而是真证伪）：若 `lmCoord=(1,1)`，则
`newLightmap = pow(1,10)*1.6 + 1*0.6 = 2.2`、`blocklightCol ≈ 0.42` ⇒ `blockLighting ≈ 2.0`，
画面本应**过曝发白**。它却仍然恰好全黑 ⇒ 说明 `albedo` 在**进入 `GetLighting` 之前**就已经是 0。

---

## 二、实验 B：候选 5（视差分支）⇒ **证伪**

探针：`dist = 1000.0` ⇒ `parallaxFade = clamp((1000-64)/32, 0, 1) = 1.0` ⇒
命中 `GetParallaxCoord` 的早退 `if (parallaxFade >= 1.0 || …) return texCoord;` ⇒
**视差分支整体跳过**、`newCoord = texCoord`。只改 `dist` 一个 varying（单变量）。

~~~
vkdisp: 顶点适配层已生成：… lmCoord=满光照诊断开关=关 **dist=视差跳过诊断开关=已开启**
integratedServer = true
~~~

画面**仍是纯黑剪影**。⇒ 视差分支**不是**压零项。

---

## 三、🔴 关键推进：坏路径与工作路径的**唯一 `albedo` 差异**

两条路径 `main()` 的**第一行逐字相同**：

~~~glsl
// 默认配置（1 槽，画面正确，h08-B 可证）
void main() {\n    vec4 albedo = texture(texture_0, texCoord) * vec4(color.rgb, 1.0);

// 高级材质（8 槽，画面全黑）
void main() {\n    vec4 albedo = texture(texture_0, texCoord) * vec4(color.rgb, 1.0);
~~~

⇒ **候选 4（`texture` 采样 / `color` / `texture_0` 本身）被结构性排除**：同样的表达式在
默认路径上算出的是可见的暖色地形（h08-B 截图）。

**但紧接着，坏路径把 `albedo` 整个重算了一遍**：

~~~glsl
vec2 newCoord = vTexCoord.st * vTexCoordAM.pq + vTexCoordAM.st;
if (skipParallax < 0.5) {
    newCoord = GetParallaxCoord(texCoord, parallaxFade, surfaceDepth);
    albedo = textureGrad(texture_0, newCoord, dcdx, dcdy) * vec4(color.rgb, 1.0);  // ← 覆盖！
}
~~~

`textureGrad` 出现次数实测：

| 路径 | `textureGrad` 次数 |
|---|---:|
| 默认配置（1 槽，画面正确） | **0** |
| 高级材质（8 槽，画面全黑） | **7** |

其中 `vec2 dcdx = dFdx(texCoord);`（第 293 行）—— 屏幕空间导数。

🔖 **这是目前唯一一个「坏路径有、工作路径没有」的 `albedo` 算子。**
并且实验 A/B 都已经证明：`GetMaterials`（ao/金属度）、天光通道、视差分支都**不是**它 ——
剩下能解释「恰好 0」的地方就在这条重采样上。

⚠️ **尚未实验验证**（本轮预算已用尽）。两个待查子项：
① 我们绑给 `texture_0` 的方块图集**mip 链是否可用**（`textureGrad` 用显式 LOD；
   若图集只有 1 级 mip 而 LOD 被算成 >0，或各级 mip 未填充 ⇒ 采样结果可以是 0）；
② `dFdx(texCoord)` 在本引擎的**反向 Z / MRT pass** 下是否退化为 0。

---

## 四、本轮新增的可观测能力

**探针从 1 个变 2 个，且都自报状态**（X45 的延续）：

| 开关 | 改哪个 varying | 目的 | 日志自报 |
|---|---|---|---|
| `mrt.terrainFullLightProbe` | `lmCoord` | 天光通道 | `lmCoord=满光照诊断开关=已开启/关` |
| `mrt.terrainParallaxSkipProbe` | `dist` | 视差分支整体跳过 | `dist=视差跳过诊断开关=已开启/关` |

🔖 两个开关互不干扰（实验 B 里 `lmCoord` 报「关」、`dist` 报「已开启」），
所以每一趟都是**严格单变量**。

---

## 五、净结果

| 候选 | 内容 | 判定 | 依据 |
|---|---|---|---|
| 1/2 | `ao*ao`、`1-metalness*smoothness` | ❌ 证伪（`h10`） | 中性材质贴图后 48.41% 像素变了但仍黑 |
| 3 | `skylightSqr = lightmap.y²` | ❌ **证伪（`h12`）** | 开关自报已开启，画面仍恰好全黑 |
| 4 | 首行 `texture(...) * color` | ❌ **结构性排除** | 默认路径同一表达式算出可见画面 |
| 5 | 视差分支 | ❌ **证伪（`h12`）** | `dist=1000` 早退跳过，画面仍黑 |
| **6** | **`textureGrad(texture_0, …)` 重采样** | 🟡 **唯一剩余，未验证** | 坏路径 7 次 / 工作路径 0 次 |

---

## 六、稳定（支柱②）

| 判据 | 值 |
|---|---|
| `Couldn\'t parse GLSL` | **0** |
| `vkdisp` ERROR | **0** |
| 客户端崩溃 | 无（两趟都进世界并画完 pass） |
| 残留游戏进程 | **0** |
| 非 vkdisp ERROR | 环境性（narrator / authlib / OpenAL） |

⚠️ 仍**不**声称「0 validation error」（本机无 Vulkan validation layer）。

---

## 七、测试

`./gradlew build` ⇒ BUILD SUCCESSFUL，**691** 单测全绿（生成器签名加第二个探针参数，
`PackVertexAdapterGeneratorTest` 7 例与 `PackTerrainSourceTest` 同步跟改）。

---

## 八、本轮**没有**证明的

1. ⛔ **候选 6 未验证**，GAP-008 仍开着（但范围已缩到一个算子）。
2. ⛔ 图集 mip 链与 `dFdx` 的实际行为**未取证**。
3. ⛔ GAP-010 的 12 条 `Couldn\'t find source` 仍未修。
4. ⛔ 地形仍只画进我方 pass ⇒ **仍不产出用户可见画面改进**（M-04 未做）。
5. ⛔ GAP-007 常量项 7 条、GAP-009 真材质贴图集未实现。

---

## 九、下一轮入口（按序）

1. **候选 6 的探针**：把适配层的 `texCoord` 供成**常量**（或让片元用 `texture()` 而非
   `textureGrad()`）—— 前者可在**顶点侧**完成，只需再加一个单变量开关：
   「令 `dcdx/dcdy` 为 0」。若 `textureGrad(…, vec2(0), vec2(0))` 变成亮 ⇒ **根因坐实**。
2. 同时静态核查**方块图集的 mip 链**：`blockAtlas()` 的 GpuTexture 是几级 mip、是否都填充过。
3. **GAP-010**：对齐适配层资源登记与管线注册的时序，消掉那 12 条 ERROR（支柱②）。
4. GAP-007 / M-04（仍需用户裁决）。

---

## 十、产物与哈希

| 文件 | sha256 |
|---|---|
| `evidence/h12-images/h12-F-probe-confirmed-on.png` | `f195fee1f6e02f322c5de50ec5cdc92d18896709be0082903019fe5bec3e4d89` |
| `evidence/h12-images/h12-G-parallax-skip.png` | `5daa672ef63d451f74414f7eca36feac81adb3beb1b08e87ffb8c16885be2acd` |
| `run/logs/latest.log`（本趟） | `0b379f11b3573a276e58a8b75b032b09a2fcbf478ffd8eece8dd9c4f311d5f0e` |

MCP 回执：两趟均 `get_status → integratedServer=true`；`set_time`、`look`、`screenshot` 全部成功。
收尾 `game_procs.sh kill` → 残留 0。
