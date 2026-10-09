# h51 · GAP-023 看图判据达成 —— 用 MCP 驱动游戏，截图通道第一次真的落地

> 上一格（h50q，提交 `dfcc186`）证了**机制**：看得见水的朝向里 `depthtex0 ≠ depthtex1` 每帧成立。
> 但看图判据一直悬着，因为 h50o/h50p/h50r 三臂的 **F2 截图通道静默失效**
> （`x11_input.py` 一路打印 `injected F2 OK`，而 `run/h27/screenshots/` 是空的）。
> 本格把截图通道换成 **MCP（mcpfabric）驱动**，一次就通。

## 〇、关键更正：MCP 驱动没有被权限层拦

多处文档/脚本写着「本机 mcpfabric 驱动被权限层拦，所以走 X11 键注入」
（`08-TESTING.md:379`、`tools/vulkan-local/x11_input.py:550`、`h48_flicker_capture.sh:7`）。
**本格实测这条不成立。** 隔离车道客户端在跑时，端口 `25600` 在听，
`tools/mcp-drive.py`（stdio 直连）调下列工具全部拿到正确回执：

| 工具 | 结果 |
|---|---|
| `get_self` / `list_players` | 返回玩家 `Dev` 的真实坐标/朝向 |
| `run_command "weather clear"` | `success=true, output=["Set the weather to clear"]` |
| `set_time {time:6000}` | 命中（时钟已是 6000 时回 already-set） |
| `teleport_player {yaw,pitch}` | `Teleported Dev to …`，`get_self` 复核 yaw/pitch 已变 |
| `screenshot` | 落地**真实渲染帧**（base64 PNG，内容随朝向变化） |
| `describe_scene` | 返回逐 ray 命中块（用来独立核实画面里有没有水） |

被 h46 §6.1 真正拦的是「在**仓库外**第三方目录跑 `./gradlew` 构建 mcpfabric」
与「配置不热加载进当前 AI 会话」——这两件事都不等于「不能驱动游戏」。
`mcp-drive.py` 走的是 stdio server + 车道 token，与这两条限制无关。

⇒ **X11 F2 注入这条通道可以退役**：它依赖合成器把键盘焦点留在 MC 表面，
而本机 `GetInputFocus` 恒回 `PointerRoot`（焦点由合成器持有）⇒ F2 落不进 MC，
注入器却照打 `OK`。MCP 的 `screenshot` 直读渲染目标，不依赖窗口焦点。

## 一、臂与闸门

入口 `run-client.sh iso -PquickPlay`；档位核验：`[STORE_RESIDUE_NONE]`（残留 0 项 = 包默认档）、
后端 `Using graphics backend Vulkan … llvmpipe`。
配置（`vkdisp-client.toml`）：`terrain=true packTerrainShader=true packWater=true
cloudsPass=false enabled=false depthGlProxy=false pixelProbe=true pixelProbeEvery=1
pixelProbeAfterTerrain=true`。观测面钉死：`time=6000`（正午）+ `weather clear`。
机位用 MCP `teleport_player` 定在 h50q 实测的三个朝向，玩家坐标 `-7.3091 96.0 -11.0535`。

## 二、读数（探针）+ 看图（截图）两条通道同臂对齐

| 机位 | describe_scene 水 ray | `c1@afterTerrain` 非0 | `depthviz0` | `depthviz1` | `0≠1` | 截图 luma / 看图 |
|---|---|---|---|---|---|---|
| yaw 270 / pitch 0 | — | 16/16 | 3.269 | **3.635** | **16/16** | luma 93，右侧有深色反光水面 |
| yaw 315 / pitch 15 | 23/48 | 21/21 | 4.022 | **4.588** | **21/21** | luma 99，前景有水面 + 反射 |
| yaw 0 / pitch 0（对照） | 7/48 | 0/18 | 6.697 | 6.697 | **0/18** | 该帧为近黑（见下） |

两条通道互相印证：看得见水的两个朝向，**探针**里 `depthtex0 ≠ depthtex1` 每帧成立、
`c1`（水的第二个输出）每帧非 0，**截图**里也真的能看到被 vkdisp 管线画出来的水面与反射。
yaw 0 的 `depthviz0 == depthviz1`（0/18）是白送的对照：没有半透明几何时两个时刻本就该相等
（OF 语义下的正确退化）。

⇒ **GAP-023 的看图判据达成**：BSL `composite.glsl:333 z1 > z0`（半透明/水体识别）
拿到的两个数，不只是机制上「可能不同」（h50q），而是在**真实渲染帧里能看见**对应的水面。

## 三、照实记的两点

1. **yaw 0 对照帧近黑（luma 6.5，连拍 5 张逐字节相同 28220B）**。它**不是** GAP-023 的判据
   （判据在探针的 `depthviz0==depthviz1` 那一格，与屏幕亮度无关），这里只作「探针对照」用。
   连拍逐字节相同 ⇒ 更像是「这个朝向本身画面暗」而非 GAP-020 的随机黑帧（随机黑帧会时黑时亮）。
   不深究，照 X55：本机 lavapipe 的黑帧/亮度不作正确性结论。
2. **水面的最终观感是否逐项对齐 BSL/Iris 参考**本格未判（需要同世界同机位的 Iris 对照帧）。
   本格证到的是「水经 vkdisp 的 `gbuffers_water` 画进了画面 + `depthtex1` 是独立层且被用上」，
   不是「像素级对齐参考实现」。

## 四、GAP-027 侧没弄坏

`[GAP-027] pack gbuffer fragment ready: program=world0/gbuffers_water outputs=2
declaredSlots=[0, 1] samplers=8` 仍在；`[GAP-023] depthtex1 绑的是 1x1 D32@0.0 桩`
与 `taken=[0, 1, 2]/3` 两条自报都在。

截图：`evidence/h51-images/h51-water-yaw270.png` / `h51-water-yaw315.png` / `h51-nowater-yaw0.png`。
