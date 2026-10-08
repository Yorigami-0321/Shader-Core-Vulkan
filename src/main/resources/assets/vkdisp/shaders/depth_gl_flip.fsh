#version 330
#extension GL_ARB_separate_shader_objects : require
// vkdisp GAP-022 ①：GL 口径深度代理片元（引擎反向 Z → 包假定的「1.0 = 天空」）
// 资源 id：vkdisp:depth_gl_flip → RenderPipeline.withFragmentShader(Identifier) 解析到本文件。
// 顶点复用 vkdisp:fullscreen（同一个全屏三角形），与 depthviz.fsh / blit.fsh 同一条管线形状。
//
// 🔖 为什么是 `1.0 - r` 而不是任何按 (near, far) 的线性/双曲重映射：
//   docs/13-GAP-REGISTRY.md GAP-022 的「由上面推出的正确换算」行已给出代数证明 ——
//   同一组透视参数下 GL 口径 z_gl(d) = (f/(f−n))(1 − n/d)、引擎 z_en(d) = (n/(f−n))(f/d − 1)，
//   两式恒满足 z_gl = 1 − z_en（与 d 无关），所以**一次逐像素取反就是全精度正确**的换算。
//
// 🔖 为什么用**原始 vUv**（不 1−v）：本 pass 是「我方离屏深度 → 我方离屏代理」，
//   按 docs/18-PARALLEL.md §10 P-1f 的标定规则（同 depthviz.fsh:12 那一行）取原始 vUv。
//   这不只是取向问题，它是「代理可以原地顶替 depthtex0」的前提：原始 vUv ⇒ 第 y 行读第 y 行
//   ⇒ 代理的第 y 行就是引擎深度的第 y 行 ⇒ 链里用同一个 texCoord 采 color 与 depth 仍然同行。
//
// 🔴 目标格式是**单通道** R32F（见 dev.vkdisp.bridge.DepthGlProxy#proxyFormat）：
//   只有 .r 存在，写进 .g/.b 是无效操作；包侧读的是 texture2D(depthtex0, uv).x ⇒ 正好对上。
//   .a 写 1.0 只为「未初始化的像素看起来不像天空」这条判据留手（GL 口径里 1.0 = 天空，
//   真没写进去的像素应当显形，不该静默当成贴脸几何）。

uniform sampler2D InSampler;

layout(location = 0) in vec2 vUv;
layout(location = 0) out vec4 fragColor;

void main() {
    float engineDepth = texture(InSampler, vUv).r;
    fragColor = vec4(1.0 - engineDepth, 0.0, 0.0, 1.0);
}
