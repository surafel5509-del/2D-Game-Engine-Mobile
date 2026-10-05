// Params: speed=12, lifetime=2
var age = 0;
function start() { self.vy = speed; }
function update(dt) { age += dt; if (age >= lifetime) self.destroy(); }
function onTrigger(other) {
  if (other.tag == "Enemy") { other.destroy(); self.destroy(); }
}
