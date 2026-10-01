# P4.1.6 画面方向矫正证据（2026-10-01，强制位姿三跑闭环）

> G-01 文本摘要：日志关键行（原文）+ sha256 + 一行复现 + 判定。
> 被摘要的日志/截图本体在 gitignored 路径（`run/logs/`、`tools/vulkan-local/evidence/`），
> 本文件只存可复核的事实；复现后按下方 sha256 对账即可确认取到了同一份证据。

## 一行复现

```bash
source tools/vulkan-local/env.sh && export JAVA_TOOL_OPTIONS="-Djava.net.preferIPv4Stack=true" && ./gradlew runClient -PquickPlay --console=plain
```

- **强制位姿**（方向判定的可重复锚点）：把玩家 NBT
  `run/saves/New World/players/data/380df991-….dat` 的 `Rotation` 写成
  `[0.0, −15.0]`（yaw 朝南、pitch **上抬 15°**；原值备份 `.bak-pose` =
  (−98.25, −5.4)，存档为 superflat，地平线理论位置可算）。进入世界后截图：
  `python3 tools/vulkan-local/x11_capture.py OUT.png --window-id 0x…`
  （窗口 id 每次启动变化，先 `--list-windows`；depth=32 窗口 930×577）。
- 环境：WSL2，llvmpipe 软件 Vulkan（Mesa 26.2.3）；worldTime=0、晴天（同 p413 基线）。
- **地平线测量口径**：crop `(35,62,w−37,h−39)` → 逐行 luma 均值
  （`luma=(r*299+g*587+b*114)//1000`）→ 相邻行均值差绝对值最大处 = 地平线。
  crop 行 0 ↔ 全局 y=62，行中心 301、6.83 px/度 →
  **上抬 15° 预测：正立 ≈ 全局 403，上下颠倒 ≈ 199**。

## 问题与根因（本轮修复的对象）

用户报「镜头反了：下方是黑色的天空、上方是黄色的地面」。三跑把根因钉在
**P3.3 链 composite 的顶点选择**，而非包片元或 present 路径：

- `FrameApi` 链模式 `packColor = sceneColorView()`（OF 语义：deferred 的
  DRAWBUFFERS:4 **不改写 colortex0**，P4.1.2 绑 viewC 首跑全黑的根因实证）——
  即链彩色主输入一直是**场景色**；
- P3.3 的「净翻转守恒」推导（deferred 与 composite 各 +1 抵消）把 deferred 当成了
  彩色链上的一跳 —— 它只落 gaux1 辅助位，**不在彩色净翻转的算式里**；
  于是链彩色路径 = `scene →[composite flipv]→ offscreen3 →[final 恒等]→ main`
  = **净 +1 翻转**，自 P3.3 起上下颠倒；P4.1.4 的恒等拷贝正确地保住了这个错。
- **包源无罪取证**（Python zipfile 读本地 `run/shaderpacks/BSL_v10.1.8.zip`，
  手工核验、不入库）：composite/deferred/final 的 `texCoord = gl_MultiTexCoord0.xy`
  （即我方 vUv，无 `1.0−` 类翻转）；`gl_FragCoord` 只用于噪声/抖动取样；
  final.fsh（色差+锐化）完全不含 `gl_FragCoord`——p414 曾疑 final 片元坐标求值，
  **该嫌疑排除**；present 路径（菜单）本就正立。

## 三跑对照（同位姿、单变量）

| 跑 | 时段 | 代码状态（wired 日志行可复核） | 地平线（全局 y） | 判定 |
|---|---|---|---|---|
| run1 | 14:07:15–14:07:58 | **原码**：`deferred … fullscreen_flipv (flipv by net-parity)` + `final … fullscreen (no v-flip by attachment-identity)` + 链 composite flipv（line 925 旧条件） | **204**（≈翻转预测 199） | **上下颠倒**（暖色地面 RGB(95.0,87.6,85.8) 在上、暗天在下） |
| run2 | 14:20:02–14:20:43 | **矫正 A（对冲，后被取代）**：final 改 `fullscreen_flipv (flipv by p416 orientation fix)`，上游不动 | **392**（≈正立预测 403） | **正立**（地平线回正，暖色地在下） |
| run3 | 14:38:29–14:42:57 | **终版 B+（本轮提交）**：链 composite 改走 `composite_scene` 不翻转（line 925 → `useScene ? …`）+ `deferred … fullscreen (no v-flip by scene-source, p416)` + `final … fullscreen (no v-flip by attachment-identity)` | **392** | **正立**，与 run2 净效果一致 |

- run2 vs run3 客户区地面带（行 400–520）luma 互相关：**identity = +1.0000**、
  V = +0.3679、V∘H = +0.0477 → 两版**同向同构**，B+ 未引入再翻转
  （逐像素有差：26.21% 通道差、max_delta=63 —— 云飘移 + aux 行序对齐差异，属内容级
  非构图级）。
- run1 地面带（行 0–130，倒置画面里地面在上）vs run3 同带垂直镜像：**+0.9450**
  （与 run1↔run2 同值 +0.9450）→ run3 与 run1 **互为垂直镜像**；
  镜像对 204 + 392 = 596 ≈ crop 对称轴（2×301）的镜像和，方向修正闭合。
- RGB 带判读（暖 = 地、黑 = 天）：run3 上带 (1.0,1.0,1.0) / 下带 R−B **+9.1**；
  run1 上带 R−B +9.2 / 下带 (10,10,10) —— 「地在下」成立。

## 修复内容（B+，单条规则）

**顶点翻转跟随彩色采样源**：源是 vanilla 场景 → 不翻转；我方 off1 风格中间目标 →
flipv（P-1f 基线）。三处落地：

1. `FrameApi` line 925：`useScene && !deferredChain ? compositeScene : composite`
   → **`useScene ? compositeScene : composite`**（链模式彩色源 = 场景，按源规则
   也走不翻转；fixture 仍 flipv，P-1f 不动）；
2. `registerDeferredPipeline`：flipv → **noflip**（color/depthtex0 都采场景；
   顺带修正 depth 配对 —— 原 flipv 使行 y 写 AO 却读 scene 深度行 1−y，且 gaux1
   与 color 行序对齐）；
3. `registerFinalPipeline`：**维持不翻转**（attachment 恒等拷贝推导在上游修正后
   恢复有效；run2 的 flipv 对冲撤回）。

代数自检：世界链 = `scene →[noflip]→ offscreen3 →[noflip]→ main` ≡ P3.2 直连
（已实测正立）；fixture/菜单 = `flipv → main` = P-1f 原样。

## 证据文件 sha256

| 文件 | sha256 |
|---|---|
| `run/logs/p416-run1.log`（655578 B，原码，翻转对照） | `a9b3d1dd0f4991592121926a728eaa9fd267f390b196189d624a84543b7c0ae4` |
| `tools/vulkan-local/evidence/p416_pose15_run1.png`（118762 B，地平线 204 = 翻转） | `1804e86904e12a995575596c8307cd9822238a5cc6cf96309ea84092e4f2f09c` |
| `run/logs/p416-run2.log`（655583 B，矫正 A 对冲） | `f3955e6626b4ae1df90ed28f249819dd0c7df9cf6c0005a10381bc957b362998` |
| `tools/vulkan-local/evidence/p416_pose15_run2.png`（117701 B，地平线 392 = 正立） | `43dbed7c39590c70fb6f10a96fa35d749b0755a28396f60a172bb0502772e4d0` |
| `run/logs/p416-run3.log`（655687 B，14:38:29–14:42:57，终版 B+） | `337b108f90db3ebb08e5faf247a922fe283d0db9537124af536320f3a4a63876` |
| `tools/vulkan-local/evidence/p416_pose15_run3.png`（118674 B，地平线 392 = 正立） | `c76667509db886e4e7e33d266c9d4a6f73d4ee071d8f686d6421cfc0f24834d4` |

## 关键日志行（原文）

### 三跑顶点接线（wired 行 = 代码状态的可复核指纹）

```
[01Oct2026 14:07:24.776] [Render thread/INFO] [dev.vkdisp.VkDisp/]: vkdisp: deferred pipeline wired: fragment=vkdisp_pack:deferred vertex=vkdisp:fullscreen_flipv (P3.3 chain step; flipv by net-parity)
[01Oct2026 14:07:24.776] [Render thread/INFO] [dev.vkdisp.VkDisp/]: vkdisp: final pipeline wired: fragment=vkdisp_pack:final vertex=vkdisp:fullscreen (P4.1.4 final step; no v-flip by attachment-identity)
[01Oct2026 14:20:09.806] [Render thread/INFO] [dev.vkdisp.VkDisp/]: vkdisp: final pipeline wired: fragment=vkdisp_pack:final vertex=vkdisp:fullscreen_flipv (P4.1.4 final step; flipv by p416 orientation fix)
[01Oct2026 14:38:37.883] [Render thread/INFO] [dev.vkdisp.VkDisp/]: vkdisp: composite scene pipeline wired: fragment=vkdisp_pack:composite vertex=vkdisp:fullscreen (no v-flip; P3.2 scene input)
[01Oct2026 14:38:37.883] [Render thread/INFO] [dev.vkdisp.VkDisp/]: vkdisp: deferred pipeline wired: fragment=vkdisp_pack:deferred vertex=vkdisp:fullscreen (P3.3 chain step; no v-flip by scene-source, p416)
[01Oct2026 14:38:37.884] [Render thread/INFO] [dev.vkdisp.VkDisp/]: vkdisp: final pipeline wired: fragment=vkdisp_pack:final vertex=vkdisp:fullscreen (P4.1.4 final step; no v-flip by attachment-identity)
```

### run3：链路与零回归（不变量）

```
[01Oct2026 14:38:46.131] [Render thread/INFO] [dev.vkdisp.VkDisp/]: vkdisp: pack compile done: stages=190 ok=49 failed=141
[01Oct2026 14:38:46.202] [Render thread/INFO] [dev.vkdisp.VkDisp/]: vkdisp: pipeline count check: registered=9, compiled=9 (aligned)
[01Oct2026 14:38:46.204] [Render thread/INFO] [dev.vkdisp.VkDisp/]: vkdisp: final chain wired: composite -> offscreen3 -> main (pack final)
[01Oct2026 14:38:49.147] [Render thread/INFO] [dev.vkdisp.VkDisp/]: vkdisp: deferred chain wired: scene -> offscreen2 -> main (pack deferred)
[01Oct2026 14:38:49.150] [Render thread/INFO] [dev.vkdisp.VkDisp/]: vkdisp: composite input source: deferred output (P3.3)
```

- `解析失败` = **0**（p415 修复仍在）；`fullscreen pass failed` = 0、`Missing uniform` = 0；
  上传三槽 written 26/24/24（与 p414/p415 逐字一致）。
- **WARN/ERROR 集合 diff（run2 → run3，去时间戳逐行比对）= 双向 0 条** ——
  方向修复零告警副作用。

## 判定

| 判据 | 结果 |
|---|---|
| 强制位姿下原码确实翻转（问题成立） | ✅ run1 地平线 204 ≈ 翻转预测 199；暖地在上/暗天在下 |
| 矫正后回正 | ✅ run3 地平线 392 ≈ 正立预测 403；暖地（R−B +9.1）在下、暗天在上 |
| 终版与对冲版同向（B+ 未引入再翻转） | ✅ run3↔run2 地面带 identity=+1.0000（V=+0.3679） |
| 与翻转版互为镜像（单变量闭合） | ✅ run1 地面带 ↔ run3 镜像 = +0.9450（= run1↔run2 同值） |
| 链路/阶段/上传零回归 | ✅ registered=9 compiled=9、stages 190/49/141、final/deferred chain wired、uploaded 26/24/24 |
| 零告警副作用 | ✅ run2→run3 WARN/ERROR 集双向 diff = 0 |
| 包源/ present 路径排除 | ✅ BSL 三程序 texCoord=gl_MultiTexCoord0.xy、final.fsh 无 gl_FragCoord（zipfile 读源核验） |

**未覆盖（登记）**：
1. **fixture/菜单路径的方向抽证**（B+ 后代数上 = P-1f `flipv→main` 原样，
   但本轮未在加载阶段单独截菜单帧实测）；
2. 同位姿逐像素精确镜像判定（run1↔run3 镜像相关 +0.9450 而非 1.0 —— 云带两次
   截图间有飘移，静态地面带才做精确比对）；
3. deferred **depth 配对修正**的直接像素证据（B+ 改了 AO 读深度的行对齐，
   场景中无强阴影对照物，视觉差未做 A/B —— 留 P4.2 切包回归）；
4. p414 证据中「截图未镜像（树冠朝上）」的判据**作废** —— 基于云团构图、分辨不出
   上下，方向判定以本文件强制位姿法为准（p414 判定表该行由本文件取代）。
