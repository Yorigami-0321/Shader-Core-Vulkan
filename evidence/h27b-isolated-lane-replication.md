# h27b · 隔离车道复现：在 **854×480**（历史「有闪烁」窗口尺寸）下**仍然不复现** + 绿天空的成因

> 同轮第二份记录。本轮**不推翻** `evidence/h27-alias-and-gap010-refuted-flicker-not-reproducible.md`
> （下称「h27 主记录」）的结论 —— 那是 `d434045` 做的四组 82 帧 A/B。
> 本文件补三件主记录**没有**的东西：
> ① **隔离车道**（把共享车道的配置竞态从对照里彻底拿掉）后的独立复现；
> ② 两个**会让 A/B 静默变成空转**的坑（不隔离就踩）；
> ③ **绿天空的成因已定位**（不是神秘颜色，是我方逐槽诊断清屏色泄漏）。
>
> 方式：单变量对照（X49）+ X51 逐组从日志确认变量生效 + X52（共享状态不得在两臂之间变过）
> + MCP 连拍 6 帧判据。取证环境 `set_time(6000)` + `set_weather(clear)` + `look(yaw=90,pitch=0)`。

---

## 〇、一句话结论

在**独立 game directory**（`run/h27`，与主车道物理隔离、端口独立）里，
用**同一个二进制**（三个 class 的 sha256 指纹逐臂相同）做严格单变量对照：

| 臂 | `mrt.shadowStubs` | 6 帧中心区黑色像素占比 | 判定 |
|---|---|---|---|
| **A'** | `true`（专用桩纹理） | **44.37% ×6**（六帧完全一致） | 无闪烁（有内容相位） |
| **B'** | `false`（**故意恢复 Vulkan UB**） | **44.41% ×6**（六帧完全一致） | 无闪烁（有内容相位） |

🔶 ⇒ **独立复现了 h27 主记录的证伪**：别名/UB **不是**闪烁的原因（两臂差 0.04pp）。
🔶 ⇒ **并且两臂都落在 854×480** —— 也就是 h27 主记录 §③ 怀疑的那个「从未登记的名义变量」
（所有「有闪烁」证据的窗口尺寸）—— **在那个尺寸上闪烁仍然不复现**
⇒ 该假设进一步被削弱（仍非严格排除，见 §六）。

---

## 一、🔴 为什么需要隔离车道：主车道上实测到的配置竞态

我最初直接在主车道（`run/`）做 B 臂，**失败在一个与被测量无关的地方**：

| 时刻 | 事件 | 证据 |
|---|---|---|
| ~17:35 | `game_procs.sh kill` → 残留 0，再 `sleep 12` | 进程确实退出了 |
| ~17:36 | `sed` 把 `shadowStubs` 改成 `false`，**读回确认** = `false` | 不信中间态，直接读文件原文 |
| **17:37:50** | 🔴 **配置文件被重写回 `true`** | `vkdisp-client.toml` mtime + 内容 |
| 17:38:07 | 客户端起来，日志打 `shadow stubs ready` ⇒ **变量没生效** | X51 抓到 |
| 17:38 | 而且**活着的那个客户端不是我的** | 见下 |

根因（与 h27 主记录新立的 **X54** 独立吻合）：**同一仓库有第二个会话在并发跑 `runClient`**。

- 我 `kill` 之后对方又起了客户端，我的 `runClientIso`/`runClient` 与之并存；
- 孤儿客户端退出时**整份重写 `run/config`** ⇒ 把我验证过的值覆盖回旧值；
- 端口 25599 被抢占 ⇒ 我的 MCP 调用打到了**对方的客户端**上。

🔖 这就是 **X52**（两臂之间共享状态变过就当单变量失效）的实体：主车道上「改配置 → 启动 → 取证」
这三步之间共享状态被第三方改过 ⇒ **B 臂根本没测到想测的东西，而且日志看起来完全正常**。

## 二、隔离车道怎么搭（本轮落地，可复用）

| 部件 | 落点 | 说明 |
|---|---|---|
| 独立 run 配置 | `build.gradle` 的 `clientIso` | `gameDirectory = project.file('run/h27')` |
| 目录天然被忽略 | `.gitignore:68` 的 `run/` | 取证产物/存档不会进版本库 |
| 独立桥端口 | `run/h27/config/mcpfabric.config.json` → `port: 25600` | 与主车道 25599 不冲突 |
| 驱动指向 | `VKDISP_MCP_CONFIG=run/h27/config/mcpfabric.config.json python3 tools/mcp-drive.py …` | `tools/mcp-drive.py` 支持该环境变量覆盖 |

```bash
# 一行复现（隔离车道）
./gradlew runClientIso -PquickPlay --console=plain
VKDISP_MCP_CONFIG=run/h27/config/mcpfabric.config.json \
  python3 tools/mcp-drive.py screenshot out.png
```

## 三、🔴🔴 两个「不隔离就踩」的坑 —— **都会让 A/B 静默变成空转**

🔴 这是本轮最可复用的产出：**两次都「看起来跑成功了」，但实验已经没有意义。**

### 坑 1：`vkdisp-pack-options.properties` 不跟着走 ⇒ 被测 sampler **根本没被声明**

隔离车道首次启动，日志打的是：

```
[GAP-003] MRT terrain pipelines will use pack fragment: colorTargets=1 samplers=5 varyings=9
```

而主车道是 `colorTargets=8 samplers=7 varyings=15`。**包选项覆盖不在 `vkdisp-client.toml` 里**，
而在同目录另一个文件 `vkdisp-pack-options.properties`，里面有
`BSL_v10.1.8.ADVANCED_MATERIALS=true`（+ `SHARPEN=3`）。

🔴 **后果**：`ADVANCED_MATERIALS` 关闭 ⇒ `gbuffers_terrain` 只写 1 个输出、只用 5 个 sampler
⇒ **`shadowtex0` / `shadowtex1` / `shadowcolor0` 压根没出现在着色器里**
⇒ 「桩纹理 vs 绑读写附件」这个对照**没有任何变量可言**，两臂必然一模一样。

🔖 只有从日志里那句 `colorTargets=N samplers=N varyings=N` 才看得出来（这正是 X51 的价值：
**配置文件不算证据，运行期反射结果才算**）。
补齐后日志出现 `选项覆盖已改写进源: 命中 2/2 [SHARPEN=3, ADVANCED_MATERIALS=true]`，两臂才对齐。

### 坑 2：`run/mods/mcpfabric-*.jar` 不跟着走 ⇒ **没有 MCP 桥**

第一次带桥启动隔离车道时，MCP 直接报
`Bridge unreachable: Could not reach the mcpfabric bridge at http://127.0.0.1:25600`
—— 日志里 `mcpfabric` 命中数 = **0**。原因：`mcpfabric` 以 jar 形式放在 `run/mods/`，
不在 `build/classes` 里 ⇒ 新 game directory 不会自动获得它。

## 四、单变量的证明（X52 的正面做法）

两臂跑的是**同一份产物**：启动前记录三个相关 class 的 sha256，两臂之间逐一 `sha256sum -c` 复核：

```
5c463ee21ad383a21621194b2bdaf1e5c330bc302096d47d1118867a9abe161a  build/classes/java/main/dev/vkdisp/bridge/TerrainPipelineApi.class
dae436a0ade2351c4b0d0251c14cd583ab73418a28797990b4bf751618064e61  build/classes/java/main/dev/vkdisp/bridge/ShadowStubs.class
cc6f6a658fb183ec2ca040461adc04b656592db5c79fcfcaaf91edda24e98d90  build/classes/java/main/dev/vkdisp/bridge/MrtTerrainPass.class
```

⇒ 两臂之间**唯一**的差异是配置文件里的一个布尔值。

**X51 逐臂确认（全部来自日志，不来自配置文件）**：

| 臂 | 必须出现的行 | 实测 |
|---|---|---|
| A' | `[GAP-003] shadow stubs ready (1x1 D32@0.0 + RGBA8@0)` | ✅ 1 条 |
| B' | `[GAP-003/A] mrt.shadowStubs=false —— **故意**…`（WARN 自报） | ✅ 1 条 |
| B' | `shadow stubs ready` | ✅ **0 条**（正确：桩纹理没建） |
| 两臂 | `will use pack fragment: … colorTargets=8 samplers=7 varyings=15` | ✅ 各 1 条 |
| 两臂 | `[GAP-003/A] terrain drawn into 8 attachment(s) pass (… draws=1)` | ✅ 各 1 条 |
| 两臂 | `pack compile done: stages=190 ok=190 failed=0` | ✅ 各 1 条 |

## 五、判定口径（把 h22 的判据**工具化**了）

🔶 h22 定下了判据，但**口径本身没有工具**，h25/h26/h27 的数字都是临时算的、无法复算。
本轮补上 `evidence/tools/flicker_ratio.py`（stdlib-only PNG 解码 + 中心区黑色像素占比）：

- 采样区 = 中心矩形 **x∈[45%,65%] / y∈[15%,75%]**（854×480 下 = 49,248 px）
- 「64000 px」这个 h22 文档里的数字是**反解出来的**：用前缀和在 h21/h25/h26 共 12 张已入库截图上
  搜索能同时复现四个已公布数字的采样区，就是这个矩形（930×577 下 = 64,356 px ≈ 64000）；
- 阈值 = RGB 各通道 ≤ 8。回归 `--selfcheck` 复现精度 ±0.25pp：

| 组 | 本工具 | h2x 文档公布 |
|---|---:|---:|
| h25 packfrag-off-1 | 0.39% | 0.38% |
| h26 att8-1 | 0.39% | 0.38% |
| h21 黑相位 | 99.86% | 99.89% |
| h21 有地形但天空黑 | 52.58% | 52.81% |

判读阈值沿用 h22：`< 60%` = 有内容相位、`> 90%` = 全黑相位；
**闪烁 = 同一组 6 帧里两种相位交替**（单帧看不出闪烁）。

## 六、🔴 附带定位：**绿天空 = 我方逐槽诊断清屏色泄漏**

主记录与我这一轮都拍到「天空是纯绿 `RGB(0,255,0)`」。本轮把它定位掉了：

```java
// src/main/java/dev/vkdisp/bridge/MrtTerrainPass.java:515-522
/** 逐槽诊断清屏色：槽 0 绿 / 槽 1 蓝 / 槽 2 品红（高对比，便于一眼分辨「哪一槽 + 有没有内容」）。 */
private static Vector4f diagnosticClear(int slot) {
    return switch (slot) {
        case 0 -> new Vector4f(0.0F, 1.0F, 0.0F, 1.0F);   // ← 纯绿，就是它
        case 1 -> new Vector4f(0.0F, 0.0F, 1.0F, 1.0F);
        default -> new Vector4f(1.0F, 0.0F, 1.0F, 1.0F);
    };
}
```

调用点是**无条件**的（`MrtTerrainPass.java:350`，给每个槽位都带上这个清屏色），**没有诊断开关**。

⇒ **成因链**（每一步都能在日志/代码里对上）：

1. 我方 MRT pass 的 `colortex0` 每帧被清成**纯绿**；
2. 我们的 pass **只画地形** —— 天空从来没被画进 gbuffer ⇒ 天空那片区域**保持纯绿**；
3. 包的 composite 采 `colortex0` ⇒ 绿天空直接出现在最终画面上。

🔶 所以「绿天空」**不是**新的神秘渲染故障，而是**两个已知事实的乘积**：
「gbuffer 里没有天空」× 「槽 0 的诊断清屏色是纯绿」。
它与主记录 h13 拍到的 `colortex3` 亮绿地形剪影是同一套配色，互相印证。

🔴 **但它确实是一个必须修的产品缺陷**：把**诊断清屏色**放进**用户可见的最终画面**，
等于「诊断仪器污染了被测量对象」。且它让 h13/h25/h26 的截图判读多了一层干扰
（此前 h26 报的「绿色天空」其实就是我方清屏色）。

**同一现象里的另一半 —— 地形全黑 —— 不是这个原因**：那是 GAP-008 的
`albedo ≡ 0`（h13 已正面证明：片元完整跑完、只有 albedo 是 0），仍然未定位。

## 七、仍未证明 / 下一步

| 项 | 状态 |
|---|---|
| GAP-011 闪烁的**历史**根因 | ❌ **仍未定位**，且**当前不可复现**。本轮在 854×480 下复现失败 ⇒ h27 主记录 §③ 的「窗口尺寸」假设进一步削弱，但**未严格排除**（窗口尺寸无法在无 WM 的 WSLg 里设定，见主记录 §③） |
| 「闪烁已被某两个提交之一消除」 | 🔶 **推测，未证**。候选：`30183e3`（摘掉孤儿绑定组 + MRT 变体 attachment 数守卫）、`7206d6d`（GAP-010）、`f1a6a81`（DRAWBUFFERS 槽位兑现）。要坐实需按此顺序 bisect —— **属历史考古，本轮不做** |
| GAP-008 `albedo ≡ 0` | ❌ 未定位。仍是**当前最大的可见缺陷**（地形全黑） |
| 绿天空 | ✅ 本轮**定位完成**（§六），**未修**。修法建议见下 |
| `diagnosticClear` 是否该进生产路径 | 🔶 **待决**（我不主张在本轮顺手改，见下） |

🔖 **为什么本轮没有顺手把绿天空改掉**（X12 边界）：诊断清屏色当初是**刻意**选的
（`MrtTerrainPass.java:346-349` 写明：槽 0 的指纹恰好是 0.0 = 黑，
「什么都没画」与「画了但很暗」在截图上无法区分）。要改就得同时保留那项区分能力
（例如槽 0 改成非黑但**不代表任何语义**的颜色，或把诊断色收到只读回通道），
属于**独立一轮**的取舍，不该混进一次「证伪测试」里悄悄改掉。

## 八、环境副作用披露

| 对象 | 改动 | 还原 |
|---|---|---|
| `build.gradle` | 新增 `clientIso` run 配置（`gameDirectory = run/h27`） | ⚠️ **有意保留**（§二，取证基础设施） |
| `tools/mcp-drive.py` | 新增 `VKDISP_MCP_CONFIG` 环境变量覆盖（默认仍是主车道） | ⚠️ **有意保留**；`/tools/` 被 `.gitignore:80` 忽略 ⇒ 不进版本库 |
| `evidence/tools/flicker_ratio.py` | 新增（**入库**，因为 `/tools/` 不入库，而判据必须可复算） | 新增证据 |
| `run/h27/**` | 隔离车道（config / saves / shaderpacks / mods / logs） | ⚠️ 保留（下次取证直接复用）；已被 `run/` 忽略 |
| 主车道 `run/config/vkdisp-client.toml` | 本轮早期被孤儿客户端重写（`shadowStubs` 回 `true`） | 未还原（保持出厂安全侧）；主车道客户端当前已停 |
| 世界存档（两个车道） | `time set 6000`、`weather clear`、`look(yaw=90,pitch=0)` | ⚠️ 未还原（刻意固定，避免昼夜误判） |
| 截图 | `evidence/h27-images/isoA-stubsON-{1..6}.png`、`isoB-stubsOFF-{1..6}.png` | 新增证据，随文档入库 |
| 游戏进程 | 隔离车道客户端 4 趟（其中 2 趟因坑 1/坑 2 作废） | 见下 |

**残留进程**：见 §九（提交时刻的实测值）。

## 九、自检

- [x] 两臂的变量都**从日志**确认生效（X51），不采信配置文件
- [x] 两臂之间**二进制指纹相同**（X52 的正面做法）
- [x] 判据**有工具、有回归**（`flicker_ratio.py --selfcheck` 复现 h2x 公布值 ±0.25pp）
- [x] 结论与 `d434045` **一致**，没有把「复现失败」写成「证伪成功」
- [x] 未在本轮顺手改产品行为（绿天空只定位、不修，理由见 §七）
