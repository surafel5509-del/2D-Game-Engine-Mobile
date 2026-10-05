function onTrigger(other) {
  if (other.tag == "Player") {
    audio.beep();
    var score = scene.find("ScoreText");
    if (score) score.text = "Collected!";
    self.destroy();
  }
}
