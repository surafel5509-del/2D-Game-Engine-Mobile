// Params: speed=5
function update(dt) {
  self.vx = input.axisX * speed;
  if (input.axisX < -0.1) self.flipX = true;
  else if (input.axisX > 0.1) self.flipX = false;
}
