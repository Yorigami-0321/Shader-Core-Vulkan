#version 330
#extension GL_ARB_separate_shader_objects : require
// vkdisp 几何片元着色器：输出插值后的顶点色（用于判定重叠区最终由哪个片元胜出）。

layout(location = 0) in vec4 vColor;
layout(location = 0) out vec4 fragColor;

void main() {
    fragColor = vColor;
}
