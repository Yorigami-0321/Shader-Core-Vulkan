# h11 · 实验 2 执行 + 🔴「quickPlay 随机不生效」的**真因**：是我们自己的管线加载失败

> 任务来源：`AGENT_CONTEXT.md` §10.16 ⑧（重跑实验 2）+ 顺带定位 quickPlay 现象。
> 取证方式：静态链路分析 + MCP 驱动真实客户端 + 像素级采样。
>
> 判定：🟡 **候选 3 仍不可判**（不是证伪，是实验本身不可信 —— 见 §五）；
> 但挖出并修掉了一个**「游戏根本起不来」级别**的回归，并定位了「quickPlay 随机不生效」的真因。

---

## 〇、一句话结论

**上一轮记下的「quickPlay 有随机不生效的现象」是错判。** 真因是我们的诊断开关在 GLSL 里
留下了一个行尾注释，**把语句末尾的分号吃掉** ⇒ 6 条地形管线全部加载失败 ⇒
**资源重载抛异常 ⇒ 世界根本进不去**。而现象看起来只是「客户端停在主菜单」，极易被误记成环境抖动。
🔖 这正是 X44 的**现场复现** —— 上一轮刚立的教训，本轮自己又踩了一次。

---

## 一、🔴 假象：「quickPlay 随机不生效」（实为管线加载失败）

h10 观察到「6 次里约 2 次没进世界」，当时记为 quickPlay 的随机性。**这是错判。** 本轮第一跑：

~~~
[resourceLoad/ERROR] PipelineBuilder: Couldn't find source for VERTEX shader (vkdisp_pack:terrain_pack_adapter)  ×12
[resourceLoad/ERROR] PipelineBuilder: Couldn't compile pipeline (vkdisp:pipeline/terrain_cutout_multidraw_mrt):
  com.mojang.renderpearl.util.ShaderCompileException: Couldn't parse GLSL:
    vkdisp_pack:terrain_pack_adapter:63: error: '' : syntax error, unexpected IDENTIFIER, expecting COMMA or SEMICO
java.util.concurrent.CompletionException: java.lang.RuntimeException: Failed to load required shader programs:
 - vkdisp:pipeline/terrain_solid_mrt / cutout_mrt / translucent_mrt
 - vkdisp:pipeline/terrain_solid_multidraw_mrt / cutout_multidraw_mrt / translucent_multidraw_mrt
~~~

**资源重载抛异常 ⇒ 客户端起不来**。jstack 只能看到「渲染线程空闲在 `limitDisplayFPS`」，
画面上就是「停在主菜单」—— 与「没触发 quickPlay」**完全无法区分**。🔖 **这就是为什么不能记成环境抖动。**

⚠️ 顺带确认：**MCP 的 75 个工具里没有任何一个能加载世界**（`tools/list` 逐名核对，
含 world/join/menu/click/key/screen/level/load 等关键词只匹配到 `screenshot`）
⇒ 世界进入只能靠 `--quickPlaySingleplayer`，没有绕过去的办法。

---

## 二、🔴 根因：行尾注释吃掉分号（**X44 现场复现**）

诊断开关原本写成：

~~~java
case "lmCoord" -> fullLightProbe
        ? "lmCoord = vec2(1.0)  // DIAG full-light probe"   // ← 元凶
~~~

生成器把赋值拼成 `<表达式>;` ⇒ 实际产出的 GLSL 是：

~~~glsl
lmCoord = vec2(1.0)  // DIAG full-light probe;
~~~

**分号在注释里** ⇒ 下一行接的是 `;` 之外的东西 ⇒ `syntax error ... expecting ... SEMICOLON`。
🔖 **这与上一轮 OptionSourceRewriter「吃掉行尾注释前的空格」是同一个根因形状**：
往行尾追加内容时，注释会吞掉后续一切。**⇒ 教训要落到断言上，不能只写在文档里。**

修法：供值表达式里**一律不带注释**（注释只能单独成行），并加 **X44 回归断言**：
「每一条赋值行都必须以 `;` 结尾」。

---

## 三、✅ 修复后复跑：世界进得去了

~~~
Couldn't parse GLSL                     : 0 次（修前 ≥1，且直接导致 6 条管线失败）
integratedServer                        : true ✅
vkdisp: [GAP-003/A] terrain drawn into 8 attachment(s) pass
~~~

⚠️ **但仍有一个未修的隐患**：首轮资源重载仍有 12 条
`Couldn't find source for VERTEX shader (vkdisp_pack:terrain_pack_adapter)`。
这是**排序竞争**：地形管线注册早于虚拟包把生成物登记为资源（§10.14 已记过这个时序约束，
此处是它的另一面）。第二次重载成功 ⇒ 不致命，但会在日志里留下 12 条 ERROR。**未修，登记为 GAP-010。**

---

## 四、🔍 像素级判据：`albedo` 是被乘成**恰好 0**，不是「很暗」

采样地形区域（y 230..330, x 60..700，64000 个像素）：

| RGB | 占比 |
|---|---:|
| `(0, 0, 0)` | **37.00%** |
| `(0, 255, 0)` | 29.17%（清屏绿底） |
| 其余 | 各种接近纯绿的清屏色 |

🔖 **没有任何深灰/暗色像素**。⇒ 不是「乘法系数很小」，而是**某个因子恰好是 0**。
这把搜索范围从「调参」缩到「找那个恒等于 0 的量」，是本轮最有价值的量化结果。

---

## 五、🟡 候选 3（`lmCoord` / 天光通道）：**结论不成立 ≠ 结论不可信**

实验确实执行了（世界进了、pass 跑了、截图拍了）。但**判读不成立**，理由两条：

1. **生成器侧已验证正确**（无头）：`probe ON → "    lmCoord = vec2(1.0);"` 且以分号收尾。
2. **但渲染结果与 `lmCoord = (1,1)` 自相矛盾**：若天光通道真的变成 1.0，则
   `newLightmap = pow(1,10)*1.6 + 1*0.6 = 2.2`、`blocklightCol ≈ 0.42` ⇒ `blockLighting ≈ 2.0`，
   画面应当**过曝发白**，而不是恰好全黑；且 `ApplyDynamicHandlight` 在 `heldLightValue == 0` 时
   直接 return（逐行核实），**不会**把 `lightmap` 改回去。

⇒ 所以**要么开关在运行期没真正生效，要么候选 3 不是主因但被更早的量压住**。
两者的画面**无法区分**，因为本轮还没有「开关是否生效」的可观测标记。

⚠️ 那 45.27% 的像素变化**不能**当作「开关生效」的证据 —— 两趟的区块状态/阴影深度本就会变。
🔖 **本轮据此立 X45：诊断开关必须在日志/诊断文本里自报状态**，
否则「开关没生效」与「结论不成立」不可区分，实验结论就是不可信的。

**已落地**：适配层生成时输出 `lmCoord=满光照诊断开关=已开启/关`；
开启时额外 WARN「若渲染结果与常量一致，说明**开关没生效**（不是结论不成立）」。
⇒ 下一轮重跑时，一眼就能判读。

---

## 六、本轮的净结果

| 项 | 状态 |
|---|---|
| 「quickPlay 随机不生效」 | ✅ **定位并纠正**：真因是管线加载失败导致资源重载抛异常 |
| X44 现场复现（行尾注释吃分号） | ✅ 修掉 + 加回归断言 |
| X45（诊断开关必须自报状态） | ✅ 立 + 落地 + 加回归断言 |
| 候选 3（`lmCoord`） | 🟡 **判读不成立**（开关生效性不可观测），非证伪 |
| 像素量化判据 | ✅ `albedo` 被乘成**恰好 0**（不是暗） |
| GAP-010（排序竞争留下 12 条 ERROR） | 🟢 新登记，**未修** |

---

## 七、稳定（支柱②）

| 判据 | 值 |
|---|---|
| `Couldn't parse GLSL` | **0**（修前导致 6 条管线加载失败） |
| `vkdisp` ERROR | **0** |
| 客户端崩溃 | 无 |
| 残留游戏进程 | **0** |
| 非 vkdisp ERROR | 环境性：narrator（缺 flite）、authlib（无网）、OpenAL（无音频设备） |

⚠️ 仍**不**声称「0 validation error」（本机无 Vulkan validation layer，沿用 §9.4.15 纪律）。

---

## 八、测试

`./gradlew build` ⇒ BUILD SUCCESSFUL，**691** 单测全绿（689 → **+2**）：

- `X44 回归`：诊断开关产出的赋值行必须以分号收尾；
- `X45 回归`：诊断开关必须在诊断文本里自报「已开启/关」，且开启时给出判读指引。

---

## 九、本轮**没有**证明的（不许当已完成引用）

1. ⛔ **GAP-008 仍开着，且没有更接近根因**。候选 1/2 已证伪（h10），候选 3 不可判（本轮）。
2. ⛔ 12 条 `Couldn't find source` 的**排序竞争未修**（GAP-010）。它不致命，但会在日志里留 ERROR。
3. ⛔ MCP 无法加载世界 ⇒ 每次取证都依赖 `--quickPlaySingleplayer`（现在知道了它为何会失败）。
4. ⛔ 地形仍只画进我方 pass，主目标由原版绘制 ⇒ **仍不产出用户可见画面改进**（M-04 未做）。
5. ⛔ GAP-007 的常量项从 3 条涨到 **7** 条；GAP-009 的真材质贴图集未实现。

---

## 十、下一轮入口（按序）

1. **重跑实验 2（现在可信了）**：日志里出现 `lmCoord=满光照诊断开关=已开启` ⇒ 开关生效，
   此时「画面仍黑」才构成候选 3 的**真证伪**；若出现 `=关` ⇒ 开关没生效，先修生效性。
2. 若候选 3 真被证伪 ⇒ 用同一把尺子量候选 4：`albedo = texture(texture_0, texCoord) * color`
   —— 用 `mrt.terrainFullscreenProbe` 之外再加一个「强制 albedo=vec4(1,0,1,1)」的探针，
   看链路是否在最上游就断了。
3. **GAP-010**：把适配层资源登记与管线注册的时序对齐，消掉那 12 条 ERROR（支柱②）。
4. GAP-007 / M-04（仍需用户裁决）。

---

## 十一、产物与哈希

| 文件 | sha256 |
|---|---|
| `evidence/h11-images/h11-E-full-light-probe.png` | `9b3e81129f96065243d58d81a1cf89867e3c86f82a58fb86afc51aabf589b44f` |
| `run/logs/latest.log`（本趟） | `155ce7c0e5a7d46fa5e3d5557d7958519fdbe075f7163e93cf7c209b8287fe06` |

MCP 回执：`get_status → integratedServer=true`；`set_time {"time":6000} → success`；
`look {"yaw":35,"pitch":-12} → 原样返回`；`screenshot → 854x480`。收尾 `game_procs.sh kill` → 残留 0。
