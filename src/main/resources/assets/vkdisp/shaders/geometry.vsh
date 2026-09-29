#version 330
#extension GL_ARB_separate_shader_objects : require
// vkdisp 几何顶点着色器（P3 前置：真实顶点缓冲 + 深度剔除验证）
// 属性名必须与 VertexFormat.Builder.addAttribute("Position"/"Color") 完全一致（04-SPEC §4 字面一致要求）。

// ⚠️ 顶点属性必须显式给 location：原版用 ARB_separate_shader_objects，SPIR-V 要求用户输入/输出有显式 location。
// 实测报错原文（未加时）：vkdisp:geometry:6: error: 'location' : SPIR-V requires location for user input/output
// location 序号与 VertexFormat 里 addAttribute 的顺序一致（Position=0，Color=1）。
layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;

// P3.1 前置：光空间矩阵（std140 mat4，块名必须与 BindGroupLayout.withUniform("LightMatrix") 一致）。
layout(std140) uniform LightMatrix {
    mat4 uLight;
};

layout(location = 0) out vec4 vColor;
layout(location = 1) out vec3 vWorldPos;

void main() {
    // 顶点数据是「世界」坐标（z 由顶点数据给定：0.3 近 / 0.7 远）。两条管线共用本文件：
    //   SHADOW_MAP_PASS（管线 define）→ 渲染进阴影贴图：裁剪空间 = 光空间
    //   否则（相机视图，P3.3 世界渲染）→ 裁剪空间 = 占位 NDC 视图（真实相机矩阵归 P3.2/3.3 主体）
    // 世界坐标两种情况都输出，供阴影采样回算光空间坐标。
#ifdef SHADOW_MAP_PASS
    gl_Position = uLight * vec4(Position, 1.0);
#else
    gl_Position = vec4(Position, 1.0);
#endif
    vWorldPos = Position;
    vColor = Color;
}
