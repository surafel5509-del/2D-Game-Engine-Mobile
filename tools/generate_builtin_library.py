#!/usr/bin/env python3
"""Generate S Engine's original, offline starter asset library (100 entries).

All artwork is drawn procedurally with Python's standard library; sounds are
synthesized locally. Re-run this script after changing the catalog/art direction.
"""
from __future__ import annotations

import json
import math
import random
import struct
import wave
import zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1] / "app/src/main/assets/asset-library"
ROOT.mkdir(parents=True, exist_ok=True)


def rgba(hex_color: str, alpha: int = 255):
    h = hex_color.lstrip("#")
    return tuple(bytes.fromhex(h)) + (alpha,)


class Canvas:
    def __init__(self, w: int, h: int, bg=(0, 0, 0, 0)):
        self.w, self.h = w, h
        self.p = bytearray(bg * (w * h))

    def pixel(self, x, y, c):
        if 0 <= x < self.w and 0 <= y < self.h:
            i = (y * self.w + x) * 4
            self.p[i:i+4] = bytes(c)

    def rect(self, x0, y0, x1, y1, c):
        x0, x1 = sorted((int(x0), int(x1)))
        y0, y1 = sorted((int(y0), int(y1)))
        x0, x1 = max(0, x0), min(self.w - 1, x1)
        y0, y1 = max(0, y0), min(self.h - 1, y1)
        if x1 < x0 or y1 < y0:
            return
        row = bytes(c) * (x1 - x0 + 1)
        for y in range(y0, y1 + 1):
            i = (y * self.w + x0) * 4
            self.p[i:i+len(row)] = row

    def outline_rect(self, x0, y0, x1, y1, c, width=2):
        for n in range(width):
            self.rect(x0+n, y0+n, x1-n, y0+n, c)
            self.rect(x0+n, y1-n, x1-n, y1-n, c)
            self.rect(x0+n, y0+n, x0+n, y1-n, c)
            self.rect(x1-n, y0+n, x1-n, y1-n, c)

    def circle(self, cx, cy, r, c, fill=True, width=2):
        cx, cy, r = int(cx), int(cy), int(r)
        r2 = r * r
        inner = max(0, r-width) ** 2
        for y in range(max(0, cy-r), min(self.h, cy+r+1)):
            for x in range(max(0, cx-r), min(self.w, cx+r+1)):
                d = (x-cx)**2 + (y-cy)**2
                if d <= r2 and (fill or d >= inner):
                    self.pixel(x, y, c)

    def ellipse(self, cx, cy, rx, ry, c, fill=True, width=2):
        cx, cy, rx, ry = float(cx), float(cy), max(1, float(rx)), max(1, float(ry))
        inner_x, inner_y = max(1, rx-width), max(1, ry-width)
        for y in range(max(0, int(cy-ry)), min(self.h, int(cy+ry)+1)):
            for x in range(max(0, int(cx-rx)), min(self.w, int(cx+rx)+1)):
                d = ((x-cx)/rx)**2 + ((y-cy)/ry)**2
                di = ((x-cx)/inner_x)**2 + ((y-cy)/inner_y)**2
                if d <= 1 and (fill or di >= 1):
                    self.pixel(x, y, c)

    def line(self, x0, y0, x1, y1, c, width=1):
        x0, y0, x1, y1 = map(int, (x0, y0, x1, y1))
        dx, sx = abs(x1-x0), 1 if x0 < x1 else -1
        dy, sy = -abs(y1-y0), 1 if y0 < y1 else -1
        err = dx + dy
        while True:
            self.rect(x0-width//2, y0-width//2, x0+width//2, y0+width//2, c)
            if x0 == x1 and y0 == y1:
                break
            e2 = 2 * err
            if e2 >= dy:
                err += dy; x0 += sx
            if e2 <= dx:
                err += dx; y0 += sy

    def polygon(self, points, c):
        ys = [p[1] for p in points]
        for y in range(max(0, min(ys)), min(self.h, max(ys)+1)):
            xs = []
            for i, (x1, y1) in enumerate(points):
                x2, y2 = points[(i+1) % len(points)]
                if y1 == y2 or y < min(y1, y2) or y >= max(y1, y2):
                    continue
                xs.append(int(x1 + (y-y1) * (x2-x1) / (y2-y1)))
            xs.sort()
            for i in range(0, len(xs)-1, 2):
                self.rect(xs[i], y, xs[i+1], y, c)

    def save(self, path: Path):
        def chunk(tag, data):
            return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", zlib.crc32(tag + data) & 0xffffffff)
        rows = b"".join(b"\0" + self.p[y*self.w*4:(y+1)*self.w*4] for y in range(self.h))
        png = (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">2I5B", self.w, self.h, 8, 6, 0, 0, 0))
               + chunk(b"IDAT", zlib.compress(rows, 9)) + chunk(b"IEND", b""))
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(png)


CHARACTERS = [
    ("Adventurer", "#E8753B", "#F2C28B", "#364A73"), ("Knight", "#8294A8", "#E9C9A0", "#E1B24A"),
    ("Forest Ranger", "#538B55", "#E5B98A", "#D6C17A"), ("Mage", "#694CC1", "#D8B9A4", "#43C7DC"),
    ("Rogue", "#454658", "#C89C78", "#C14E5C"), ("Robot", "#49A9AE", "#CBDDE0", "#F4B942"),
    ("Slime", "#46B870", "#A4ED9A", "#DAF4A5"), ("Skeleton", "#D8D1B9", "#EEE6CC", "#735B56"),
    ("Orc", "#668D43", "#9CC56E", "#A74B3D"), ("Space Pilot", "#4788D3", "#E6C49A", "#F5DC68"),
    ("Ninja", "#38394D", "#D6B394", "#E3505B"), ("Miner", "#BD813D", "#E6C395", "#5B6574"),
    ("Alien", "#6F55B5", "#B0E8D5", "#F18CC8"), ("Pirate", "#914E43", "#E9BE8C", "#E6C14C"),
    ("Archer", "#697C45", "#DEB68B", "#C86C46"), ("Fire Sprite", "#D34D32", "#FFC86C", "#FFE77A"),
    ("Ice Sprite", "#59A6C9", "#D9F3FA", "#9DE6F0"), ("Engineer", "#65758B", "#DDBB91", "#E19B43"),
    ("Viking", "#6481A2", "#E9C29A", "#A75D43"), ("Bat Monster", "#71518D", "#C59BCB", "#D94E69"),
]
PROPS = ["Wood Crate", "Steel Barrel", "Health Potion", "Mana Potion", "Gold Coin", "Silver Key", "Treasure Chest", "Heart Pickup", "Blue Gem", "Torch", "Stone Pillar", "Oak Tree", "Red Mushroom", "Bomb", "Magic Portal", "Wooden Sign", "Lantern", "Crystal", "Door", "Checkpoint Flag"]
BACKGROUNDS = ["Dawn Valley", "Sunset Hills", "Midnight City", "Deep Space", "Nebula Drift", "Ocean Depths", "Coral Reef", "Pine Forest", "Autumn Woods", "Desert Dunes", "Frozen Peaks", "Volcanic Ridge", "Crystal Cave", "Cloud Kingdom", "Moon Surface", "Rainy Harbor", "Meadow Day", "Storm Front", "Starfield Blue", "Pixel Grid Night"]
SOUNDS = ["Coin", "Jump", "Land", "Hit", "Explosion", "Laser", "Power Up", "UI Click", "UI Confirm", "Door Open", "Footstep", "Heart Beat", "Wind Gust", "Rain Drop", "Error", "Victory"]
SCRIPTS = [
("HorizontalMove.js", "Horizontal movement", '''// Params: speed=5\nfunction update(dt) {\n  self.vx = input.axisX * speed;\n  if (input.axisX < -0.1) self.flipX = true;\n  else if (input.axisX > 0.1) self.flipX = false;\n}\n'''),
("TopDownMove.js", "Top-down 8-way movement", '''// Params: speed=5\nfunction update(dt) {\n  var x = input.axisX, y = input.axisY;\n  var len = Math.sqrt(x*x + y*y);\n  if (len > 1) { x /= len; y /= len; }\n  self.vx = x * speed; self.vy = y * speed;\n}\n'''),
("JumpController.js", "Variable-height jump", '''// Params: jump=10, moveSpeed=6\nfunction update(dt) {\n  self.vx = input.axisX * moveSpeed;\n  if (input.aDown && self.grounded) self.addImpulse(0, jump);\n}\n'''),
("Patrol.js", "Ping-pong patrol", '''// Params: distance=3, speed=2\nvar origin = 0;\nfunction start() { origin = transform.x; }\nfunction update(dt) {\n  transform.x = origin + Math.sin(time.time * speed) * distance;\n}\n'''),
("Collectible.js", "Collectible trigger", '''function onTrigger(other) {\n  if (other.tag == "Player") {\n    audio.beep();\n    var score = scene.find("ScoreText");\n    if (score) score.text = "Collected!";\n    self.destroy();\n  }\n}\n'''),
("HealthDamage.js", "Damage on collision", '''// Params: health=3\nvar hp;\nfunction start() { hp = health; }\nfunction onCollision(other) {\n  if (other.tag == "Hazard") {\n    hp--; log("Health: " + hp);\n    if (hp <= 0) self.destroy();\n  }\n}\n'''),
("Projectile.js", "Timed projectile", '''// Params: speed=12, lifetime=2\nvar age = 0;\nfunction start() { self.vy = speed; }\nfunction update(dt) { age += dt; if (age >= lifetime) self.destroy(); }\nfunction onTrigger(other) {\n  if (other.tag == "Enemy") { other.destroy(); self.destroy(); }\n}\n'''),
("RotateBob.js", "Floating, rotating pickup", '''var y0 = 0;\nfunction start() { y0 = transform.y; }\nfunction update(dt) {\n  transform.y = y0 + Math.sin(time.time * 2.5) * 0.18;\n  transform.rotation += 35 * dt;\n}\n'''),
("ScreenBounds.js", "Keep an object inside a camera-sized region", '''// Params: halfWidth=8, halfHeight=5, margin=0.4\nfunction update(dt) {\n  transform.x = clamp(transform.x, -halfWidth + margin, halfWidth - margin);\n  transform.y = clamp(transform.y, -halfHeight + margin, halfHeight - margin);\n}\n'''),
("SpawnTimer.js", "Periodic object spawner", '''// Params: objectName=Enemy, interval=2\nfunction start() { every(interval, function() { scene.spawn(objectName, transform.x, transform.y); }); }\n'''),
("CameraShakeOnHit.js", "Camera feedback on collision", '''function onCollision(other) {\n  cameraShake(0.18, 0.14);\n  audio.beep(60);\n}\n'''),
("TapToDestroy.js", "Tap to remove an object", '''function onTap() {\n  var fx = scene.spawn("PickupFX", self.worldX, self.worldY);\n  if (fx) { fx.burst(18); after(1, function() { fx.destroy(); }); }\n  self.destroy();\n}\n'''),
]
SHADERS = [
("tint.frag", "Texture tint", """precision mediump float;\nuniform sampler2D uTexture;\nuniform vec4 uTint;\nvarying vec2 vUV;\nvoid main() { gl_FragColor = texture2D(uTexture, vUV) * uTint; }\n"""),
("grayscale.frag", "Grayscale", """precision mediump float;\nuniform sampler2D uTexture;\nvarying vec2 vUV;\nvoid main() { vec4 c=texture2D(uTexture,vUV); float g=dot(c.rgb,vec3(.299,.587,.114)); gl_FragColor=vec4(vec3(g),c.a); }\n"""),
("pixel-dither.frag", "Ordered pixel dither", """precision mediump float;\nuniform sampler2D uTexture; uniform vec2 uResolution; varying vec2 vUV;\nvoid main(){ vec4 c=texture2D(uTexture,vUV); float d=mod(floor(gl_FragCoord.x)+floor(gl_FragCoord.y),2.0)*0.07; gl_FragColor=vec4(floor(c.rgb*5.0+d)/5.0,c.a); }\n"""),
("outline.frag", "Sprite outline", """precision mediump float;\nuniform sampler2D uTexture; uniform vec2 uTexel; uniform vec4 uOutline; varying vec2 vUV;\nvoid main(){ vec4 c=texture2D(uTexture,vUV); float a=max(max(texture2D(uTexture,vUV+vec2(uTexel.x,0.)).a,texture2D(uTexture,vUV-vec2(uTexel.x,0.)).a),max(texture2D(uTexture,vUV+vec2(0.,uTexel.y)).a,texture2D(uTexture,vUV-vec2(0.,uTexel.y)).a)); gl_FragColor=c.a>0.01?c:(a>0.01?uOutline:vec4(0.)); }\n"""),
("heat.frag", "Heat palette", """precision mediump float; uniform sampler2D uTexture; varying vec2 vUV;\nvoid main(){ vec4 c=texture2D(uTexture,vUV); float l=dot(c.rgb,vec3(.299,.587,.114)); vec3 p=vec3(smoothstep(0.0,.6,l),smoothstep(.25,.85,l),smoothstep(.65,1.0,l)); gl_FragColor=vec4(p,c.a); }\n"""),
("vignette.frag", "Vignette", """precision mediump float; uniform sampler2D uTexture; uniform float uStrength; varying vec2 vUV;\nvoid main(){ vec4 c=texture2D(uTexture,vUV); vec2 q=vUV-.5; float v=1.0-uStrength*dot(q,q)*2.8; gl_FragColor=vec4(c.rgb*clamp(v,0.0,1.0),c.a); }\n"""),
("dissolve.frag", "Threshold dissolve", """precision mediump float; uniform sampler2D uTexture; uniform float uAmount; varying vec2 vUV;\nvoid main(){ vec4 c=texture2D(uTexture,vUV); float n=fract(sin(dot(floor(vUV*128.0),vec2(12.9898,78.233)))*43758.5453); if(n<uAmount) discard; gl_FragColor=c; }\n"""),
("water.frag", "Animated water ripple", """precision mediump float; uniform sampler2D uTexture; uniform float uTime; varying vec2 vUV;\nvoid main(){ vec2 uv=vUV+vec2(sin(vUV.y*28.0+uTime)*.006,cos(vUV.x*22.0-uTime)*.004); vec4 c=texture2D(uTexture,uv); gl_FragColor=vec4(c.rgb*vec3(.82,1.04,1.13),c.a); }\n"""),
("glow.frag", "Soft radial glow", """precision mediump float; uniform sampler2D uTexture; uniform float uGlow; varying vec2 vUV;\nvoid main(){ vec4 c=texture2D(uTexture,vUV); float r=length(vUV-.5); c.rgb+=uGlow*max(0.0,1.0-r*2.0); gl_FragColor=c; }\n"""),
("palette.frag", "Warm palette remap", """precision mediump float; uniform sampler2D uTexture; varying vec2 vUV;\nvoid main(){ vec4 c=texture2D(uTexture,vUV); vec3 warm=vec3(c.r*1.05,c.g*.94,c.b*.78); gl_FragColor=vec4(clamp(warm,0.0,1.0),c.a); }\n"""),
("sepia.frag", "Sepia tone", """precision mediump float; uniform sampler2D uTexture; varying vec2 vUV;\nvoid main(){ vec4 c=texture2D(uTexture,vUV); vec3 s=vec3(dot(c.rgb,vec3(.393,.769,.189)),dot(c.rgb,vec3(.349,.686,.168)),dot(c.rgb,vec3(.272,.534,.131))); gl_FragColor=vec4(s,c.a); }\n"""),
("scanline.frag", "Retro scanlines", """precision mediump float; uniform sampler2D uTexture; uniform float uStrength; varying vec2 vUV;\nvoid main(){ vec4 c=texture2D(uTexture,vUV); float s=sin(vUV.y*720.0*3.14159)*.5+.5; gl_FragColor=vec4(c.rgb*(1.0-uStrength*(1.0-s)),c.a); }\n"""),
]


def generate_character(name, body_hex, skin_hex, accent_hex, seed):
    random.seed(seed)
    c = Canvas(64, 64)
    outline = rgba("#222638")
    body, skin, accent = rgba(body_hex), rgba(skin_hex), rgba(accent_hex)
    light = tuple(min(255, int(v*1.22)) for v in body[:3]) + (255,)
    dark = tuple(max(0, int(v*.62)) for v in body[:3]) + (255,)
    # Soft oval shadow, pixelated deliberately to match the 64px sprite style.
    c.ellipse(32, 58, 17, 4, rgba("#17202F", 105))
    # Legs, boots and arms with dark silhouette edges.
    c.rect(21, 42, 29, 54, outline); c.rect(35, 42, 43, 54, outline)
    c.rect(23, 43, 28, 52, body); c.rect(36, 43, 41, 52, dark)
    c.rect(19, 52, 30, 57, outline); c.rect(34, 52, 45, 57, outline)
    c.rect(21, 53, 28, 55, accent); c.rect(36, 53, 43, 55, accent)
    c.rect(15, 29, 22, 43, outline); c.rect(42, 29, 49, 43, outline)
    c.rect(16, 31, 20, 40, body); c.rect(44, 31, 48, 40, light)
    c.rect(22, 26, 42, 46, outline); c.rect(24, 28, 40, 44, body)
    c.rect(26, 29, 32, 32, light); c.rect(33, 36, 39, 39, dark)
    # Head / hair / helmet.
    c.rect(21, 8, 43, 28, outline); c.rect(23, 10, 41, 26, skin)
    c.rect(22, 7, 42, 12, body); c.rect(20, 10, 24, 16, body); c.rect(40, 10, 44, 16, body)
    c.rect(25, 16, 28, 19, outline); c.rect(36, 16, 39, 19, outline)
    c.rect(29, 22, 35, 23, dark)
    # Palette-specific costume accents keep every silhouette readable.
    c.rect(28, 33, 35, 39, accent)
    c.rect(20, 26, 43, 28, dark)
    if seed % 4 == 0:  # cape
        c.polygon([(20, 27), (15, 30), (18, 48), (23, 42)], dark)
    if seed % 5 == 0:  # antenna / plume
        c.rect(31, 2, 33, 8, accent); c.circle(32, 2, 3, light)
    if seed % 3 == 0:  # belt and buckle
        c.rect(23, 40, 41, 42, outline); c.rect(31, 39, 34, 43, rgba("#F5D35D"))
    return c


def generate_prop(name, seed):
    random.seed(seed)
    c = Canvas(64, 64)
    shadow = rgba("#1B2230", 90)
    c.ellipse(32, 56, 22, 5, shadow)
    colors = ["#A96B3C", "#6B7D93", "#D84D61", "#4D9DCC", "#E5B948", "#65A66F", "#A174C5", "#CF7953"]
    main = rgba(colors[seed % len(colors)])
    light = tuple(min(255, int(v*1.3)) for v in main[:3]) + (255,)
    dark = tuple(max(0, int(v*.55)) for v in main[:3]) + (255,)
    ink = rgba("#283044")
    kind = seed % 10
    if kind in (0, 1):
        c.rect(13, 20, 51, 52, ink); c.rect(16, 23, 48, 49, main)
        c.line(18, 25, 46, 47, dark, 4); c.line(46, 25, 18, 47, light, 3)
        c.rect(25, 17, 39, 22, ink)
    elif kind == 2:
        c.rect(22, 16, 42, 52, ink); c.rect(25, 19, 39, 49, main)
        c.rect(20, 20, 44, 25, light); c.rect(29, 27, 35, 39, rgba("#FFE98C"))
        c.circle(32, 34, 3, rgba("#FFF6C5"))
    elif kind == 3:
        c.circle(32, 32, 18, ink); c.circle(32, 32, 15, main); c.circle(32, 32, 9, light); c.circle(32, 32, 4, rgba("#FFF4B0"))
    elif kind == 4:
        c.polygon([(32, 8), (48, 25), (42, 48), (32, 55), (22, 48), (16, 25)], ink)
        c.polygon([(32, 12), (44, 26), (39, 44), (32, 49), (25, 44), (20, 26)], main)
        c.polygon([(32, 13), (32, 47), (24, 27)], light)
    elif kind == 5:
        c.rect(22, 19, 42, 52, ink); c.rect(25, 22, 39, 49, main)
        c.rect(18, 15, 46, 22, ink); c.rect(20, 16, 44, 19, light)
        c.rect(29, 30, 35, 40, rgba("#FFF1A4"))
    elif kind == 6:
        c.rect(18, 30, 46, 52, ink); c.rect(21, 32, 43, 49, main)
        c.ellipse(32, 24, 14, 14, ink); c.ellipse(32, 24, 11, 11, light)
        c.rect(28, 9, 36, 27, main); c.circle(32, 21, 4, rgba("#FFED94"))
    elif kind == 7:
        c.rect(29, 30, 35, 51, ink); c.rect(30, 31, 34, 50, dark)
        c.circle(32, 23, 17, ink); c.circle(32, 22, 14, main); c.circle(27, 18, 5, light)
    elif kind == 8:
        c.circle(32, 34, 16, ink); c.circle(32, 34, 12, main); c.rect(28, 10, 36, 25, ink); c.rect(30, 12, 34, 24, light)
        c.line(23, 34, 41, 34, rgba("#FFF0AB"), 3)
    else:
        c.rect(18, 16, 46, 52, ink); c.rect(21, 19, 43, 49, main)
        c.rect(16, 13, 48, 20, light); c.rect(27, 27, 37, 42, dark)
        c.circle(32, 32, 4, rgba("#FFE77C"))
    # shared little specular glint
    c.rect(12 + seed % 4, 12, 15 + seed % 4, 15, rgba("#FFFFFF", 160))
    return c


def generate_background(name, seed):
    random.seed(seed)
    w, h = 320, 180
    palettes = [
        ("#F6A66D", "#435D92", "#28425B"), ("#FFB56B", "#C46572", "#344565"),
        ("#27385B", "#111B3D", "#10172F"), ("#10285F", "#1C3C85", "#07122D"),
        ("#402D73", "#C35B9D", "#1D234E"), ("#2A8AB0", "#164D7E", "#0C2948"),
        ("#5AC8C1", "#207596", "#145168"), ("#7DC6A0", "#4A8F7D", "#24565C"),
        ("#F2A15B", "#A94D4E", "#543D4F"), ("#F4D386", "#D59255", "#8F5A45"),
        ("#C8EAF2", "#6D9CC2", "#3B5278"), ("#FF9C55", "#8E3D49", "#332E47"),
        ("#442F77", "#256D8D", "#101E3F"), ("#67C8E8", "#9A86E3", "#526FBD"),
        ("#B8A6D9", "#7675A9", "#48516A"), ("#5C738B", "#35485F", "#182938"),
        ("#8DD4AA", "#5AAB91", "#326E6D"), ("#8395B9", "#515E83", "#283656"),
        ("#163471", "#101C48", "#080F2D"), ("#18233C", "#202D4D", "#2A385A"),
    ]
    top, horizon, bottom = [rgba(x) for x in palettes[seed % len(palettes)]]
    c = Canvas(w, h)
    # Vertical gradient, split at a generated horizon.
    horizon_y = 90 + (seed * 13 % 32)
    for y in range(h):
        if y <= horizon_y:
            t = y / max(1, horizon_y)
            a, b = top, horizon
        else:
            t = (y-horizon_y) / max(1, h-horizon_y)
            a, b = horizon, bottom
        col = tuple(int(a[i]*(1-t)+b[i]*t) for i in range(3)) + (255,)
        c.rect(0, y, w-1, y, col)
    # Moon / sun and two layered, deterministic mountain silhouettes.
    c.circle(238 + seed*7 % 58, 42 + seed*3 % 24, 13 + seed % 9, rgba("#FFF0B1", 225))
    for layer, color in enumerate((rgba("#314766", 225), rgba("#1D334E", 245))):
        base = 116 + layer*18
        pts = [(0, h)]
        x = -10
        while x < w+20:
            peak = base - random.randrange(10, 50)
            pts.extend([(x, base), (x+random.randrange(10, 35), peak), (x+random.randrange(30, 60), base)])
            x += random.randrange(32, 62)
        pts.extend([(w, h)])
        c.polygon(pts, color)
    for _ in range(80):
        x, y = random.randrange(w), random.randrange(5, 118)
        if seed % 4 in (0, 2):
            c.pixel(x, y, rgba("#FFF4D0", random.randrange(80, 245)))
    # Subtle foreground streaks give a sense of depth without hiding game sprites.
    for i in range(12):
        x = random.randrange(w); y = random.randrange(130, h)
        c.line(x, y, x+random.randrange(8, 42), y, rgba("#C0D5E8", 35), 1)
    return c


def write_wav(path: Path, seed: int):
    random.seed(seed)
    rate = 22050
    duration = 0.12 + (seed % 5) * 0.07
    n = int(rate * duration)
    base = [440, 520, 660, 330, 180, 880, 740, 520, 610, 290, 210, 110, 360, 500, 190, 660][seed % 16]
    kind = seed % 5
    samples = []
    noise = 0.0
    for i in range(n):
        t = i / rate
        p = i / max(1, n-1)
        env = (1-p) ** (1.4 if kind in (0, 1, 2) else 0.65)
        if kind == 0:
            freq = base * (1.8 - p * 0.65)
            value = math.sin(2*math.pi*freq*t) * (0.75 + 0.25*math.sin(2*math.pi*8*t))
        elif kind == 1:
            value = math.sin(2*math.pi*base*t) + 0.38*math.sin(2*math.pi*base*1.5*t)
        elif kind == 2:
            noise = noise*0.76 + (random.random()*2-1)*0.24
            value = noise
        elif kind == 3:
            value = math.sin(2*math.pi*(base + 120*math.sin(t*12))*t)
        else:
            value = math.sin(2*math.pi*base*t) * math.sin(2*math.pi*5*t)
        sample = int(max(-1, min(1, value * env * 0.55)) * 32767)
        samples.append(struct.pack("<h", sample))
    path.parent.mkdir(parents=True, exist_ok=True)
    with wave.open(str(path), "wb") as out:
        out.setnchannels(1); out.setsampwidth(2); out.setframerate(rate)
        out.writeframes(b"".join(samples))


entries = []
def add(category, title, rel, description):
    entries.append({"id": f"asset-{len(entries)+1:03}", "name": title, "category": category,
                    "path": rel, "filename": Path(rel).name, "description": description,
                    "license": "CC0-1.0", "source": "Original procedural S Engine starter content"})

for i, (name, body, skin, accent) in enumerate(CHARACTERS):
    rel = f"characters/{i+1:02}_{name.lower().replace(' ', '_')}.png"
    generate_character(name, body, skin, accent, i).save(ROOT / rel)
    add("Characters", name, rel, "64×64 original pixel-art character sprite with transparent background.")
for i, name in enumerate(PROPS):
    rel = f"objects/{i+1:02}_{name.lower().replace(' ', '_')}.png"
    generate_prop(name, i + 20).save(ROOT / rel)
    add("Objects", name, rel, "64×64 original pixel-art gameplay prop or pickup.")
for i, name in enumerate(BACKGROUNDS):
    rel = f"backgrounds/{i+1:02}_{name.lower().replace(' ', '_')}.png"
    generate_background(name, i).save(ROOT / rel)
    add("Backgrounds", name, rel, "320×180 original parallax-ready 2D landscape background.")
for i, name in enumerate(SOUNDS):
    rel = f"sounds/{i+1:02}_{name.lower().replace(' ', '_')}.wav"
    write_wav(ROOT / rel, i)
    add("Sounds", name, rel, "Original synthesized short mono game sound effect (22.05 kHz PCM WAV).")
for name, description, source in SCRIPTS:
    rel = f"scripts/{name}"
    path = ROOT / rel; path.parent.mkdir(parents=True, exist_ok=True); path.write_text(source, encoding="utf-8")
    add("Behaviors", name.removesuffix(".js"), rel, description + ". JavaScript behavior source; attach to a Script component.")
for name, description, source in SHADERS:
    rel = f"shaders/{name}"
    path = ROOT / rel; path.parent.mkdir(parents=True, exist_ok=True); path.write_text("// " + description + " — GLES 2.0 GLSL ES 1.00 starter source.\n" + source, encoding="utf-8")
    add("Shader Sources", name.removesuffix(".frag"), rel, description + ". GLSL ES 1.00 fragment shader example for future/custom render pipelines.")

assert len(entries) == 100, f"expected exactly 100 catalog entries, generated {len(entries)}"
manifest = {"name": "S Engine Original 2D Starter Library", "version": 1, "entryCount": len(entries),
            "license": "CC0-1.0", "entries": entries}
(ROOT / "library.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
(ROOT / "README.txt").write_text(
    "S Engine Original 2D Starter Library\n"
    "100 original offline assets: 20 characters, 20 objects, 20 backgrounds,\n"
    "16 synthesized WAV effects, 12 JavaScript behaviors, and 12 GLSL sources.\n"
    "All generated artwork, audio and examples are dedicated to CC0-1.0.\n"
    "Shaders are source examples; the current runtime does not load custom materials.\n",
    encoding="utf-8")
print(f"Generated {len(entries)} assets in {ROOT}")
