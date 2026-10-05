// Warm palette remap — GLES 2.0 GLSL ES 1.00 starter source.
precision mediump float; uniform sampler2D uTexture; varying vec2 vUV;
void main(){ vec4 c=texture2D(uTexture,vUV); vec3 warm=vec3(c.r*1.05,c.g*.94,c.b*.78); gl_FragColor=vec4(clamp(warm,0.0,1.0),c.a); }
