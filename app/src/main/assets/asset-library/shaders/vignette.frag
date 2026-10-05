// Vignette — GLES 2.0 GLSL ES 1.00 starter source.
precision mediump float; uniform sampler2D uTexture; uniform float uStrength; varying vec2 vUV;
void main(){ vec4 c=texture2D(uTexture,vUV); vec2 q=vUV-.5; float v=1.0-uStrength*dot(q,q)*2.8; gl_FragColor=vec4(c.rgb*clamp(v,0.0,1.0),c.a); }
