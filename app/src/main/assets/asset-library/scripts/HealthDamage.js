// Params: health=3
var hp;
function start() { hp = health; }
function onCollision(other) {
  if (other.tag == "Hazard") {
    hp--; log("Health: " + hp);
    if (hp <= 0) self.destroy();
  }
}
