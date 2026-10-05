// Params: speed=5
function update(dt) {
  var x = input.axisX, y = input.axisY;
  var len = Math.sqrt(x*x + y*y);
  if (len > 1) { x /= len; y /= len; }
  self.vx = x * speed; self.vy = y * speed;
}
