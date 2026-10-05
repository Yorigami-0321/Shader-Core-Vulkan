# h44 · 🔴🔴 `terrainToMain=true` 档**首次产出有效两源对照** ⇒ GAP-008 在 Vulkan 上定位到「只有 albedo ≡ 0」

> 取证方式：新增「包声明写哪些槽」契约 + 档位敏感判读，经 **MCP 驱动**在本机 **Vulkan 后端**（lavapipe）实测。
> 判定：🟢 **GAP-008 在 Vulkan 上完成正面定位**，且比 `h13`（OpenGL）多排除了一层（落点因素）。
> ⚠️ 本轮**推翻了自己上一轮的两个结论**（`h43` §③④ 与本项目一条长期注释），详见 §五。

---

## 〇、一句话结论

`mrt.terrainToMain=true` 档下：**主目标（= 我方 pass 的附件 0 = 包的 albedo）逐像素恰好 0，
而同一 pass、同一 draw、同一片元的 `colortex3` / `colortex6` / `colortex7` 三路输出形态正常。**

⇒ ① 地形 draw **确实落到了**主目标（不是落点/接线问题）；
⇒ ② 整条 draw **确实产出了片元**（不是几何/深度/管线全丢）；
⇒ ③ 唯独 **albedo 那一路输出 ≡ 0** —— 这正是 GAP-008 的定义形态，**在 Vulkan 上首次被正面证明**。

同一存档/机位/时刻，在 `terrainToMain=false` 档独立复现：`colortex0`（albedo）全黑、`3/6/7` 有内容。

---

## 一、🔴🔴 上一轮的「阻塞项」本身是错的

`h43` §④ 登记的阻塞是：

> 该手段在 `toMain` 档**需要 ≥2 个附件才可能生效**，而 BSL 默认配置只写槽 0 ⇒ **该档仍是下一步**。

这条**不成立**。实测与源级核实：

| 项 | `h43` 的说法 | 实测 |
| --- | --- | --- |
| BSL 默认配置写的槽 | 只有槽 0 | **`[0, 3, 6, 7]`**（4 槽） |
| 附件数 | ≥2 才可能生效 | **8** |

**为什么会算错**：`MrtPlan` 的旧注释写着「`ADVANCED_MATERIALS`/`MCBL_SS` 在 BSL 里**默认注释掉**
⇒ 默认配置下地形只写 colortex0」。这条注释是**错的**，来源是本项目把它当成了前提继续往下推：

```
shaders.properties（BSL v10.1.8 实测全文）
  → 没有任何 option.* 行；profile.* 五档里也**不含** ADVANCED_MATERIALS
  ⇒ 该选项取默认 true（实测佐证：地形片元契约 varyings=15 / samplers=7，
     正是高级材质路径的形状）
shaders/lang/en_US.lang + 扫包日志
  → MCBL_SS 是包**自己声明**的 option.MCBL_SS type=BOOLEAN **default=false**
  ⇒ 默认档走 #else 分支里那条活标记 /* DRAWBUFFERS:0367 */
```

`/* DRAWBUFFERS:0367 */` 管辖 `gl_FragData[0..3]` ⇒ 槽位表 `[0,3,6,7]` ⇒ `outputCount = 7+1 = 8`。

> **这是本项目「注释里的前提被当成事实」的第五例**（前四例：死开关 `mrt.gap010Regression`、
> 探针位置早于地形 pass、`toMain` 档拿 `colortex0` 当对照、`optionOverrides` 不进记忆键）。
> 已在 `MrtPlan` 里**就地更正**并写明实测依据。

---

## 二、🔴🔖 本轮修掉的假证据：槽 1 不是包写的槽

`h43` 的探针在 `toMain` 档**硬编码「改测槽 1」**。而槽 1 在默认档**没有任何片元输出**
（附件存在，但只有清屏值）⇒ 读它必然得到 `allZero=true`
⇒ 日志会报「包片元输出黑」= **结论反了**（真相是「那张图这一帧没有包的输出」）。

🔖 与「`toMain` 档拿 `colortex0` 当对照」是**同一族**（把「附件存在」当成「附件被写了」），
只是这次发生在**另一个槽号**上 —— 说明「写死一个槽号」这件事本身就是缺陷形态。

修法不是把 1 换成 3，而是**让「挑哪一槽」变成契约**：

- `PackTerrainProgram` 新增 `declaredOutputSlots()`（转译终稿里 `layout(location=N) out` 的全集）
  与 `declaresOutputSlot(int)`；
- `MrtPlan` 新增 `freezePackProgram(outputs, declaredSlots)` —— **附件数与槽位集合同一次冻结**
  （分两条通道就会出现「附件按新契约、槽位按旧契约」，两者矛盾而日志全正常）；
- 新增 `pipeline/model/PixelProbePlan`（纯逻辑、可单测）负责挑槽，探针只消费结果。

注册期证据行现在**同时**打出三个数（`[05Oct2026 17:13:47]`，逐字）：

```
vkdisp: [GAP-003] MRT terrain pipelines will use pack fragment:
  program=world0/gbuffers_terrain colorTargets=8
  declaredOutputSlots=[0, 3, 6, 7] samplers=7 varyings=15
  unwrittenAttachments=[1, 2, 4, 5]
```

⇒ 附件 1/2/4/5 的存在**不再**会被误读成「被写了」。

---

## 三、🔴🔖🔖 第二处假证据：判读标签**不认档位**，会把已定位的 GAP-008 读成接线 bug

探针第一次在该档给出有效对照时，打的是：

```
NOT_ON_MAIN：colortex 有内容而主目标逐像素全黑
  ⇒ 地形 draw **没有落到主目标**（落点/接线问题，不是着色问题）
```

**这句话在该档下正好说反了。** 因为 `terrainToMain=true` 时「主目标」就是我方 pass 的
**附件 0**，也就是包的 albedo —— 于是「主目标黑 + colortex3 有内容」的真相是
「draw **落到了**主目标，只是那里 albedo ≡ 0」。

🔖 关键在于**同一组布尔量在两档下含义相反**：

| 读数 | `terrainToMain=false`（主目标 = 原版画面） | `terrainToMain=true`（主目标 = 附件 0） |
| --- | --- | --- |
| 主黑 + 槽有内容 | `NOT_ON_MAIN` = 没落到主目标 | `ALBEDO_BLACK_OTHERS_OK` = **落到了**，albedo ≡ 0 |
| 主有内容 + 槽黑 | `SLOT_BLACK` = 该路输出黑 | `SLOT_BLACK_ALBEDO_OK` = 反过来的那一格 |
| 都黑 | `BOTH_BLACK` | `ALL_BLACK` |
| 都有内容 | `BOTH_HAVE_CONTENT` | `BOTH_HAVE_CONTENT` |

⇒ 复用一张与档位无关的标签表，会把**已经定位的 GAP-008** 读成「有个接线 bug」，
把下一轮取证引向一个不存在的问题。

修法：新增 `pipeline/model/PixelProbeVerdict`（纯函数，**输入必须含档位**），
两档各自的结论名与解释全在里面；单测钉住「两档只在『都有内容』这一格同名」。

---

## 四、Vulkan 后端实测（逐字日志）

### 4.1 后端确认（两轮都是 Vulkan）

```
[05Oct2026 17:13:47.681] [modloading-sync-worker/INFO] [dev.vkdisp.VkDisp/]:
  vkdisp: backend=Vulkan, device=llvmpipe (LLVM 23.1.1, 256 bits)
Using graphics backend Vulkan, using drivers: 1.4.354 llvmpipe Mesa 26.2.4-arch1.1
```

⚠️ 本机**无 Vulkan validation layer**（全盘搜索到的 `VkLayer_khronos_validation` 都是 Windows `.dll`）
⇒ 本文**不含**任何「无 validation error」式断言。

### 4.2 观测面（沿用 `h43` 的固定面，便于逐位对比）

同一存档 / 机位 `yaw=35, pitch=-8` / `dayTime=6000` / 天气 clear；
配置组合 `mrt.terrain=true` + `terrainAfterLevel=true` + `packTerrainShader=true` + `shadowStubs=true`
+ `capabilityGate=false`，仅 `terrainToMain` 在两档间切换。
地形 draw 实测提交量：`SOLID{groups=1,draws=741} CUTOUT{groups=1,draws=619} TRANSLUCENT{groups=1,draws=192}`
⇒ **draw 确实在提交**（排除「一条 draw 都没提交」）。

### 4.3 A 臂 `terrainToMain=true`

```
测槽决策: toMain=true attachments=8 测=[3, 6, 7] comparable=true truncated=false
  —— 槽 0 已被换成主目标视图，colortex0 这一帧**不是附件**；
     主目标因此就是包的槽 0（albedo）本身，对照改测同档**确实被写**的槽 [3, 6, 7]
```

| 源 | meanRGB | mean_luma | nonBlack | maxR | allZero |
| --- | --- | --- | --- | --- | --- |
| `main`（= albedo） | (0.0000, 0.0000, 0.0000) | 0.0000 | 0.000% | 0 | **true** |
| `colortex3` | (0.0000, **255.0000**, 0.0000) | 182.3760 | 100.000% | 0 | false |
| `colortex6` | (**128.0000, 218.0000, 255.0000**) | 201.5374 | 100.000% | 128 | false |
| `colortex7` | (197.7745, 197.7745, 197.7745) | 197.7745 | 100.000% | 255 | false |

结论（三槽**各自**独立报出，`ALBEDO_BLACK_OTHERS_OK`）：

> 主目标逐像素全黑而 `colortexN` 有内容 ⇒ 地形 draw **确实落到了**主目标，只是 albedo ≡ 0，
> 而同一片元的其余输出形态正常。**这是 GAP-008 的定义形态，不是落点/接线问题。**

### 4.4 B 臂 `terrainToMain=false`（配置热加载触发，未重启）

```
config hot-reload: … mrt.terrainToMain=false … -> resource reload
测槽决策: toMain=false attachments=8 测=[0, 3, 6, 7] comparable=true truncated=false
```

| 源 | mean_luma | nonBlack | maxR | 结论 |
| --- | --- | --- | --- | --- |
| `main`（原版画面） | 16.0991 | 46.209% | 243 | — |
| `colortex0`（albedo） | **0.0000** | **0.000%** | **0** | `SLOT_BLACK` |
| `colortex3` | 182.3760 | 100.000% | 0 | `BOTH_HAVE_CONTENT` |
| `colortex6` | 201.5374 | 100.000% | 128 | `BOTH_HAVE_CONTENT` |
| `colortex7` | 197.7745 | 100.000% | 255 | `BOTH_HAVE_CONTENT` |

⇒ 两个档位**独立**给出同一结论：albedo 那一路 ≡ 0，其余正常。

### 4.5 3/6/7 的取值形态**与包源码逐字对得上**（排除「诊断色/清屏色混入」）

源（`shaders/program/gbuffers_terrain.glsl` 438–442 行，默认档活分支）：

```glsl
/* DRAWBUFFERS:0367 */
gl_FragData[1] = vec4(smoothness, skyOcclusion, 0.0, 1.0);   → colortex3
gl_FragData[2] = vec4(EncodeNormal(newNormal), …);           → colortex6
gl_FragData[3] = vec4(fresnel3, 1.0);                        → colortex7
```

- `colortex3 = (0, 255, 0)` ⇒ `smoothness=0`（本机无 specular 贴图）+ `skyOcclusion=1.0`
  = **「无 specular + 全天空遮蔽」的正确取值**；
- `colortex6 = (128, 218, 255)` ⇒ 法线编码，`b=255` 指向近平面（法线朝观察者），形态合理；
- `colortex7 = (197.77, …)` ⇒ 菲涅尔近乎常量，与「平地 + 固定视角」相符。

🔖 这同时排除了「这些数字是清屏色或诊断色」：本档清屏是 `NEUTRAL` 全 0
（`mrt.enabled=false` ⇒ 诊断色不启用），而三槽实测**非零且互不相同**。

### 4.6 与屏幕截图交叉核对

MCP 截图（854×480，最终帧含 GUI）同一采样区：`meanRGB=(0.3521,0.3521,0.3521)`、
`nonBlack=0.138%`、`maxR=255`。
⇒ 与探针的 `main allZero=true` **一致**；那 0.138% 非黑像素是 GUI（准星/物品栏），
不属采样语义内。🔖 探针读的是 GUI 合成**之前**的主目标，所以数字更干净。

---

## 五、本轮推翻/更正的既有结论

| 编号 | 既有说法 | 本轮实测 | 处置 |
| --- | --- | --- | --- |
| `h43` §④ | 「`toMain` 档需 ≥2 附件，而 BSL 默认只写槽 0 ⇒ 该档做不了对照」 | 默认写 `[0,3,6,7]`、8 附件 | **作废**（前提来自一条错注释） |
| `MrtPlan` 旧注释 | 「`ADVANCED_MATERIALS`/`MCBL_SS` 默认注释掉 ⇒ 默认只写 colortex0」 | `ADVANCED_MATERIALS` 默认 **true**、`MCBL_SS` 包声明 **default=false** ⇒ 走 `0367` | **就地更正**并附实测依据 |
| `h43` 探针 | `toMain` 档「改测槽 1」 | 槽 1 无包输出 ⇒ 读它是清屏值 = 假证据 | **改为契约驱动**（`PixelProbePlan`） |
| `h43` 探针判读 | 四格标签表不认档位 | 该档下 `NOT_ON_MAIN` 说反了 | **改为档位敏感**（`PixelProbeVerdict`） |
| `h42` §4.3 | 「输出黑」与「没落到主目标」本轮未分开 | 本轮**已分开**（A/B 两档各自独立给出） | **收口** |

---

## 六、GAP-008 现在的状态

**已定位（在 Vulkan 上，正面证据）**：包地形片元 `gbuffers_terrain` 的 **albedo 那一路输出 ≡ 0**，
同一片元的 smoothness/skyOcclusion、normal、fresnel 三路输出形态正常。

**已排除**：落点/接线问题、整条 draw 未出片元、附件绑定错、几何/深度全丢、清屏色/诊断色混入。

**仍开放**：albedo 为什么是 0。`h13`（OpenGL）已把范围收敛到
`albedo = textureGrad(texture_0, newCoord, dcdx, dcdy) * vec4(color.rgb, 1.0);`
（视差分支的第二次赋值），且已排除 `color`（`h28`）、`lmCoord`（`h10`）、导数（`h14`）。
🔴 **本轮不做性能结论**（按指令）；下一步若继续，应针对 `newCoord` / `textureGrad` 采样结果本身，
而**不是**再在档位/落点层面找。

---

## 七、纪律核对

- **§3.2 六项**：Vulkan 后端有逐字日志（§4.1）；无 validation layer 的限制已声明（§4.1 ⚠️）；
  未做任何性能断言（§六 🔴）；`tools/` 的取证环境修复不随仓库分发（`libvulkan_lvp.so` 匹配件在 `/tmp/vksw/`）。
- **单测**：886 条全绿（本轮 +27：`PixelProbePlanTest` 12、`PixelProbeVerdictTest` 7、
  `PackTerrainProgramTest` +4、`PixelProbeWiringTest` 改写后净 +4）；
  `./gradlew build` 退出码 0。
- **残留**：取证结束后 `game_procs.sh kill` + `count` 已清零；隔离车道配置已还原。

---

## 八、代码位置

- `pipeline/model/PixelProbePlan.java`（新）：测槽决策（挑哪些槽、能不能对照、为什么）
- `pipeline/model/PixelProbeVerdict.java`（新）：**档位敏感**的两源判读
- `pipeline/model/PackTerrainProgram.java`：`declaredOutputSlots()` / `declaresOutputSlot(int)`
- `pipeline/model/MrtPlan.java`：`freezePackProgram(outputs, slots)` 同次冻结 + 错注释更正
- `bridge/TargetReadback.java`：消费上面两个决策；对照状态改按源分别记（多槽不再互相吃掉结论）
- `bridge/TerrainPipelineApi.java`：注册期打出 `declaredOutputSlots` 与 `unwrittenAttachments`
- 测试：`pipeline/model/PixelProbePlanTest`、`pipeline/model/PixelProbeVerdictTest`、
  `pipeline/model/PackTerrainProgramTest`、`bridge/PixelProbeWiringTest`