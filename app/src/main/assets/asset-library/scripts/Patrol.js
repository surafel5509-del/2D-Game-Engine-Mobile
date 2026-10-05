// Params: distance=3, speed=2
var origin = 0;
function start() { origin = transform.x; }
function update(dt) {
  transform.x = origin + Math.sin(time.time * speed) * distance;
}
