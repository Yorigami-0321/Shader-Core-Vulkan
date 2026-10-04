# h13 · 🔴 第一次**正面证明**片元着色器跑完了：只有 `albedo` 是 0，其余输出全部正常

> 任务来源：`AGENT_CONTEXT.md` §10.18 ⑦（候选 6 的静态核查 + 探针）。
> 取证方式：静态核查方块图集 mip 链 + **换附件看**（诊断视图）+ MCP 客户端取证 + 像素采样。
>
> 判定：🟡 **重大推进**。GAP-008 第一次从「排除法逼近」变成「**有正面证据**证明只有 `albedo` 为 0」，
> 并顺带**用画面独立验证了 DRAWBUFFERS 槽位路由**。候选 6 仍是唯一嫌疑，但**尚未验证**。

---

## 〇、一句话结论

把诊断视图切到 **colortex3**（高级材质路径**确实写**的那个槽）后，画面是
**亮绿色地形剪影**（`vec4(smoothness, skyOcclusion, 0, 1)` 的 `.g` 通道）。
⇒ **片元着色器完整跑完、输出正常**；唯独 `albedo`（槽 0）是 0。
🔖 切到 **colortex1** 则**整幅纯清屏色、一根地形都没有** —— 因为高级材质路径写的是
**槽 0/3/6/7**，槽 1 本来就不该被写。**这就是 h09 的槽位映射第一次被画面独立验证。**

---

## 一、静态核查：方块图集的 mip 链**不是**我们的锅

§10.18 留给本轮的第一个待查项：'''① 绑给 texture_0 的方块图集 mip 链是否可用'''。核实结果：

~~~java
// MrtTerrainPass:492（逐字）
private static GpuTextureView blockAtlas() {
    GpuTextureView atlas = Minecraft.getInstance().getTextureManager()
            .getTexture(TextureAtlas.LOCATION_BLOCKS).getTextureView();
    if (atlas == null) {
        throw new IllegalStateException("vkdisp: block atlas texture view is null");
    }
    return atlas;
}
~~~

🔖 **是原版自己建的图集视图**（`TextureAtlas.LOCATION_BLOCKS`），不是本引擎 `createTexture` 出来的
⇒ mip 链由原版生成并填充 ⇒ **待查项 ① 排除**。「mip 未填充导致采样为 0」这条不成立。

---

## 二、🔴 先修一个**测量仪器本身的缺陷**：诊断视图当时是**哑的**

第一次尝试：只把 `viewSlot` 改成 1（诊断视图 `mrt.enabled` 仍是 **false**、
`terrainToMain` 仍是 **true**）。结果截图与 h09-C 的 sha256 **完全相同**：

| 文件 | sha256 |
|---|---|
| `h13-H-slot1.png` | `cc4bbf58351c26c16a9c05793f7c102af8d9f9ad912118510c75288d4ab05962` |
| `evidence/h09-images/h09-C-adv-materials-8slots.png` | `cc4bbf58351c26c16a9c05793f7c102af8d9f9ad912118510c75288d4ab05962` |

**逐字节相同** ⇒ 切槽**根本没生效**。原因：`mrt.enabled=false` 时诊断视图不画，
而 `terrainToMain=true` 让槽 0 直接就是主目标 ⇒ 我们看到的永远是主目标。

🔖 **教训**：`viewSlot` 单独切**不足以**换视图，`mrt.enabled` 才是总闸。
⇒ 已改正为 `mrt.enabled=true` + `terrainToMain=false` + 指定 `viewSlot`。
🔖 **方法论**：这次的「测量仪器哑了」是靠**哈希完全相同**发现的 —— 数值指标（像素均值）
可能被误读成「结果一样」，而哈希相同是**无歧义**的仪器故障证据。

---

## 三、🔴 槽 1：**整幅没有地形**（99.89% 是纯清屏色）

~~~
采样地形区（y 230..330, x 60..700，64000 像素）：
  RGB(0, 0, 255)      63932  (99.89%)   ← 槽 1 的清屏色
  RGB(255, 255, 0)      68  ( 0.11%)   ← 只有 UI/HUD 像素
~~~

**一根地形都没有**。🔖 这**正是预期**：高级材质路径的输出经 DRAWBUFFERS 映射到
**槽 0/3/6/7**（`DRAWBUFFERS:0367`），槽 1 与槽 2 **本来就不被写**。
⇒ **h09 的槽位映射第一次得到画面层面的独立验证**（而不是只看日志）。

---

## 四、🔴🔴 槽 3：**片元着色器完整跑完，输出正常**（本轮最关键的一张）

~~~
采样同一块区域：
  RGB(255, 0, 255)    40250  (62.89%)   ← 槽 3 的清屏色（品红）
  RGB(0, 255, 0)      23750  (37.11%)   ← 地形区域：纯绿
~~~

槽 3 的着色器输出是 `vec4(smoothness, skyOcclusion, 0.0, 1.0)`，`.b` 恒 0、`.a` 恒 1，
所以**纯绿 = `.g` 满值** ⇒ `skyOcclusion = lightmap.y = 1.0`（精确 1.0，不是近似）。

🔖 **由此得到 GAP-008 史上第一条正面证据**：
同一个片元里，**除了 `albedo` 之外的一切都活着且正确** —— 光照、天光通道、法线、菲涅尔
全部正常产出。**`albedo` 是在进入这些计算**之前**就已经被写成 0 的。**

---

## 五、顺带纠正一处我自己的推导错误（很关键）

我在 §10.18 里写过：'''若 `lmCoord=(1,1)` 则 `blockLighting ≈ 2.0` ⇒ 画面本应过曝发白'''。
本轮实测发现**适配层的 clamp 上界写的是 `vec2(0.9333, 1.0)`** ——
**`.y` 的上界是 `1.0` 而不是 `0.9333`**（我上一轮读错了 `vec2` 的逐分量上界）。

⇒ 默认配置下 `lightmap.y` **本来就饱和在 1.0** ⇒ **实验 A 的探针（在结构上）根本改变不了任何东西**。

🔖 这**不是**推翻实验 A 的结论，而是**解释了**它：候选 3 本来就不是压零项（`skyOcclusion = 1.0` 是正面证据），
所以探针开不开都是同一张图。**两路证据一致。**

🔖 **方法论教训**：单变量探针要**先证明它能改变被测量**，否则「无变化」既可能是「假设错」
也可能是「探针本来就在那个值上」。本轮是**先测出 `skyOcclusion = 1.0`** 才把这件事说清楚的。

---

## 六、候选 6 的现状（仍未验证）

| 子项 | 结论 |
|---|---|
| ① 图集 mip 链 | ✅ **排除** —— 用的是原版 `TextureAtlas.LOCATION_BLOCKS`，mip 由原版生成填充 |
| ② `dFdx(texCoord)` 是否退化 | 🟡 **未验证** |

🔖 但本轮新增了一条**排除**：`albedo` 在**进入 `GetMaterials` / `GetLighting` 之前**就已是 0
（由槽 3 正常反证）。这把候选范围进一步压到**片元 `main()` 的最前几行**：

~~~glsl
vec4 albedo = texture(texture_0, texCoord) * vec4(color.rgb, 1.0);   // 与工作路径逐字相同
if (skipParallax < 0.5) {                                            // mat=0 ⇒ 必定进入
    newCoord = GetParallaxCoord(texCoord, parallaxFade, surfaceDepth);
    albedo = textureGrad(texture_0, newCoord, dcdx, dcdy) * vec4(color.rgb, 1.0);  // ← 覆盖 albedo
}
~~~

⚠️ 第一行已被**结构性排除**（默认路径同一表达式算出可见画面，`h08-B`），
⇒ **第二次赋值是 `albedo` 的最后一次写入**，候选 6 仍是唯一嫌疑。

---

## 七、净结果

| 项 | 状态 |
|---|---|
| 候选 6 子项 ① 图集 mip 链 | ✅ **排除**（原版图集视图） |
| 「片元是否跑完」 | ✅ **正面证明**：槽 3 亮绿地形（`skyOcclusion = 1.0` 精确满值） |
| 「只有 `albedo` 为 0」 | ✅ **正面证明**（同一片元的其余输出全部正常） |
| DRAWBUFFERS 槽位路由 | ✅ **画面层面独立验证**：槽 1 全清屏、槽 3 有地形 |
| 诊断视图总闸（`mrt.enabled`） | 🔴 **发现仪器缺陷并改正** |
| 我自己的一处推导错误（clamp 上界） | 🔴 **已纠正并写入文档** |
| 候选 6 子项 ② `dFdx` 是否退化 | 🟡 **未验证** |

---

## 八、稳定（支柱②）

| 判据 | 值 |
|---|---|
| `vkdisp` ERROR | **0** |
| 客户端崩溃 | 无（三趟都进世界） |
| 残留游戏进程 | **0** |
| 非 vkdisp ERROR | 环境性（narrator / authlib / OpenAL） |

⚠️ 仍**不**声称「0 validation error」（本机无 Vulkan validation layer）。

---

## 九、测试

`./gradlew build` ⇒ BUILD SUCCESSFUL，**691** 单测全绿。本轮**未改产品代码** ——
全部结论来自测量与文档修正。

---

## 十、本轮**没有**证明的

1. ⛔ **候选 6 未验证**，GAP-008 仍开着（但已排除 mip 子项）。
2. ⛔ `dFdx(texCoord)` 在反向 Z / MRT pass 下的实际行为**未取证**。
3. ⛔ GAP-010 的 12 条 `Couldn't find source` 仍未修。
4. ⛔ 地形仍只画进我方 pass ⇒ **仍不产出用户可见画面改进**（M-04 未做）。
5. ⛔ GAP-007 常量项 7 条、GAP-009 真材质贴图集未实现。

---

## 十一、下一轮入口（按序）

1. **候选 6 的片元侧探针**：`dcdx/dcdy` 声明在片元里（第 293 行 `vec2 dcdx = dFdx(texCoord);`），
   顶点侧够不着 ⇒ 需要在转译阶段加一个「派生导数探针」（把 `dcdx`/`dcdy` 的初值改成 0）。
   判定：若画面亮起来 ⇒ **根因坐实**；若仍黑 ⇒ 候选 6 也被否，需换切分方向。
2. **GAP-010**：对齐适配层资源登记与管线注册的时序，消掉 12 条 ERROR（支柱②）。
3. **GAP-007**：地形顶点侧补 `normal` + 方块 id（常量项 7 条的欠账）。
4. **M-04**（仍需用户裁决）：把地形接进主链 —— 这是最后一步用户可见改进。

---

## 十二、产物与哈希

| 文件 | sha256 |
|---|---|
| `evidence/h13-images/h13-H-slot1.png`（仪器哑了，仪器故障证据） | `cc4bbf58351c26c16a9c05793f7c102af8d9f9ad912118510c75288d4ab05962` |
| `evidence/h13-images/h13-I-overlay-slot1.png`（改正后：全清屏） | `bc72dcf7496074f3c1787c048bff5269b182031c38ba18b4edfc9b762c42b457` |
| `evidence/h13-images/h13-J-overlay-slot3.png`（**亮绿地形**） | `b4f5a9539c10f8b50dfff1223d35e99362785fc0f154132288541db4390b6dfa` |

MCP 回执：三趟均 `get_status → integratedServer=true`；`set_time`、`look`、`screenshot` 全部成功。
收尾 `game_procs.sh kill` → 残留 0；`run/config/vkdisp-client.toml` 已复原为默认。
