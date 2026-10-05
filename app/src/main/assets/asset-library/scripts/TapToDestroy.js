function onTap() {
  var fx = scene.spawn("PickupFX", self.worldX, self.worldY);
  if (fx) { fx.burst(18); after(1, function() { fx.destroy(); }); }
  self.destroy();
}
