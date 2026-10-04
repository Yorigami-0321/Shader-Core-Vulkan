# h10 · GAP-008 切分开跑：中性材质贴图**证伪了「ao/金属度是主因」**，并揪出一个每帧抛的回归

> 任务来源：`AGENT_CONTEXT.md` §10.15 ⑨ 第 1 条 —— **GAP-008 逐项切分：先定位，不猜绑定**。
> 取证方式：静态链路分析 + MCP 驱动真实客户端的单变量 A/B。
>
> 判定：🟡 **部分推进**。假设被实验**证伪**（这是有用的结果）；定位收敛到唯一剩下的乘法项；
> 对应的单变量实验已实现并通过无头测试，但**本轮客户端没进世界**，实验未执行。

---

## 〇、一句话结论

**「把 `specular`/`normals` 绑成方块图集导致全黑」这个假设是错的。** 换成中性材质贴图后
画面仍然全黑（尽管 48.41% 的像素确实变了）。真正把 `albedo` 乘到 0 的是
**`sceneLighting *= skylightSqr`，而 `skylightSqr = lightmap.y²`、`lightmap = clamp(lmCoord, 0, 1)`**
—— 我们的 `lmCoord` 取自原版**把天光与块光打包进同一个 UV2** 的顶点属性，天光通道恒为 0。

---

## 一、先把乘法链**静态列全**（不猜，逐行读转译终稿）

BSL v10.1.8 高级材质路径（`world0/gbuffers_terrain`，1483 行）对 `albedo` 的**全部**乘法：

```
albedo = texture(texture_0, texCoord) * vec4(color.rgb, 1.0);   // 真值
albedo.rgb = pow(albedo.rgb, vec3(2.2));                       // gamma，非乘性衰减
GetMaterials(..., out float ao, ...) {                          // 第 1218–1250 行
    specularMap = textureLod(specular, newCoord, 0);
    smoothness  = specularMap.r;   f0 = specularMap.g;
    ao = textureGrad(normals, newCoord, dcdx, dcdy).z;
}
albedo.rgb *= ao * ao;                                          // ★ 候选 1
albedo.rgb *= 1.0 - metalness * smoothness;                      // ★ 候选 2
GetLighting(albedo.rgb, ...):                                   // 第 882–970 行（inout）
    float skylightSqr = lightmap.y * lightmap.y;                // ★ 候选 3
    sceneLighting *= skylightSqr * (1.0 + scattering * shadow);
    albedo *= max(sceneLighting + blockLighting + emissiveLighting
                   + nightVisionLighting + minLighting, vec3(0.0));
    albedo *= vanillaDiffuse * smoothLighting * smoothLighting;
}
albedo.rgb *= (1.0 - fresnel3 * smoothness * smoothness * (1.0 - metalness));  // smoothness=0 ⇒ 恒等
albedo.rgb = sqrt(max(albedo.rgb, vec3(0.0)));
```

🔖 默认配置（1 槽）路径里 **`GetMaterials` 出现 0 次** —— 它整段在 `defined ADVANCED_MATERIALS` 里。
这解释了「为什么只有开高级材质才全黑」，也说明候选 1/2 **只在高级材质路径起作用**。

---

## 二、实验 1：`specular` / `normals` 绑方块图集 ⇒ 改成**中性材质贴图**（已执行，**假设被证伪**）

### 2.1 为什么「中性值」不是编一个假的输入

候选 1/2 都是**乘法**，所以正确的缺省不是「随便找张贴图」，而是**乘法单位元**：

| 贴图 | 取值 | 推导 |
|---|---|---|
| `specular` | `(0,0,0,255)` | `smoothness=0`、`f0=0` ⇒ `metalness=0`、`porosity=0`、`emissionMat=0` ⇒ `1 - 0*0 = 1` |
| `normals` | `(128,128,255,255)` | 解码后 `normalMap=(0,0,1)`、**`.z = 1.0`** ⇒ `ao = 1.0` ⇒ `ao*ao = 1` |

语义 =「这张方块**没有材质覆盖、没有 AO、法线朝上**」—— 可解释的缺省，不是假的输入。
实现：新增 `bridge/NeutralMaterialMaps`（4×4 非二次幂，规避部分驱动的 mip 限制）。

### 2.2 实测（同一存档 / `time set 6000` / `yaw=35,pitch=-12`）

~~~
vkdisp: [GAP-009] neutral material map created: specular (4x4, RGBA=0,0,0,255)
vkdisp: [GAP-009] neutral material map created: normals  (4x4, RGBA=128,128,255,255)
vkdisp: [GAP-003] pack terrain uniforms bound: blockMembers=42 samplers=7
        (texture_0=图集真值; specular/normals=中性单位元 h10; noisetex/shadowcolor0=占位; shadowtex0/1=本 pass 深度)
vkdisp: [GAP-003/A] terrain drawn into 8 attachment(s) pass (group=OPAQUE, draws=1)
~~~

图 A = h09（绑方块图集）、图 B = h10（中性材质贴图），取图区 y 80..435：

| 指标 | 值 |
|---|---:|
| 平均绝对差（逐通道） | **2.94** |
| 最大通道差 | **33** |
| 变化像素占比 | **48.41%** |
| 画面是否仍全黑 | **是** |

🔖 **结论：假设被证伪。** 两个采样器**确实被读到了**（48.41% 像素变了，不是「完全没生效」），
但它们**不是主因**。若当初直接「改个看起来对的绑定」并宣布修好，就是一次**假绿**。

---

## 三、🔴 顺带揪出并修掉一个**每帧抛异常**的回归（本轮自己造的）

第一版把中性贴图的创建放在 `bindPackTerrainUniforms`（**pass 打开期间**），实测：

~~~
java.lang.IllegalStateException: Close the existing render pass before performing additional commands
  at FrontendCommandEncoder.writeToTexture(FrontendCommandEncoder.java:465)
  at dev.vkdisp.bridge.NeutralMaterialMaps.create(NeutralMaterialMaps.java:113)
  at dev.vkdisp.bridge.TerrainPipelineApi.bindPackTerrainUniforms(TerrainPipelineApi.java:582)
  at dev.vkdisp.bridge.MrtTerrainPass.drawTerrain(MrtTerrainPass.java:358)
~~~

**每帧一次**（懒建在 pass 内永远失败 ⇒ 永远重建）。修法：改成 `ensureCreated()`，
在 `createRenderPass` **之前**调用 —— 与本项目既有的 `MappableRingBuffer` map/close 纪律同源。
复跑实测：贴图创建 1 次、pass 正常执行、**0 条 `pass failed`**。

---

## 四、定位收敛到候选 3（`lmCoord` / 天光通道）

`skylightSqr = lightmap.y²`，`lightmap = clamp(lmCoord, 0, 1)`。而适配层现在给的是
`lmCoord = clamp((vec2(UV2) / 16.0 - 0.03125) * 1.06667, …)`。

🔴 **原版把「天光」与「块光」两个通道打包进同一个 UV2**（`uv2.y` 在地形网格里恒为 0），
于是 `lmCoord.y ≡ 0` ⇒ `skylightSqr ≡ 0` ⇒ `sceneLighting ≡ 0`，
剩下 `blockLighting + minLighting` 兜底 —— 若这两项也接近 0，画面就是**纯黑**。
OF 语义下 `lmCoord` 应当是 `(块光, 天光)` 两条独立通道 ⇒ **这是一处真实的映射错误**。

⚠️ **本轮尚未用实验坐实**（见 §五）：静态链路读得通，但「静态读得通」不等于「就是它」。

---

## 五、🟡 实验 2 已实现并通过无头测试，**但本轮客户端没进世界，未执行**

为了不靠猜，做了一个**只改一个 varying** 的诊断开关 `mrt.terrainFullLightProbe`（默认关）：
打开时 `lmCoord = vec2(1.0)`，其余 14 条 varying 与 7 个采样器绑定**全部不动**。
判定口径：若地形变亮 ⇒ 压零项就是天光通道（`lmCoord`）；若仍全黑 ⇒ 候选 3 也被证伪，继续往下找。

⚠️ **执行失败（诚实记录）**：客户端启动后停在主菜单、**没有加载世界**，
4 分 26 秒后 FML 正常关闭退出（`Closing FML Loader`，**不是崩溃**）。
jstack 在上一趟已证实这种状态下渲染线程空闲在 `limitDisplayFPS`；
`--quickPlaySingleplayer` 本轮**未生效**（本项目已观测到该行为有随机性：h09 首跑与 h10 探针跑各一次未进世界，
其余各次都正常进世界）。⇒ **实验 2 处于「已就绪未执行」状态，交下一轮重跑**。

---

## 六、本轮的净结果

| 项 | 状态 |
|---|---|
| 乘法链静态列全（3 个候选 + 1 个已排除） | ✅ 完成 |
| 候选 1/2（`ao` / 金属度） | ❌ **实验证伪**（48.41% 像素变了但仍黑） |
| 候选 3（`lmCoord` 天光） | 🟡 静态链路成立，**实验已就绪未执行** |
| 中性材质贴图（缺省语义 + 实现） | ✅ 落地并跑通（GAP-009） |
| 每帧抛的 `IllegalStateException` 回归 | ✅ 修掉并复跑验证 |
| 单变量诊断开关 | ✅ 落地（`mrt.terrainFullLightProbe`） |

---

## 七、稳定（支柱②）

| 判据 | 值 |
|---|---|
| 复跑 `vkdisp` ERROR | **0** |
| `pass failed` | **0**（修前每帧 1 条） |
| `ShaderCompileException` | **0** |
| 客户端崩溃 | 无（探针跑是正常关闭退出） |
| 残留游戏进程 | **0** |

⚠️ 仍**不**声称「0 validation error」（本机无 Vulkan validation layer，沿用 §9.4.15 纪律）。

---

## 八、测试

`./gradlew build` ⇒ BUILD SUCCESSFUL（本轮未新增用例，但**改了生成器签名**，
`PackVertexAdapterGeneratorTest` 5 例与 `PackTerrainSourceTest` 的签名对账用例同步跟改并保持全绿）。

---

## 九、本轮**没有**证明的（不许当已完成引用）

1. ⛔ **压零项尚未坐实**。候选 1/2 已证伪；候选 3 静态成立但实验未跑 ⇒ GAP-008 仍开着。
2. ⛔ 客户端 quickPlay 的随机不生效**未定位**（无头/无世界时复现率约 2/6）。
3. ⛔ 中性材质贴图只是**缺省**，不等于真材质：高级材质的光滑度/金属度/材质法线**仍不成立**（GAP-009）。
4. ⛔ 地形仍只画进我方 pass，主目标由原版绘制 ⇒ **仍不产出用户可见画面改进**（M-04 未做）。
5. ⛔ 原版 UV2 的精确打包位序**未从源码坐实**（在 `client/renderer/chunk` 15 个文件里 grep 未命中，
   网格化代码可能不在该 sources jar 内）⇒ 下一轮要么定位到源码，要么**用实验反推**（把两个候选通道都试一遍）。

---

## 十、产物与哈希

| 文件 | sha256 |
|---|---|
| `evidence/h10-images/h10-D-neutral-material-maps.png` | `7623bdca8ff5f5f7619728cbfafe92c9c8debcace4b8c5986f048cb46161fcd1` |
| `run/logs/latest.log`（探针跑；未进世界） | `80f0fbcf9fd098b37c0d7f3e882c74829812a7d5982302c418ec1bbf57ec7353` |

---

## 十一、下一轮入口（按序）

1. **重跑实验 2**：`mrt.terrainFullLightProbe=true` + 高级材质，MCP 截图 ⇒ 判定候选 3。
   若成立 ⇒ 实现 `lmCoord` 的**正确通道映射**（块光 / 天光分开），并写死回归断言。
2. 若候选 3 也被证伪 ⇒ 下一个候选是 `GetLighting` 末尾的
   `albedo *= vanillaDiffuse * smoothLighting²`（`vanillaDiffuse` 依赖 `upVec`/`eastVec`/`newNormal`）
   —— 用同样的单变量法切。
3. **GAP-007**：地形顶点格式加 `Normal` + 方块 id（常量项已从 3 条涨到 7 条）。
4. **M-04**（仍需用户裁决）：把地形接进主链。
