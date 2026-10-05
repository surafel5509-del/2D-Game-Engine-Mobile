// Grayscale — GLES 2.0 GLSL ES 1.00 starter source.
precision mediump float;
uniform sampler2D uTexture;
varying vec2 vUV;
void main() { vec4 c=texture2D(uTexture,vUV); float g=dot(c.rgb,vec3(.299,.587,.114)); gl_FragColor=vec4(vec3(g),c.a); }
