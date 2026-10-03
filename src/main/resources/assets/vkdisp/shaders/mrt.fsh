#version 330
#extension GL_ARB_separate_shader_objects : require
// vkdisp 多附件（MRT）写入片元 —— GAP-003 的能力验证件
// 资源 id：vkdisp:mrt → RenderPipeline.withFragmentShader(Identifier) 解析到本文件。
//
// 顶点复用 vkdisp:fullscreen（全屏三角形，vUv 在 location 0）。
// 本片元**同时**写三个颜色输出，对应三个 render pass 附件：
//   location 0 → colortex0 / albedo
//   location 1 → colortex1 / normal + lightmap
//   location 2 → colortex2 / material
//
// 🔖 R 通道写入**逐槽不同的常量指纹**（0 / 1/3 / 2/3，见 MrtPlan.fingerprintR）：
// 「三个附件都拿到了内容」用截图无法区分（都可能是同一个渐变），
// 而 R 通道均值能唯一指出这张图拍的是哪一槽 —— 可量化判读，不靠肉眼。
// G/B 通道放 vUv 渐变，用来确认每个附件都是**全屏有效内容**而非单像素。

layout(location = 0) in vec2 vUv;

layout(location = 0) out vec4 gAlbedo;
layout(location = 1) out vec4 gNormal;
layout(location = 2) out vec4 gMaterial;

void main() {
    gAlbedo = vec4(0.0, vUv.x, vUv.y, 1.0);
    gNormal = vec4(1.0 / 3.0, vUv.x, vUv.y, 1.0);
    gMaterial = vec4(2.0 / 3.0, vUv.x, vUv.y, 1.0);
}