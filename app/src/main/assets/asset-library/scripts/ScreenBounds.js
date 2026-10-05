// Params: halfWidth=8, halfHeight=5, margin=0.4
function update(dt) {
  transform.x = clamp(transform.x, -halfWidth + margin, halfWidth - margin);
  transform.y = clamp(transform.y, -halfHeight + margin, halfHeight - margin);
}
