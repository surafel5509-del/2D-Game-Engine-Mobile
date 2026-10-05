// Params: jump=10, moveSpeed=6
function update(dt) {
  self.vx = input.axisX * moveSpeed;
  if (input.aDown && self.grounded) self.addImpulse(0, jump);
}
