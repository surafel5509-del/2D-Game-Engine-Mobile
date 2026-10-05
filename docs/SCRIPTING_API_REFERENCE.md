# S Engine Script API Reference

## Component Access
```js
var renderer = gameObject.get("SpriteRenderer");
var body = gameObject.get("Rigidbody2D");
var audio = gameObject.get("AudioSource");
```

## Scene Access
```js
var scene = gameObject.scene;
var objects = scene.objects;
var child = scene.create("NewObject");
```

## Physics
```js
var rb = gameObject.get("Rigidbody2D");
rb.vx = 10;
rb.vy = 5;
rb.gravityScale = 0.5;
```

## Input
```js
if (engine.input.keys.contains(29)) { // A key
    // Action
}
```

## UI / Animation Events
```js
// Events triggered by engine
function onCreate() {}
function onDestroy() {}
function onUpdate(delta) {}
```

## Audio Control
```js
var audio = gameObject.get("AudioSource");
audio.clip = "music.ogg";
audio.volume = 0.8;
audio.playOnStart = true;
```
