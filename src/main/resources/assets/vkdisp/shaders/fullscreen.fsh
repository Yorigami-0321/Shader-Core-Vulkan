#version 330
#extension GL_ARB_separate_shader_objects : require
// vkdisp 全屏图案片元着色器（P0.3 验收图案：高饱和品红/青棋盘 + 黄色四角标 + 白色中轴十字）
// 资源 id：vkdisp:fullscreen → 由 RenderPipeline withFragmentShader(Identifier) 解析到本文件。
// 首行必须是 #version（编译器不注入版本行）。

// 与 fullscreen.vsh 的 location=0 一一对应（separate shader objects 布局约定）。
layout(location = 0) in vec2 vUv;
layout(location = 0) out vec4 fragColor;

// P1.1 自定义 uniform 块：名字必须与 BindGroupLayout.withUniform("VkDispParams", UNIFORM_BUFFER) 一致
// （原版约定见 clouds.vsh 的 layout(std140) uniform CloudInfo，无显式 binding 序号）。
// x = phase（秒级相位，驱动棋盘平移：改数值画面就变）、y = intensity（预留）。
layout(std140) uniform VkDispParams {
    vec4 Params;
};

// 采样器：名字与 BindGroupLayout.withUniform("InSampler", COMBINED_IMAGE_SAMPLER) 一致
// （原版 core/blit_depth.fsh 的 uniform sampler2D InSampler 写法）。
uniform sampler2D InSampler;

void main() {
    // 8x8 棋盘：品红 / 青，高饱和纯色，截图一眼可辨是否被本管线覆盖。
    // P1.1：棋盘随 Params.x 每 4 秒平移 2 个格子 → 间隔截图必然不同（uniform 真的传到了 GPU）。
    vec2 boardUv = fract(vUv + vec2(Params.x * 0.25, 0.0));
    vec2 cell = floor(boardUv * 8.0);
    bool odd = mod(cell.x + cell.y, 2.0) < 1.0;
    vec3 color = odd ? vec3(1.0, 0.0, 1.0) : vec3(0.0, 1.0, 1.0);

    // 四角黄色方块（边长约 1/16 屏）：用于确认四角都被画到、方向未翻转。
    vec2 toEdge = min(vUv, vec2(1.0) - vUv);
    if (toEdge.x < 0.0625 && toEdge.y < 0.0625) {
        color = vec3(1.0, 1.0, 0.0);
    }

    // 白色中轴十字：用于确认全屏覆盖与中心位置。
    if (abs(vUv.x - 0.5) < 0.004 || abs(vUv.y - 0.5) < 0.004) {
        color = vec3(1.0, 1.0, 1.0);
    }

    // 采样器链路验证：用自建 16x16 四象限纹理（重复 4x4）调制亮度。
    // 采样到的是红/绿/蓝/白 → 棋盘亮度分区变化，肉眼与像素统计都可判别"纹理真的传进来了"。
    // 一对一映射（不重复）：屏幕象限 ↔ 测试纹理象限，便于用「四象限颜色」量化判定采样是否正确。
    // ⚠️ 实测约定（2026-09-29）：全屏三角形里 vUv.y = 0 对应屏幕**底部**（NDC 方向所致），
    // 而 NativeImage 行 0 是顶部 → 采样前必须翻转 V，否则画面与纹理会上下颠倒（已用四象限色验证）。
    vec3 tint = texture(InSampler, vec2(vUv.x, 1.0 - vUv.y)).rgb;
    color *= 0.35 + 0.65 * tint;

    // 黑色细边框：把画面与窗口边缘区分开，便于截图确认画满整屏。
    if (toEdge.x < 0.01 || toEdge.y < 0.01) {
        color = vec3(0.0, 0.0, 0.0);
    }

    fragColor = vec4(color, 1.0);
}
