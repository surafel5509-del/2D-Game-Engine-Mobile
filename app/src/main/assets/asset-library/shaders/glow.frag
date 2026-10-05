// Soft radial glow — GLES 2.0 GLSL ES 1.00 starter source.
precision mediump float; uniform sampler2D uTexture; uniform float uGlow; varying vec2 vUV;
void main(){ vec4 c=texture2D(uTexture,vUV); float r=length(vUV-.5); c.rgb+=uGlow*max(0.0,1.0-r*2.0); gl_FragColor=c; }
