# h15 · 🔴 候选 6 **被否**；🔴 X47 的解释**是错的**（M-01 不是基准）；🔴 真正原因是模组总闸

> 任务来源：`AGENT_CONTEXT.md` §10.20 ⑧（重跑候选 6，必须先过两道闸）。
> 取证方式：先查清 h14「pass 没跑」的真正原因，再按三道闸重跑。
>
> 判定：🔴 **候选 6 被实验否掉**；同时**纠正上一轮一个错误结论**，并把「闸门」清单往前推了一层。

---

## 〇、一句话结论

探针在客户端里**确实命中 2 处**（`dcdx` + `dcdy`，顶点侧两开关均关 = 严格单变量），
画面**仍然全黑** ⇒ **候选 6 被否**。六个候选至此全部排除。
🔖 顺带查明：h14 那趟 pass 没跑，**根本不是「基准阻塞」**，而是 **`vkdisp.enabled=false`（模组总闸关着）**。

---

## 一、🔴 先纠正 h14 的错误结论：M-01 不是基准

上一轮我写「**M-01 冷路径基准长时间占住渲染线程**」并据此立了 X47。**这个解释是错的。**

逐行核实 `TerrainPipelineApi` 的 M-01 埋点：

~~~java
/** 🔖 <b>节流间隔为什么是 25 万次而不是 600（首版踩过的坑，勿回调）</b> … */
private static final long HIT_LOG_EVERY = 250_000L;

public static void onWireTerrainHit() {
    long hits = WIRE_HITS.incrementAndGet();
    if (hits == 1L) { … }
    else if (hits % HIT_LOG_EVERY == 0L) {
        VkDisp.LOGGER.info("vkdisp: [M-01] hit x{} (wired={}/{})", …);
    }
}
~~~

🔖 **它是一个无限增长的命中计数器，每 25 万次打一行日志，永远不会「跑完」。**
所以「等 M-01 跑完」这个判据**本身就不可能成立** —— 我据此立的 **X47 作废**。
而且它的存在恰恰是**好消息**：`hit x6250000` 说明 M-01 注入点**确实在被调用**（地形 draw 在跑）。

### 1.1 真正的���因

顺着 h14 日志往下读，第 158 行（本轮才第一次读到）：

~~~
[WARN] vkdisp: fullscreen pass skipped (fallback branch: config vkdisp.enabled=false)
~~~

**整个模组没启用** ⇒ pass 不执行、探针不生效、当轮屏幕上是纯原版画面。
这与「基准阻塞」是完全不同的两回事。

---

## 二、三道闸（比上一轮列的多一道，而且顺序是对的）

上一轮我只列了两道闸（基准跑完 / pass 出现）。实测表明**最前面还缺一道**，且它**遮住了**后两道：

| # | 闸 | 判据 | 本轮实测 |
|---|---|---|---|
| **①** | **模组总闸** | 日志里**不得**出现 `fallback branch: config vkdisp.enabled=false` | **0 次** ✅ |
| ② | 探针真的命中 | 自报「已把 **N** 处」 | **N=2** ✅ |
| ③ | pass 真的执行 | `terrain drawn into` | 出现 ✅ |

🔖 第 ① 道闸**必须在最前面**：总闸关着时，②③ 两道都会「碰巧不满足」，
而 ②③ 的失败信息**长得完全一样** ⇒ 极易误判成「探针没生效」或「pass 没跑」从而归错因。

**本轮根因正是如此**：上一轮看到「pass 没跑」就归因于基准，而真实原因在**更上游的总闸**。

---

## 三、🔴 候选 6 被否

探针做的事（等行数、只改右值）：

~~~glsl
- vec2 dcdx = dFdx(texCoord);
+ vec2 dcdx = vec2(0.0);
- vec2 dcdy = dFdy(texCoord);
+ vec2 dcdy = vec2(0.0);
~~~

按 GLSL 规定，此时 `textureGrad` 的 LOD 选取应与 `texture()` 相同 ⇒ 采样结果**应当一致**。

实测：

~~~
日志自报:  派生导数探针**已开启**：已把 2 处 dFdx/dFdy 初值改成 vec2(0.0)
顶点侧:    lmCoord=满光照诊断开关=关     dist=视差跳过诊断开关=关   ← 严格单变量
pass:      terrain drawn into 8 attachment(s) pass (group=OPAQUE)
画面:      仍全黑
~~~

🔖 **判据「画面是否变亮」未达成 ⇒ 候选 6 被否。**

⚠️ 注意 `已把 0 处` 也出现了 **620** 次（而 `已把 2 处` 出现 108 次）——
那是**合成/延迟/最终四个程序**的转译（它们不声明 dcdx/dcdy）。
这正好验证了 **`finally` 复位是必要的**：否则那四个程序也会被这个开关波及。

---

## 四、六个候选至此全部排除

| 候选 | 内容 | 结论 | 依据 |
|---|---|---|---|
| 1/2 | `ao*ao`、`1-metalness*smoothness` | ❌ | `h10` 中性材质贴图后 48.41% 像素变了但仍黑 |
| 3 | `skylightSqr = lightmap.y²` | ❌ | `h12` 探针自报已开启，仍黑；`h13` 另测得 `skyOcclusion = 1.0`（满值） |
| 4 | 首行 `texture()*color` | ❌ 结构性 | 默认路径同一表达式「看起来」正常 —— ⚠️ 见 §五，这条其实**未被真正验证** |
| 5 | 视差分支 | ❌ | `h12` `dist=1000` 早退跳过，仍黑 |
| **6** | **`textureGrad` 的显式 LOD** | ❌ **本轮** | 导数置零后仍黑 |

🔖 排除法已经走到尽头，**必须换策略**：不能继续在下游找因子，要去证明**最上游那一项**。

---

## 五、🔴 一个越来越可疑的发现：`h08-B` 可能也是**原版画面**

回看本轮顺带核实的顶点格式（`DefaultVertexFormat`，逐行读出）：

~~~java
public static final VertexFormat BLOCK = VertexFormat.builder(0)
        .addAttribute("Position", POSITION_FORMAT)   // RGB32_FLOAT  → location 0, vec3  ✅
        .addAttribute("Color",    COLOR_FORMAT)      // RGBA8_UNORM   → location 1, vec4  ✅
        .addAttribute("UV0",      UV0_FORMAT)        // RG32_FLOAT    → location 2, vec2  ✅
        .addAttribute("UV2",      UV2_FORMAT)        // RG16_SINT     → location 3, ivec2 ✅
        .build();
~~~

适配层的属性声明与这四条**逐条相符**（含 `ivec2`）。

🔖 **但这里冒出一个更根本的疑问**：`h08-B` 那张「BSL 处理过的暖色地形」截图，
当时 **并没有开 `terrainToMain`** ⇒ 我们看到的很可能一直是**原版画面**，
**从未真正看过自己 pass 输出的 colortex0 内容** —— h09～h15 看到的黑，恰恰就是它。

⚠️ **这不推翻 `h12` 对候选 4 的「结构性排除」**（那条依据的是「默认路径算出可见画面」），
但它意味着**那条排除所依赖的证据本身不可靠** ⇒ 候选 4 应当**降级为「未验证」**。
🔖 **教训**：`h12` 那条排除当时被我当成「结构性、无需实验」，结果它依赖了一张
**没经过 `terrain drawn into` 核验**的截图 ⇒ 结构性的推论也必须建立在**已验证的前提**上。

### 5.1 由此得到新的首要嫌疑：`color`

两条路径的 albedo **首行逐字相同**：

~~~glsl
vec4 albedo = texture(texture_0, texCoord) * vec4(color.rgb, 1.0);
~~~

🔖 若 `color.rgb` 为 0，则 `albedo ≡ 0`，**与 texture / textureGrad / 光照全无关** ——
这一条**能同时解释本轮与此前所有观察**，且是**唯一一个还没被任何实验触及的因子**。

⚠️ **本轮未能核实**：原版地形网格究竟往 `Color` 里填了什么（`PutColor` 的实参），
在 `client/renderer/chunk` 与全仓 grep 里都没命中 —— 网格化代码可能不在该 sources jar 内。
⇒ **登记为下一轮的头号入口，且必须在客户端里实测（不能只靠 grep）**。

---

## 六、净结果

| 项 | 状态 |
|---|---|
| 候选 6 | ❌ **被实验否掉**（探针命中 2 处、严格单变量、仍黑） |
| `h14` 的「基准阻塞」解释 | 🔴 **错误，已纠正**（M-01 是无限计数器，不是基准） |
| X47 | 🔴 **作废** |
| h14 pass 没跑的真因 | 🔴 **`vkdisp.enabled=false`**（模组总闸） |
| 闸门清单 | ✅ 补上**最前面**的「模组总闸」 |
| 候选 4 | ⬇️ **降级为「未验证」**（其排除依据的截图未经 X46 核验） |
| 新首要嫌疑 | 🔴 **`color.rgb`**（能同时解释全部观察，且从未被实验触及） |
| 测试 | ✅ **697** 单测全绿（本轮未改产品代码） |

---

## 七、稳定（支柱②）

| 判据 | 值 |
|---|---|
| `Couldn't parse GLSL` | **0** |
| `vkdisp` ERROR | **0** |
| 客户端崩溃 | 无 |
| 残留游戏进程 | **0** |
| 配置复原 | ✅ 模组总闸已回 `enabled=false`，`mrt.*` 全关 |

⚠️ 仍**不**声称「0 validation error」（本机无 Vulkan validation layer）。

---

## 八、测试

`./gradlew build` ⇒ BUILD SUCCESSFUL，**697** 单测全绿。**本轮未改产品代码**。

---

## 九、本轮**没有**证明的

1. ⛔ **GAP-008 根因仍未坐实**；六个候选已排除，但排除法见底。
2. ⛔ **`color.rgb` 的实际取值未实测**（下一轮头号入口）。
3. ⛔ **`h08-B` 是否为我方 pass 输出，未验证**（据此候选 4 已降级）。
4. ⛔ GAP-010 的 12 条 ERROR 未修；GAP-007 常量项 7 条；GAP-009 真材质集未实现。
5. ⛔ 地形仍只画进我方 pass ⇒ **仍不产出用户可见画面改进**（M-04 未做）。

---

## 十、下一轮入口（按序）

1. **测 `color`**：给顶点适配层加一个**把 `color` 强制成 `vec4(1.0)` 的单变量探针**（默认关、自报）。
   判据：画面变亮 ⇒ **根因坐实**（`color` 从未被供上）；仍黑 ⇒ 排除。
   这比继续在下游找因子更值 —— 它是唯一还没被碰过的因子。
2. 顺手核实**我方 pass 的 colortex0 是否真的被写过**（`h08-B` 的疑点）。
3. **GAP-010**：对齐适配层资源登记与管线注册的时序，消掉 12 条 ERROR。
4. **GAP-007**：地形顶点侧补 `normal` + 方块 id。
5. **M-04**（仍需用户裁决）。

---

## 十一、产物与哈希

| 文件 | sha256 |
|---|---|
| `evidence/h15-images/h15-L-derivative-2sites.png`（探针命中 2 处，仍全黑） | `45172103d6cf2eaeb06fc816d714accd605f54b03edc3bf6bf48973594ba05cd` |
| `run/logs/latest.log`（本趟；三道闸全过） | `5f67d53f6b528dcd81508210f2e734ec568061ad887af807041fb3bcd5291132` |

MCP 回执：`get_status → integratedServer=true`；`set_time`、`look`、`screenshot` 全部成功。
收尾 `game_procs.sh kill` → 残留 0。
