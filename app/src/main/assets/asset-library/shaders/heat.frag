// Heat palette — GLES 2.0 GLSL ES 1.00 starter source.
precision mediump float; uniform sampler2D uTexture; varying vec2 vUV;
void main(){ vec4 c=texture2D(uTexture,vUV); float l=dot(c.rgb,vec3(.299,.587,.114)); vec3 p=vec3(smoothstep(0.0,.6,l),smoothstep(.25,.85,l),smoothstep(.65,1.0,l)); gl_FragColor=vec4(p,c.a); }
