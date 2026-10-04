# h09 · DRAWBUFFERS 槽位映射兑现 + BSL 布尔选项可开 ⇒ 高级材质的 8 槽 gbuffer 真正跑起来

> 任务来源：`docs/AGENT_CONTEXT.md` §10.14 ⑨ 与 GAP-003 的第 ⑤ 条遗留
> 「若要支持 `ADVANCED_MATERIALS`：附件顺序须**服从 DRAWBUFFERS 而非下标**（否则静默绑错槽）」。
> 取证方式：**MCP 驱动真实客户端**。
>
> 判定：✅ **通过（带一条明确的画面不正确）**。槽位映射按 `DRAWBUFFERS:0367` 落到 colortex **0/3/6/7**，
> 附件数 **8**，管线与 pass 两侧一致，0 崩、0 `ShaderCompileException`、0 条 vkdisp ERROR。
> ⛔ 但 8 槽画面是**剪影全黑** —— 几何与槽位路由对，像素值不对，原因已定位并登记（见 §六）。

---

## 〇、一句话结论

**`gl_FragData[k]` 不等于 `location k` 这条静默错误被消灭了**：包说写 colortex3/6/7，我们就写 3/6/7。
顺带挖出并修掉一个更靠前的阻塞：**BSL 的 483 个布尔开关此前既不可见也不可改**（284 个枚举选项里
连 `ADVANCED_MATERIALS` 都没有）⇒ 多槽路径根本**无法被触发**。

---

## 一、一行复现

~~~bash
# 打开包选项（本文件不进 FML 监听，靠内容变更 + 切包触发重载）
printf 'BSL_v10.1.8.ADVANCED_MATERIALS=true\nBSL_v10.1.8.REFLECTION_SPECULAR=true\n' \
  >> run/config/vkdisp-pack-options.properties

# run/config/vkdisp-client.toml：mrt.terrain / terrainAfterLevel / terrainToMain / packTerrainShader 全 true
bash tools/vulkan-local/game_procs.sh count        # 残留必须为 0
source tools/vulkan-local/env.sh && ./gradlew runClient -PquickPlay

python3 tools/mcp-drive.py set_time '{"time":6000}'
python3 tools/mcp-drive.py look     '{"yaw":35,"pitch":-12}'
python3 tools/mcp-drive.py screenshot evidence/h09-images/h09-C-adv-materials-8slots.png
~~~

---

## 二、🔴 阻塞一：`gl_FragData[k]` 被当成 `location k`（静默绑错槽）

### 2.1 核实（GLSL 公开语义 + BSL 源码）

`gl_FragData[n]` 是 GLSL 1.20 及以前的片元内建输出数组，**与 `location n` 没有必然关系**；
槽位由引擎按包源码里的 `/* DRAWBUFFERS:… */` 注释兑现。BSL v10.1.8
`shaders/program/gbuffers_terrain.glsl` 实测（424/428/432/439 行）：

| 标记 | 管辖的 `gl_FragData` | 落点 |
|---|---|---|
| `DRAWBUFFERS:0` | `[0]` | colortex0 |
| `DRAWBUFFERS:08` | `+[1]` | colortex8 |
| `DRAWBUFFERS:0367` | `+[1..3]` | **colortex3 / 6 / 7** |
| `DRAWBUFFERS:08367` | `+[2..4]` | colortex3 / 6 / 7 |

🔖 **标记是累积的，不是单条的** —— 一条标记管辖它到下一条标记之间的那些下标；
索引 k 的最终映射来自**最后一条长度 > k 的标记**。（首版实现按「必须唯一一条」写，跑出来才发现是两条。）

### 2.2 修法：新增转译第 **7½ 段** `DrawBuffersSlotAdapter`

- 排在 ⑦（合成 `layout(location = k) out vec4`）**之后**、⑧（注入内建 uniform）**之前**；
- **等行数**变换（只改已有声明行里的 location 数字）⇒ 行号映射完全不受影响；
- 「能力口径 vs 生产口径」的判别：源里若仍有 `#if` 族指令 ⇒ 多分支同时在场，**不改写**并出 INFO
  （转译终稿实测 `#if` 数为 **0**，所以生产链路上永远走生产分支）；
- **三条显式拒绝**（绝不静默夹取）：单条内槽位重复 / 需要超过 `maxColorAttachments = 8` / 标记未覆盖的输出。
  拒绝时出 ERROR 且 `failure() != null`，`PackTerrainSource` 据此**拒绝接线**（沿用原版 core/terrain）。

### 2.3 实测（客户端日志）

~~~
vkdisp: [GAP-003] MRT terrain pipelines will use pack fragment: program=world0/gbuffers_terrain colorTargets=8 samplers=7 varyings=15
vkdisp: [GAP-003/A] terrain MRT derived pipelines registered: 6/6 (colorTargets=8)
vkdisp: [GAP-003/A] gbuffer terrain targets ready: 854x480 slots=8 depth=D32_FLOAT
vkdisp: [GAP-003/A] terrain drawn into 8 attachment(s) pass (group=OPAQUE, main target untouched; draws=1)
vkdisp: [GAP-003/A] captured draw groups:  SOLID{groups=1,draws=610} CUTOUT{groups=1,draws=414} TRANSLUCENT{groups=1,draws=164}
~~~

配置里 `mrt.attachments = 3` **故意不改** ⇒ 管线与 pass 两侧都被改成 **8**（h08 的冻结机制继续生效）。

### 2.4 能力边界（如实登记，不夹取）

`DRAWBUFFERS:08367`（MCBL_SS + 高级材质**同开**）需要 **9** 个附件 > Vulkan 上限 8
⇒ 本引擎**显式拒绝接线**，不把 colortex8 悄悄夹掉。已写成断言。

---

## 三、🔴 阻塞二：BSL 的布尔选项**既不可见也不可改**（这才是真正的门槛）

### 3.1 核实：全包定义行形态分布

~~~
446 行  #define NAME          （裸宏，功能开）
 37 行  //#define NAME        （裸宏被注释 = 功能关）
278 行  #define NAME v //[..] （带值带候选 ⇒ 原规则已覆盖）
 13 行  #define NAME alias    （带值无候选 = 别名，不是选项）
~~~

原规则只认「带值 **且** 尾注有 `[...]` 候选表」⇒ 只让 278/774 行可见。
客户端实测：**284 个枚举选项里没有 `ADVANCED_MATERIALS`**，布尔选项数为 **0**。
⇒ 「把默认关闭的选项打开」这个动作**根本做不到** ⇒ 多槽路径无法被触发（h06 的全部结论只能停在纸面）。

### 3.2 修法（两处，缺一不可）

1. `ConstEvaluator`：`#define NAME` / `//#define NAME`（整行只有名字）= **BOOLEAN** 选项，
   候选表合成 `[true, false]`，默认值 = 注释掉 ? `false` : `true`；
   **带值但无候选表**的仍是别名（`#define colortexR colortex5`），不是选项；
   函数宏（`#define f(x) …`）因要求整行结束而天然排除。
   ⇒ 前缀守卫同步放行 `//#define`（与正则锚定条件逐字对应，仍可证明等价）。
2. `OptionSourceRewriter`：新增 `//#define NAME` 路径 —— `true` ⇒ 去掉 `//` 成为真定义，
   `false` ⇒ **保持原样**（本来就是关的，再补 `//` 会变成 `///#define` 污染包源）。

### 3.3 实测

~~~
vkdisp: composite source diagnostic: INFO: ./shaderpacks/BSL_v10.1.8.zip: 选项覆盖已改写进源: 命中 2/2 [ADVANCED_MATERIALS=true, SHARPEN=3]
vkdisp: 地形片元契约解析完成: program=world0/gbuffers_terrain outputs=8 samplers=7 varyings=15
~~~

枚举侧：BSL 选项 **284 → 386**，布尔选项 **0 → 102**，`ADVANCED_MATERIALS` = `Option[name=ADVANCED_MATERIALS, type=BOOLEAN, defaultValue=false, values=[true,false], screen=MATERIAL]`。

---

## 四、🔴 第三个问题：**静态顶点适配层撑不住**（只跑客户端才暴露）

### 4.1 症状（响亮失败，不是静默）

~~~
com.mojang.renderpearl.util.ShaderCompileException:
  Vertex shader (vkdisp:terrain_pack_adapter) missing output at location 14
  consumed by Fragment shader (vkdisp_pack:gbuffers_terrain)
~~~

开高级材质后包片元要 **15** 条 varying，而静态适配层只写死 9 条
⇒ 资源加载期抛异常 ⇒ **客户端根本进不了世界**（jstack 证实渲染线程空闲在 `limitDisplayFPS`，停在主菜单）。

### 4.2 根因与修法

包的 varying 集合**随配置变化**（默认 9 条 / 高级材质 15 条），静态资产对另一个配置就是「少供」。
⇒ 改为**按契约生成**：`glsl/translate/PackVertexAdapterGenerator` 逐条产出
`layout(location = N) out <type> <name>;` 与对应赋值，随片元源**同生共死**（杜绝半接线）。
静态资产 `assets/vkdisp/shaders/terrain_pack_adapter.vsh` 已删除（避免两份真源）。

### 4.3 三档供值，绝不假装

| 档 | varying | 来源 |
|---|---|---|
| ✅ 真值 | `texCoord` `lmCoord` `color` `sunVec` `upVec` `eastVec` `viewVector` `dist` | 原版地形顶点格式 + `VkDispTerrainParams` 真算 |
| ⚠️ 常量（GAP-007） | `mat` `recolor` `normal` `binormal` `tangent` `vTexCoord` `vTexCoordAM` | 原版顶点缓冲无对应属性 ⇒ 按常量供并记账 + WARN |
| ⚠️ 类型零值 + WARN | 任何不认识的名字 | 逐条 WARN，**绝不猜一个像的值**（X9） |

客户端实测：

~~~
vkdisp: 顶点适配层已生成：varyings=15（常量供值 7 条：mat, recolor, normal, binormal, tangent, vTexCoord, vTexCoordAM）
~~~

---

## 五、画面判据（MCP 截图）

`h09-C-adv-materials-8slots.png`（同一存档 / `time set 6000` / `yaw=35,pitch=-12`）：
**绿色清屏底上是地形剪影，全黑**。

| 读到的 | 说明 |
|---|---|
| 地形轮廓清晰可辨、`draws=610` | 几何链路通：顶点适配层供的 15 条 varying 足以让片元跑完整 |
| 剪影**纯黑** | colortex0 拿到的是**未被照亮的 albedo**（deferred 的光照在后续 pass），且受常量项影响 |
| HUD / 准星 / 手部正常 | 清屏只影响我方 pass 的附件 0，未污染 UI |

对比 h08-B（默认配置 1 槽）：那张地形是**可见且被 BSL 处理过**的暖色调。
⇒ **默认配置路径的画面是对的；高级材质路径的几何与槽位是对的、像素值不对。**

---

## 六、⛔ 8 槽画面为什么黑（已定位，未修）

高级材质路径下 BSL 的 `gbuffers_terrain` 会用**本轮按常量供值**的那 7 条
（`normal` / `tangent` / `binormal` / `mat` / `vTexCoord*` …）参与光照与材质分支计算；
再叠上 `specular` / `normals` 两个新采样器**绑的是方块图集占位视图**，
最终乘积把 colortex0 压到 0。

🔖 **这不是接线错误**：接线错误的表现是「崩」或「绑到别的槽」，而这里是「跑完了、值不对」。
⚠️ **仍不能声称根因已坐实** —— 本轮没有逐项二分验证是哪一条造成压零
（要做需逐条切常量项重跑，属下一轮）。**登记为 GAP-008，不假装已修。**

---

## 七、稳定（支柱②）

| 判据 | 实测 |
|---|---|
| `vkdisp` ERROR 行数 | **0** |
| `ShaderCompileException` / `missing output at location` | **0**（修前 ≥ 3） |
| `Missing uniform`（布局漏绑） | **0** |
| 客户端崩溃 | 无 |
| 残留游戏进程 | **0** |
| 非 vkdisp 的 ERROR | 2 条环境性：narrator（缺 flite）、OpenAL（无音频设备） |

⚠️ 仍**不**声称「0 validation error」（本机无 Vulkan validation layer，沿用 §9.4.15 纪律）。

---

## 八、测试

~~~
./gradlew build  →  BUILD SUCCESSFUL，689 单测全绿（684 → +5；本轮共新增 19 例）
新例 DrawBuffersSlotAdapterTest    8 例（累积语义 / 0-3-6-7 映射 / 恒等静默 / 超限拒绝 /
                                        槽位重复拒绝 / 能力口径不改写 / 无标记恒等 / 顶点不参与）
新例 PackVertexAdapterGeneratorTest 5 例（逐位置对齐 / 15 条全供 / 常量记账 / 未知零值+WARN / 属性声明）
新例 PackBooleanOptionTest          6 例（//#define 可开 / false 保持原样 / 保缩进与行尾注释 /
                                        函数宏不误伤 / 别名不进模型 / 真实包可枚举可开启）
更新 PackTerrainSourceTest：适配层签名对账改为对**生成物**（静态资产已删）
~~~

🔖 **实现期自己制造并修掉的两个回归**（都记在案，因为它们都是「改一行毁一片」的形状）：
1. `OptionSourceRewriter` 首版从**前导空白**上切掉 2 个字符（以为那是 `//`）⇒ 整行被改坏 ⇒
   下游预处理器报 `Range [0, -2) out of bounds` 并**丢掉全部 182 个编译阶段**。
2. 同类的正则 `\s*` 把行尾注释前的空格吃掉 ⇒ `#define SHADOW_CLOUD  // 云阴影` 变成 `…// 云阴影`。
⇒ **教训**：改写行的代码必须断言「输出行仍能被同一套 pattern 再解析回去」。

---

## 九、本轮**没有**证明的（不许当已完成引用）

1. ⛔ **8 槽的像素值不对**（画面全黑，§六）。几何与槽位对，值不对。GAP-008。
2. ⛔ 地形仍只画进**我方 pass**，主目标由原版照常绘制 ⇒ **本轮同样不产出用户可见画面改进**（M-04 未做）。
3. ⛔ GAP-007 的 7 条常量项（方块 id / 法线 / 切线副法线 / 材质 UV）。
4. ⛔ `specular` / `normals` 两个采样器绑的是**方块图集占位**；`shadowtex0/1` 是本 pass 深度占位。
5. ⛔ `DRAWBUFFERS:08367`（MCBL_SS + 高级材质同开）需要 9 附件 ⇒ **显式拒绝接线**（能力边界）。
6. ⛔ 只覆盖 OPAQUE 组；只验了 BSL 一个包（X39）。
7. ⛔ 新增 102 个布尔选项对**选项屏幕**的实际影响未测（P4.3 UI 面），只验了生效链。

---

## 十、产物与哈希

| 文件 | sha256 |
|---|---|
| `run/logs/latest.log`（本趟） | `96f4942b0be2df67fe3c0ebf62f1310af1816eae434559f814352c92e75353d3` |
| `evidence/h09-images/h09-C-adv-materials-8slots.png` | `cc4bbf58351c26c16a9c05793f7c102af8d9f9ad912118510c75288d4ab05962` |

MCP 回执：`get_status → integratedServer=true, playerCount=1`；
`set_time {"time":6000} → success`；`look {"yaw":35,"pitch":-12} → 原样返回`；`screenshot → 854x480`。
收尾 `game_procs.sh kill` → `残留游戏进程数=0`。
