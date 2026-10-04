#version 330
#extension GL_ARB_separate_shader_objects : require

// vkdisp 地形顶点适配层（GAP-003 方案 A / 包地形片元接线）
//
// 为什么需要它：包的 gbuffers_terrain 是**一对**顶点/片元，原版只提供 core/terrain 这一对。
// 把包片元单独接到原版顶点侧不行 —— 包片元要的 9 条 OF varying（location 0..8）与原版
// core/terrain 产出的 5 条（sphericalVertexDistance/cylindricalVertexDistance/vertexColor/
// texCoord0/chunkVisibility）**位置与类型都不重合**，链接期即失败。
// 而包的顶点着色器也不能直接用：它要 7 个顶点属性（含 Normal / mc_Entity / mc_midTexCoord），
// 原版地形顶点缓冲（DefaultVertexFormat.BLOCK）只有 4 个（Position/Color/UV0/UV2）——
// 增列要改区块网格化，属另一层工程。本层因此**只做适配**：按原版顶点格式取数，
// 产出包片元要的 9 条 varying，位置逐条对齐。
//
// 每条 varying 的取值口径（真值 / 常量逐条标注，X9 不猜）：
//   0 mat        ← 常量 0.0   ⚠ 包 VS 由 mc_Entity.x/100 的方块 id 推出；原版地形缓冲无此属性
//   1 recolor    ← 常量 0.0   ⚠ 同上
//   2 texCoord   ← UV0                        ✅ 真值
//   3 lmCoord    ← UV2 / 16.0 后套用包 VS 原 clamp  ✅ 真值（单位换算：OF 0..1 ↔ 原版 0..15 格）
//   4 normal     ← 常量 (0,1,0) ⚠ 包 VS 由 Normal 属性推出；原版 BLOCK 顶点格式**没有** Normal
//   5 sunVec     ← VkDispTerrainParams.SunDir   ✅ 真值（OfUniformManager 的眼空间 sunPosition）
//   6 upVec      ← ModelViewMat[1].xyz          ✅ 真值（与包 VS 同一条算式）
//   7 eastVec    ← ModelViewMat[0].xyz          ✅ 真值（与包 VS 同一条算式）
//   8 color      ← Color（再套包 VS 的 alpha 兜底） ✅ 真值（包片元自己再乘光照，不用顶点侧乘 lightmap）
//
// 🔖 三条 ⚠ 常量项是**已知缺口**（登记为 GAP-007）：mat / recolor / normal。
//   它们影响的是「哪些方块被认成树叶/自发光」「法线朝向」这类**光照细节**，
//   不影响「包的片元真的跑在地形 draw 上」这一结论 —— 后者才是本层要证明的。

#include <minecraft:globals.glsl>
#include <minecraft:projection.glsl>
#include <minecraft:terrainglobals.glsl>
#ifndef MULTIDRAW_TERRAIN
    #include <minecraft:chunksection.glsl>
#endif

// 与原版 DefaultVertexFormat.BLOCK 逐属性、逐 location 对齐（字节码核实，见类注释）。
layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;
layout(location = 2) in vec2 UV0;
layout(location = 3) in ivec2 UV2;
#ifdef MULTIDRAW_TERRAIN
layout(location = 4) in ivec3 ChunkPosition;
layout(location = 5) in float ChunkVisibility;
#endif

// GAP-004 的自定义块：眼空间太阳方向。块名必须与绑定组布局里的 uniform 名逐字一致。
layout(std140) uniform VkDispTerrainParams {
    vec4 SunDir;
};

// 包片元要的 9 条 varying（位置与 PackTerrainProgram.parse 的契约逐条对齐）。
layout(location = 0) out float mat;
layout(location = 1) out float recolor;
layout(location = 2) out vec2 texCoord;
layout(location = 3) out vec2 lmCoord;
layout(location = 4) out vec3 normal;
layout(location = 5) out vec3 sunVec;
layout(location = 6) out vec3 upVec;
layout(location = 7) out vec3 eastVec;
layout(location = 8) out vec4 color;

void main() {
    // 逐字沿用原版 core/terrain 的位置算式（Position 与 ChunkPosition 同名不同源，
    // 非多重绘制时来自 ChunkSection 块，多重绘制时来自实例化顶点属性 —— 两者本文件都声明了）。
    vec3 pos = Position + (vec3(ChunkPosition) - vec3(CameraBlockPos)) + CameraOffset;
    gl_Position = ProjMat * ModelViewMat * vec4(pos, 1.0);

    texCoord = UV0;
    // OF 的 UV2 是 0..1 的光照贴图坐标；原版是 0..15 的整数格 ⇒ 先除 16 再套包 VS 原 clamp。
    lmCoord = clamp((vec2(UV2) / 16.0 - 0.03125) * 1.06667, vec2(0.0), vec2(0.9333, 1.0));

    color = Color;
    if (color.a < 0.1) {
        color.a = 1.0;
    }

    mat = 0.0;
    recolor = 0.0;
    normal = vec3(0.0, 1.0, 0.0);

    sunVec = normalize(SunDir.xyz);
    upVec = normalize(ModelViewMat[1].xyz);
    eastVec = normalize(ModelViewMat[0].xyz);
}
