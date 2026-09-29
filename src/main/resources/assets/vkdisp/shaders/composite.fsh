#version 330
#extension GL_ARB_separate_shader_objects : require
// vkdisp 合成 pass 片元着色器（P2 前置：采样上一级中间目标 → 处理 → 写下一级中间目标）
// 资源 id：vkdisp:composite → RenderPipeline.withFragmentShader(Identifier) 解析到本文件。
//
// ⚠️ 当前效果是**刻意选择的临时验证效应**：R/B 通道互换。
// 作用：让「这一级 pass 到底有没有执行、有没有真的采样到上一级结果」变成**可像素级判定**的事实
// （图案四角的黄色 (255,255,0) 若被处理过必然变成青色 (0,255,255)；没执行则仍是黄色）。
// 真实包运行时本文件会被 OF/Iris 的 composite 程序替换（P2.4）。

uniform sampler2D InSampler;

layout(location = 0) in vec2 vUv;
layout(location = 0) out vec4 fragColor;

void main() {
    // ⭐ 实测约定（2026-09-29 三 pass 链标定）：
    //   中间目标 → 中间目标 的采样用**原始 vUv**；中间目标 → 主目标 的采样用 1-v（见 blit.fsh）。
    //   两级都做 1-v 会让整链上下颠倒（用图案底部的方向参考带定位验证）；此不对称现象为实测结果，
    //   P3.3 做多级链时会再复核。约定登记见 docs/18-PARALLEL.md §10 P-1f。
    vec3 sampled = texture(InSampler, vUv).rgb;
    fragColor = vec4(sampled.b, sampled.g, sampled.r, 1.0);
}
