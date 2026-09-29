#version 330
#extension GL_ARB_separate_shader_objects : require
// vkdisp 几何顶点着色器（P3 前置：真实顶点缓冲 + 深度剔除验证）
// 属性名必须与 VertexFormat.Builder.addAttribute("Position"/"Color") 完全一致（04-SPEC §4 字面一致要求）。

// ⚠️ 顶点属性必须显式给 location：原版用 ARB_separate_shader_objects，SPIR-V 要求用户输入/输出有显式 location。
// 实测报错原文（未加时）：vkdisp:geometry:6: error: 'location' : SPIR-V requires location for user input/output
// location 序号与 VertexFormat 里 addAttribute 的顺序一致（Position=0，Color=1）。
layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;

layout(location = 0) out vec4 vColor;

void main() {
    // 顶点已经是 NDC 坐标（本验证不引入相机矩阵）；z 由顶点数据给定（0.3 / 0.7）。
    gl_Position = vec4(Position, 1.0);
    vColor = Color;
}
