var y0 = 0;
function start() { y0 = transform.y; }
function update(dt) {
  transform.y = y0 + Math.sin(time.time * 2.5) * 0.18;
  transform.rotation += 35 * dt;
}
