// Sepia tone — GLES 2.0 GLSL ES 1.00 starter source.
precision mediump float; uniform sampler2D uTexture; varying vec2 vUV;
void main(){ vec4 c=texture2D(uTexture,vUV); vec3 s=vec3(dot(c.rgb,vec3(.393,.769,.189)),dot(c.rgb,vec3(.349,.686,.168)),dot(c.rgb,vec3(.272,.534,.131))); gl_FragColor=vec4(s,c.a); }
