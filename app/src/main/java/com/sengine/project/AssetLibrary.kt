package com.sengine.project

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import com.sengine.engine.anim.AnimationClip
import com.sengine.engine.blueprint.Blueprint
import java.io.ByteArrayOutputStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** Built-in, procedurally generated asset store content (textures, sheets, sounds, shaders, scripts, blueprints, models). */
object AssetLibrary {

    class Item(
        val title: String,
        val category: String,
        val description: String,
        val files: List<String>,
        val glyph: String = "",
        val preview: (() -> Bitmap)? = null,
        val sound: (() -> ByteArray)? = null,
        val install: (Project) -> Unit,
    ) {
        fun installed(p: Project) = files.all { p.assetFile(it).exists() }
    }

    val categories = listOf("All", "Packs", "Textures", "Sprites", "Sprite Sheets", "Sounds", "Shaders", "Scripts", "Blueprints", "3D Models")

    private fun png(p: Project, name: String, b: Bitmap) {
        p.assetsDir.mkdirs()
        p.assetFile(name).outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun tex(title: String, file: String, desc: String, cat: String = "Textures", gen: () -> Bitmap) =
        Item(title, cat, desc, listOf(file), preview = gen) { p -> png(p, file, gen()) }

    private fun text(title: String, cat: String, file: String, desc: String, glyph: String, content: () -> String) =
        Item(title, cat, desc, listOf(file), glyph) { p -> p.writeAsset(file, content()) }

    private fun sheet(title: String, file: String, anim: String, desc: String, cols: Int, fps: Float, loop: Boolean, gen: () -> Bitmap) =
        Item(title, "Sprite Sheets", desc, listOf(file, anim), preview = gen) { p ->
            p.writeAsset(anim, AnimationClip(file, cols, 1, (0 until cols).toMutableList(), fps, loop).toJson().toString(2))
            png(p, file, gen())
        }

    private fun sfx(title: String, file: String, desc: String, gen: () -> ByteArray) =
        Item(title, "Sounds", desc, listOf(file), "♪", sound = gen) { p -> p.assetsDir.mkdirs(); p.assetFile(file).writeBytes(gen()) }

    val items: List<Item> by lazy { buildItems() }

    private fun buildItems(): List<Item> {
        val list = ArrayList<Item>()
        // ---------------------------------------------------------------- textures
        list += tex("Brick Wall", "Brick.png", "Tileable red bricks, 128×128") { brick() }
        list += tex("Grass Tile", "Grass.png", "Grass top with dirt, 64×64") { grassTile() }
        list += tex("Dirt", "Dirt.png", "Tileable soil, 64×64") { noiseTex(64, 0xFF7A5230.toInt(), 0xFF5C3B20.toInt(), 3) }
        list += tex("Stone", "Stone.png", "Tileable cobblestone, 128×128") { stone() }
        list += tex("Wood Planks", "Wood.png", "Horizontal planks, 128×128") { planks() }
        list += tex("Wooden Crate", "Crate.png", "Classic crate, 64×64") { crate() }
        list += tex("Metal Plate", "Metal.png", "Riveted steel, 128×128") { metal() }
        list += tex("Checker", "Checker.png", "Prototype checker grid, 128×128") { checker() }
        list += tex("Sand", "Sand.png", "Tileable sand, 64×64") { noiseTex(64, 0xFFE3C88A.toInt(), 0xFFC9A765.toInt(), 1) }
        list += tex("Water", "Water.png", "Tileable water, 128×128") { water() }
        list += tex("Lava", "Lava.png", "Glowing lava, 128×128") { lava() }
        list += tex("Ice", "Ice.png", "Frozen surface, 64×64") { noiseTex(64, 0xFFBEE7F5.toInt(), 0xFF8CCBE6.toInt(), 2) }
        list += tex("Sky Gradient", "Sky.png", "Background gradient, 256×256") { sky(0xFF2E6FD8.toInt(), 0xFFBFE3FF.toInt(), false) }
        list += tex("Night Sky", "NightSky.png", "Stars background, 256×256") { sky(0xFF050A1E.toInt(), 0xFF1D2B5A.toInt(), true) }
        list += tex("Mountains", "Mountains.png", "Parallax mountains, 256×128") { mountains() }
        // ---------------------------------------------------------------- sprites (pixel art)
        list += tex("Hero", "Hero.png", "16×16 pixel hero", "Sprites") { pixel(HERO_IDLE, PAL) }
        list += tex("Slime", "Slime.png", "16×16 slime enemy", "Sprites") { pixel(SLIME[0], PAL) }
        list += tex("Coin", "Coin.png", "16×16 gold coin", "Sprites") { pixel(COIN, PAL) }
        list += tex("Heart", "Heart.png", "16×16 heart", "Sprites") { pixel(HEART, PAL) }
        list += tex("Gem", "Gem.png", "16×16 gem", "Sprites") { pixel(GEM, PAL) }
        list += tex("Key", "Key.png", "16×16 key", "Sprites") { pixel(KEY, PAL) }
        list += tex("Spaceship", "Spaceship.png", "16×16 player ship", "Sprites") { pixel(SHIP, PAL) }
        list += tex("Asteroid", "Asteroid.png", "Rocky asteroid, 64×64", "Sprites") { asteroid() }
        list += tex("Laser Bolt", "Laser.png", "Glowing projectile, 16×64", "Sprites") { laser() }
        list += tex("Tree", "Tree2D.png", "Pixel tree, 32×32", "Sprites") { tree2d() }
        list += tex("Cloud", "Cloud.png", "Soft cloud, 128×64", "Sprites") { cloud() }
        list += tex("Soft Particle", "SoftDot.png", "Radial glow for particles, 64×64", "Sprites") { softDot(Color.WHITE) }
        list += tex("Spark", "Spark.png", "Star spark particle, 64×64", "Sprites") { spark() }
        list += tex("UI Button", "Button.png", "Rounded UI panel, 128×48", "Sprites") { uiButton() }
        // ---------------------------------------------------------------- sheets (+ .anim)
        list += sheet("Hero Run (4 frames)", "HeroRun.png", "HeroRun.anim", "Run cycle + animation clip", 4, 10f, true) { strip(HERO_RUN) }
        list += sheet("Coin Spin (6 frames)", "CoinSpin.png", "CoinSpin.anim", "Spinning coin + clip", 6, 12f, true) { coinSpin() }
        list += sheet("Slime Bounce (4 frames)", "SlimeBounce.png", "SlimeBounce.anim", "Squash & stretch + clip", 4, 8f, true) { strip(SLIME) }
        list += sheet("Explosion (8 frames)", "Explosion.png", "Explosion.anim", "One-shot blast + clip", 8, 16f, false) { explosion() }
        list += sheet("Fire (6 frames)", "Fire.png", "Fire.anim", "Looping flame + clip", 6, 12f, true) { fire() }
        // ---------------------------------------------------------------- sounds
        list += sfx("Coin Pickup", "coin.wav", "Bright two-tone chime") { Sfx.coin() }
        list += sfx("Jump", "jump.wav", "Rising sweep") { Sfx.jump() }
        list += sfx("Laser", "laser.wav", "Pew! falling sweep") { Sfx.laser() }
        list += sfx("Explosion", "explosion.wav", "Noise burst with decay") { Sfx.explosion() }
        list += sfx("Hit", "hit.wav", "Short impact") { Sfx.hit() }
        list += sfx("Power Up", "powerup.wav", "Rising arpeggio") { Sfx.powerup() }
        list += sfx("UI Click", "click.wav", "Tiny blip") { Sfx.click() }
        list += sfx("Win Jingle", "win.wav", "Victory fanfare") { Sfx.win() }
        list += sfx("Game Over", "lose.wav", "Sad descending tones") { Sfx.lose() }
        list += sfx("Chiptune Loop", "music_loop.wav", "8-second retro music loop") { Sfx.music() }
        // ---------------------------------------------------------------- shaders
        for ((name, desc, code) in Shaders.all) list += text(name.removeSuffix(".glsl"), "Shaders", name, desc, "GLSL") { code }
        // ---------------------------------------------------------------- scripts
        for ((name, desc, code) in Scripts.all) list += text(name.removeSuffix(".js"), "Scripts", name, desc, "JS") { code }
        // ---------------------------------------------------------------- blueprints
        list += text("Rotator (Blueprint)", "Blueprints", "RotatorBP.bp", "Spins the object every frame", "BP") { bpRotator() }
        list += text("Collectible (Blueprint)", "Blueprints", "CollectibleBP.bp", "Adds score, plays a sound and disappears on trigger", "BP") { bpCollectible() }
        list += text("Platformer Player (Blueprint)", "Blueprints", "PlatformerBP.bp", "Run & jump controller with animation", "BP") { bpPlatformer() }
        list += text("Timed Spawner (Blueprint)", "Blueprints", "SpawnerBP.bp", "Spawns a template every 2 seconds", "BP") { bpSpawner() }
        // ---------------------------------------------------------------- models
        list += text("Low-Poly Tree", "3D Models", "Tree.obj", "Trunk + foliage, OBJ", "3D") { Models.tree() }
        list += text("Rock", "3D Models", "Rock.obj", "Irregular boulder, OBJ", "3D") { Models.rock() }
        list += text("House", "3D Models", "House.obj", "Box house with roof, OBJ", "3D") { Models.house() }
        list += text("Crystal", "3D Models", "Crystal.obj", "Faceted gem, OBJ", "3D") { Models.crystal() }
        list += text("Barrel", "3D Models", "Barrel.obj", "Bulged barrel, OBJ", "3D") { Models.barrel() }
        list += text("Arrow", "3D Models", "Arrow.obj", "Direction arrow, OBJ", "3D") { Models.arrow() }
        // ---------------------------------------------------------------- packs
        fun pack(title: String, desc: String, names: List<String>) {
            val parts = names.mapNotNull { n -> list.firstOrNull { it.title == n } }
            list.add(0, Item(title, "Packs", desc, parts.flatMap { it.files }, "📦", preview = parts.firstOrNull { it.preview != null }?.preview) { p -> parts.forEach { it.install(p) } })
        }
        pack("2D Platformer Pack", "Hero + run animation, coins, slime, tiles, sounds and scripts",
            listOf("Hero", "Hero Run (4 frames)", "Coin Spin (6 frames)", "Slime Bounce (4 frames)", "Grass Tile", "Brick Wall", "Sky Gradient", "Mountains", "Coin Pickup", "Jump", "Hit", "PlayerPlatformer", "EnemyPatrol", "Collectible"))
        pack("Space Shooter Pack", "Ship, asteroid, laser, explosion, night sky and sounds",
            listOf("Spaceship", "Asteroid", "Laser Bolt", "Explosion (8 frames)", "Night Sky", "Laser", "Explosion", "Bullet", "Spawner"))
        pack("3D Starter Pack", "Models, tileable textures, a 3D controller and a toon shader",
            listOf("Low-Poly Tree", "Rock", "House", "Crystal", "Checker", "Grass Tile", "Stone", "Player3D", "Rotator", "Toon"))
        pack("VFX Pack", "Particles, fire, shaders and power-up sound",
            listOf("Soft Particle", "Spark", "Fire (6 frames)", "Dissolve", "Hit Flash", "Rainbow", "Hologram", "Power Up"))
        return list
    }

    // ==================================================================== blueprints

    private fun bpRotator(): String {
        val bp = Blueprint()
        val u = bp.add("OnUpdate", 40f, 40f); val r = bp.add("Rotate", 300f, 40f)
        bp.connect(u.id, "out", r.id)
        return bp.toJson().toString(2)
    }

    private fun bpCollectible(): String {
        val bp = Blueprint()
        val t = bp.add("OnTrigger", 40f, 40f)
        val cond = bp.add("If", 300f, 40f); cond.params["condition"] = "other.tag == \"Player\""
        val add = bp.add("AddVar", 560f, 20f)
        val snd = bp.add("PlaySound", 820f, 20f)
        val kill = bp.add("DestroySelf", 1080f, 20f)
        bp.connect(t.id, "out", cond.id); bp.connect(cond.id, "true", add.id); bp.connect(add.id, "out", snd.id); bp.connect(snd.id, "out", kill.id)
        return bp.toJson().toString(2)
    }

    private fun bpPlatformer(): String {
        val bp = Blueprint()
        val u = bp.add("OnUpdate", 40f, 40f)
        val ctl = bp.add("Platformer", 300f, 40f)
        val cond = bp.add("If", 560f, 40f); cond.params["condition"] = "Math.abs(self.vx) > 0.1"
        val run = bp.add("PlayAnimation", 820f, 0f); run.params["clip"] = "HeroRun.anim"
        val idle = bp.add("Code", 820f, 140f); idle.params["code"] = "self.stopAnimation()"
        bp.connect(u.id, "out", ctl.id); bp.connect(ctl.id, "out", cond.id); bp.connect(cond.id, "true", run.id); bp.connect(cond.id, "false", idle.id)
        val a = bp.add("OnButtonA", 40f, 260f); val snd = bp.add("PlaySound", 300f, 260f); snd.params["file"] = "jump.wav"
        bp.connect(a.id, "out", snd.id)
        return bp.toJson().toString(2)
    }

    private fun bpSpawner(): String {
        val bp = Blueprint()
        val t = bp.add("OnTimer", 40f, 40f); t.params["seconds"] = "2"
        val s = bp.add("Spawn", 300f, 40f); s.params["template"] = "Enemy"; s.params["x"] = "random(-6, 6)"; s.params["y"] = "self.worldY"
        bp.connect(t.id, "out", s.id)
        return bp.toJson().toString(2)
    }

    // ==================================================================== pixel art

    private val PAL = mapOf(
        'k' to 0xFF1A1C2C.toInt(), 'w' to 0xFFF4F4F4.toInt(), 's' to 0xFFFFCD9E.toInt(), 'b' to 0xFF3B5DC9.toInt(),
        'B' to 0xFF29366F.toInt(), 'r' to 0xFFB13E53.toInt(), 'R' to 0xFFEF7D57.toInt(), 'y' to 0xFFFFCD75.toInt(),
        'Y' to 0xFFE0A030.toInt(), 'g' to 0xFF38B764.toInt(), 'G' to 0xFF257179.toInt(), 'l' to 0xFFA7F070.toInt(),
        'c' to 0xFF41A6F6.toInt(), 'C' to 0xFF73EFF7.toInt(), 'n' to 0xFF5D275D.toInt(), 'h' to 0xFF566C86.toInt(),
        'H' to 0xFF94B0C2.toInt(), 'o' to 0xFF6F4E37.toInt(), 'p' to 0xFFE040FB.toInt(),
    )

    private val HERO_IDLE = listOf(
        "................", ".....kkkkk......", "....kRRRRRk.....", "...kRRRRRRRk....", "...kkkkkkkkk....",
        "...ksskskssk....", "...kssssssk.....", "....kssssk......", "...kbbbbbbk.....", "..ksbbbbbbsk....",
        "..ksbbbbbbsk....", "...kbbbbbbk.....", "...kBBkkBBk.....", "...kBBk.kBBk....", "...kook..kook...", "................")
    private val HERO_RUN = listOf(
        HERO_IDLE.take(12) + listOf("...kBBkkBBk.....", "..kBBk...kBk....", ".kook.....kok...", "................"),
        HERO_IDLE.take(12) + listOf("....kBBBBk......", "....kBkBBk......", "....kookok......", "................"),
        HERO_IDLE.take(12) + listOf("...kBBkkBBk.....", "....kBk.kBBk....", "...kok...kook...", "................"),
        HERO_IDLE.take(12) + listOf("....kBBBBk......", "....kBBkBk......", "....kokook......", "................"),
    )
    private val SLIME = listOf(
        listOf("................", "................", "................", "................", "................", "......kkkk......",
            "....kkggggkk....", "...kgglggggk....", "..kgglgggggkk...", "..kggkggkgggk...", "..kggkggkgggk...", ".kgggggggggggk..",
            ".kgggggggggggk..", ".kGGGGGGGGGGGk..", "..kkkkkkkkkkk...", "................"),
        listOf("................", "................", "................", "................", "................", "................",
            "......kkkk......", "...kkkggggkkk...", "..kgglgggggggk..", ".kgglggkggkgggk.", ".kggggkggkggggk.", "kggggggggggggggk",
            "kggggggggggggggk", "kGGGGGGGGGGGGGGk", ".kkkkkkkkkkkkkk.", "................"),
        listOf("................", "................", "................", "......kkkk......", ".....kggggk.....", "....kgglgggk....",
            "....kglggggk....", "...kggkggkggk...", "...kggkggkggk...", "...kggggggggk...", "...kggggggggk...", "...kggggggggk...",
            "...kGGGGGGGGk...", "....kkkkkkkk....", "................", "................"),
        listOf("................", "................", "................", "................", ".......kk.......", ".....kkggkk.....",
            "....kgglgggk....", "...kgglgggggk...", "...kggkggkggk...", "..kgggkggkgggk..", "..kgggggggggggk.", "..kgggggggggggk.",
            "..kGGGGGGGGGGGk.", "...kkkkkkkkkkk..", "................", "................"),
    )
    private val COIN = listOf(
        "................", ".....kkkkkk.....", "....kyyyyyyk....", "...kyyYYYYyyk...", "..kyyYyyyyYyyk..", "..kyYyywyyyYyk..",
        "..kyYywyyyyYyk..", "..kyYyyyyyyYyk..", "..kyYyyyyyyYyk..", "..kyYyyyyyyYyk..", "..kyYyyyyyyYyk..", "..kyyYyyyyYyyk..",
        "...kyyYYYYyyk...", "....kyyyyyyk....", ".....kkkkkk.....", "................")
    private val HEART = listOf(
        "................", "................", "...kkk....kkk...", "..kRRRk..kRRRk..", ".kRwRRRkkRRRRRk.", ".kRwRRRRRRRRRRk.",
        ".kRRRRRRRRRRRRk.", ".krRRRRRRRRRRrk.", "..krRRRRRRRRrk..", "...krRRRRRRrk...", "....krRRRRrk....", ".....krRRrk.....",
        "......krrk......", ".......kk.......", "................", "................")
    private val GEM = listOf(
        "................", "................", "....kkkkkkkk....", "...kCCcCCcCCk...", "..kCwCccCccCCk..", ".kCCCcccccCCCCk.",
        ".kkkkkkkkkkkkkk.", ".kcCcccccccccck.", "..kcCccccccccK..", "...kcCcccccck...", "....kcccccck....", ".....kcccck.....",
        "......kcck......", ".......kk.......", "................", "................").map { it.replace('K', 'k') }
    private val KEY = listOf(
        "................", "................", "...kkkk.........", "..kyyyyk........", ".kyykkyyk.......", ".kyk..kyk.......",
        ".kyk..kykkkkkkk.", ".kyykkyyyyyyyyyk", "..kyyyykkkykkyk.", "...kkkk..kyk.kk.", ".........kk.....", "................",
        "................", "................", "................", "................")
    private val SHIP = listOf(
        ".......kk.......", "......kHHk......", "......kHHk......", ".....kHccHk.....", ".....kHCcHk.....", "....kHHccHHk....",
        "....kHHHHHHk....", "...kHHhHHhHHk...", "..kHHhhHHhhHHk..", ".kHHhhhHHhhhHHk.", "kHHhhkkHHkkhhHHk", "kHhhk.kHHk.khhHk",
        "kkkk..kRRk..kkkk", "......kyyk......", ".......kk.......", "................")

    private fun pixel(rows: List<String>, pal: Map<Char, Int>): Bitmap {
        val h = rows.size; val w = rows.maxOf { it.length }
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        for (y in 0 until h) for (x in 0 until w) {
            val ch = rows[y].getOrElse(x) { '.' }
            b.setPixel(x, y, pal[ch] ?: Color.TRANSPARENT)
        }
        return b
    }

    private fun strip(frames: List<List<String>>): Bitmap {
        val fw = frames[0][0].length; val fh = frames[0].size
        val out = Bitmap.createBitmap(fw * frames.size, fh, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        frames.forEachIndexed { i, f -> c.drawBitmap(pixel(f, PAL), (i * fw).toFloat(), 0f, null) }
        return out
    }

    private fun coinSpin(): Bitmap {
        val n = 6; val s = 16
        val out = Bitmap.createBitmap(s * n, s, Bitmap.Config.ARGB_8888)
        val coin = pixel(COIN, PAL)
        for (i in 0 until n) {
            val scale = abs(cos(i * PI / n)).toFloat().coerceAtLeast(0.15f)
            for (y in 0 until s) for (x in 0 until s) {
                val sx = ((x - s / 2f + 0.5f) / scale + s / 2f).toInt()
                if (sx in 0 until s) out.setPixel(i * s + x, y, coin.getPixel(sx, y))
            }
        }
        return out
    }

    // ==================================================================== procedural textures

    private fun rng(seed: Int) = Random(seed)

    private fun noiseTex(size: Int, a: Int, b: Int, seed: Int): Bitmap {
        val r = rng(seed)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        for (y in 0 until size) for (x in 0 until size) bmp.setPixel(x, y, lerp(a, b, r.nextFloat()))
        return bmp
    }

    fun lerp(a: Int, b: Int, t: Float): Int {
        fun ch(s: Int) = ((((a shr s) and 0xFF) * (1 - t)) + (((b shr s) and 0xFF) * t)).toInt().coerceIn(0, 255)
        return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    private fun shade(c: Int, f: Float): Int {
        fun ch(s: Int) = (((c shr s) and 0xFF) * f).toInt().coerceIn(0, 255)
        return (c and 0xFF000000.toInt()) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    private fun brick(): Bitmap {
        val s = 128; val b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val r = rng(7); val c = Canvas(b)
        c.drawColor(0xFFBDB3A4.toInt())
        val p = Paint()
        val bh = 16; val bw = 32
        for (row in 0 until s / bh) {
            val off = if (row % 2 == 0) 0 else bw / 2
            var x = -off
            while (x < s) {
                p.color = shade(0xFFA8432F.toInt(), 0.8f + r.nextFloat() * 0.35f)
                c.drawRect((x + 1).toFloat(), (row * bh + 1).toFloat(), (x + bw - 1).toFloat(), (row * bh + bh - 1).toFloat(), p)
                if (x + bw > s) { c.drawRect((x - s + 1).toFloat(), (row * bh + 1).toFloat(), (x - s + bw - 1).toFloat(), (row * bh + bh - 1).toFloat(), p) }
                x += bw
            }
        }
        grain(b, 0.08f, 3)
        return b
    }

    private fun grain(b: Bitmap, amount: Float, seed: Int) {
        val r = rng(seed)
        for (y in 0 until b.height) for (x in 0 until b.width) {
            val px = b.getPixel(x, y)
            if (Color.alpha(px) == 0) continue
            b.setPixel(x, y, shade(px, 1f + (r.nextFloat() - 0.5f) * 2 * amount))
        }
    }

    private fun grassTile(): Bitmap {
        val s = 64; val b = noiseTex(s, 0xFF7A5230.toInt(), 0xFF5C3B20.toInt(), 5)
        val r = rng(9)
        for (x in 0 until s) {
            val h = 14 + (sin(x * 0.7) * 2 + r.nextInt(3)).toInt()
            for (y in 0 until h) b.setPixel(x, y, lerp(0xFF4CAF50.toInt(), 0xFF2E7D32.toInt(), r.nextFloat() * 0.6f + y / h.toFloat() * 0.4f))
            b.setPixel(x, h, 0xFF2E5E1E.toInt())
        }
        return b
    }

    private fun stone(): Bitmap {
        val s = 128; val b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val r = rng(11)
        val pts = List(24) { floatArrayOf(r.nextFloat() * s, r.nextFloat() * s, 0.75f + r.nextFloat() * 0.35f) }
        for (y in 0 until s) for (x in 0 until s) {
            var d1 = Float.MAX_VALUE; var d2 = Float.MAX_VALUE; var shadeF = 1f
            for (p in pts) for (ox in -1..1) for (oy in -1..1) {
                val dx = x - (p[0] + ox * s); val dy = y - (p[1] + oy * s)
                val d = sqrt(dx * dx + dy * dy)
                if (d < d1) { d2 = d1; d1 = d; shadeF = p[2] } else if (d < d2) d2 = d
            }
            val edge = ((d2 - d1) / 4f).coerceIn(0f, 1f)
            b.setPixel(x, y, shade(0xFF8C8C8C.toInt(), shadeF * (0.45f + 0.55f * edge)))
        }
        grain(b, 0.06f, 12)
        return b
    }

    private fun planks(): Bitmap {
        val s = 128; val b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val r = rng(13)
        val ph = 16
        for (y in 0 until s) {
            val plank = y / ph
            val base = shade(0xFFA0703C.toInt(), 0.85f + (plank * 37 % 7) / 30f)
            for (x in 0 until s) {
                val grainV = sin((x * 0.15 + sin(y * 0.4 + plank) * 2 + plank * 3)).toFloat() * 0.08f
                var c = shade(base, 1f + grainV + (r.nextFloat() - 0.5f) * 0.06f)
                if (y % ph == 0) c = shade(base, 0.45f)
                if ((x + plank * 45) % 128 == 0) c = shade(base, 0.5f)
                b.setPixel(x, y, c)
            }
        }
        return b
    }

    private fun crate(): Bitmap {
        val s = 64; val b = planks().let { Bitmap.createScaledBitmap(it, s, s, false) }
        val c = Canvas(b); val p = Paint().apply { color = 0xFF5D3A17.toInt(); strokeWidth = 6f; style = Paint.Style.STROKE }
        c.drawRect(3f, 3f, s - 3f, s - 3f, p)
        p.strokeWidth = 7f; c.drawLine(6f, 6f, s - 6f, s - 6f, p)
        return b
    }

    private fun metal(): Bitmap {
        val s = 128; val b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val r = rng(17)
        for (y in 0 until s) for (x in 0 until s) {
            val streak = sin(y * 0.9 + r.nextFloat()).toFloat() * 0.03f
            b.setPixel(x, y, shade(0xFF9EA7B0.toInt(), 0.9f + streak + r.nextFloat() * 0.06f))
        }
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = 0xFF5F6770.toInt(); p.style = Paint.Style.STROKE; p.strokeWidth = 3f
        c.drawRect(1.5f, 1.5f, s - 1.5f, s - 1.5f, p)
        p.style = Paint.Style.FILL
        for ((x, y) in listOf(10f to 10f, s - 10f to 10f, 10f to s - 10f, s - 10f to s - 10f)) {
            p.color = 0xFF6C747D.toInt(); c.drawCircle(x, y, 5f, p)
            p.color = 0xFFD6DCE2.toInt(); c.drawCircle(x - 1.5f, y - 1.5f, 2f, p)
        }
        return b
    }

    private fun checker(): Bitmap {
        val s = 128; val b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        for (y in 0 until s) for (x in 0 until s) {
            val on = ((x / 16) + (y / 16)) % 2 == 0
            var c = if (on) 0xFFE0E0E0.toInt() else 0xFF9E9E9E.toInt()
            if (x % 64 == 0 || y % 64 == 0) c = 0xFFFF9800.toInt()
            b.setPixel(x, y, c)
        }
        return b
    }

    private fun water(): Bitmap {
        val s = 128; val b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        for (y in 0 until s) for (x in 0 until s) {
            val u = x * 2 * PI / s; val v = y * 2 * PI / s
            val w = (sin(u * 2 + sin(v * 3) * 1.5) + sin(v * 2 + cos(u * 3))) * 0.25 + 0.5
            b.setPixel(x, y, lerp(0xFF1565C0.toInt(), 0xFF4FC3F7.toInt(), w.toFloat()))
        }
        return b
    }

    private fun lava(): Bitmap {
        val s = 128; val b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        for (y in 0 until s) for (x in 0 until s) {
            val u = x * 2 * PI / s; val v = y * 2 * PI / s
            val w = (sin(u * 3 + sin(v * 2) * 2) * sin(v * 3 + cos(u * 2) * 2) * 0.5 + 0.5).toFloat()
            val c = if (w > 0.55f) lerp(0xFFFF6F00.toInt(), 0xFFFFEB3B.toInt(), (w - 0.55f) / 0.45f) else lerp(0xFF3E0A00.toInt(), 0xFFD84315.toInt(), w / 0.55f)
            b.setPixel(x, y, c)
        }
        return b
    }

    private fun sky(top: Int, bottom: Int, stars: Boolean): Bitmap {
        val s = 256; val b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawPaint(Paint().apply { shader = LinearGradient(0f, 0f, 0f, s.toFloat(), top, bottom, Shader.TileMode.CLAMP) })
        if (stars) {
            val r = rng(21); val p = Paint(Paint.ANTI_ALIAS_FLAG)
            repeat(120) {
                p.color = lerp(0x66FFFFFF, 0xFFFFFFFF.toInt(), r.nextFloat())
                c.drawCircle(r.nextFloat() * s, r.nextFloat() * s, 0.4f + r.nextFloat() * 1.1f, p)
            }
        }
        return b
    }

    private fun mountains(): Bitmap {
        val w = 256; val h = 128; val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b); val p = Paint(Paint.ANTI_ALIAS_FLAG)
        for ((layer, color) in listOf(0 to 0xFF7E9CC0.toInt(), 1 to 0xFF4E6E94.toInt(), 2 to 0xFF2F4A6B.toInt())) {
            val path = Path(); path.moveTo(0f, h.toFloat())
            for (x in 0..w step 4) {
                val t = x * 2 * PI / w
                val y = h * (0.35f + layer * 0.15f) + (sin(t * (2 + layer) + layer) * 14 + sin(t * (5 + layer * 2)) * 6).toFloat()
                path.lineTo(x.toFloat(), y)
            }
            path.lineTo(w.toFloat(), h.toFloat()); path.close()
            p.color = color; c.drawPath(path, p)
        }
        return b
    }

    private fun asteroid(): Bitmap {
        val s = 64; val b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(b); val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val r = rng(31)
        val path = Path()
        for (i in 0 until 14) {
            val a = i * 2 * PI / 14; val rad = 22 + r.nextFloat() * 8
            val x = (32 + cos(a) * rad).toFloat(); val y = (32 + sin(a) * rad).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        p.shader = RadialGradient(24f, 22f, 36f, 0xFF9E8E7E.toInt(), 0xFF4A3F36.toInt(), Shader.TileMode.CLAMP)
        c.drawPath(path, p)
        p.shader = null; p.color = 0x55000000
        repeat(6) { c.drawCircle(14 + r.nextFloat() * 36, 14 + r.nextFloat() * 36, 2 + r.nextFloat() * 5, p) }
        return b
    }

    private fun laser(): Bitmap {
        val b = Bitmap.createBitmap(16, 64, Bitmap.Config.ARGB_8888)
        for (y in 0 until 64) for (x in 0 until 16) {
            val dx = abs(x - 7.5f) / 8f; val dy = abs(y - 31.5f) / 32f
            val core = (1 - dx * 2.2f).coerceIn(0f, 1f) * (1 - dy * dy).coerceIn(0f, 1f)
            val glow = (1 - dx).coerceIn(0f, 1f) * (1 - dy).coerceIn(0f, 1f)
            val a = (glow * 0.6f + core).coerceIn(0f, 1f)
            b.setPixel(x, y, Color.argb((a * 255).toInt(), (120 + core * 135).toInt().coerceAtMost(255), 255, (120 + core * 135).toInt().coerceAtMost(255)))
        }
        return b
    }

    private fun tree2d(): Bitmap {
        val b = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        for (y in 0 until 32) for (x in 0 until 32) {
            val dx = x - 15.5f; val dy = y - 12f
            if (dx * dx / 150f + dy * dy / 110f < 1f) b.setPixel(x, y, if ((x * 7 + y * 13) % 9 == 0) 0xFF2E7D32.toInt() else if (dx + dy < -6) 0xFF66BB6A.toInt() else 0xFF43A047.toInt())
            else if (y > 20 && abs(dx) < 2.5f) b.setPixel(x, y, 0xFF6D4C41.toInt())
        }
        return b
    }

    private fun cloud(): Bitmap {
        val b = Bitmap.createBitmap(128, 64, Bitmap.Config.ARGB_8888)
        val c = Canvas(b); val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xF2FFFFFF.toInt() }
        c.drawCircle(40f, 38f, 20f, p); c.drawCircle(64f, 28f, 24f, p); c.drawCircle(90f, 38f, 18f, p)
        c.drawRect(40f, 38f, 90f, 56f, p)
        return b
    }

    private fun softDot(color: Int): Bitmap {
        val b = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        for (y in 0 until 64) for (x in 0 until 64) {
            val d = sqrt((x - 31.5f) * (x - 31.5f) + (y - 31.5f) * (y - 31.5f)) / 32f
            val a = exp(-d * d * 4.5f) * (1 - d).coerceIn(0f, 1f)
            b.setPixel(x, y, (color and 0x00FFFFFF) or ((a * 255).toInt().coerceIn(0, 255) shl 24))
        }
        return b
    }

    private fun spark(): Bitmap {
        val b = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        for (y in 0 until 64) for (x in 0 until 64) {
            val dx = abs(x - 31.5f) / 32f; val dy = abs(y - 31.5f) / 32f
            val star = ((1 - dx * 8).coerceAtLeast(0f) * (1 - dy) + (1 - dy * 8).coerceAtLeast(0f) * (1 - dx)).coerceIn(0f, 1f)
            val core = exp(-(dx * dx + dy * dy) * 30f)
            val a = (star + core).coerceIn(0f, 1f)
            b.setPixel(x, y, Color.argb((a * 255).toInt(), 255, 255, (200 + 55 * core).toInt()))
        }
        return b
    }

    private fun uiButton(): Bitmap {
        val b = Bitmap.createBitmap(128, 48, Bitmap.Config.ARGB_8888)
        val c = Canvas(b); val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = 0xFF1B3A6B.toInt(); c.drawRoundRect(0f, 4f, 128f, 48f, 14f, 14f, p)
        p.shader = LinearGradient(0f, 0f, 0f, 44f, 0xFF5C9DFF.toInt(), 0xFF2F6BD6.toInt(), Shader.TileMode.CLAMP)
        c.drawRoundRect(0f, 0f, 128f, 44f, 14f, 14f, p)
        p.shader = null; p.color = 0x44FFFFFF; c.drawRoundRect(6f, 4f, 122f, 20f, 8f, 8f, p)
        return b
    }

    private fun explosion(): Bitmap {
        val n = 8; val s = 32
        val b = Bitmap.createBitmap(s * n, s, Bitmap.Config.ARGB_8888)
        val r = rng(41)
        val puffs = List(10) { floatArrayOf((r.nextFloat() - 0.5f) * 14, (r.nextFloat() - 0.5f) * 14, 4 + r.nextFloat() * 5) }
        for (i in 0 until n) {
            val t = i / (n - 1f)
            for (y in 0 until s) for (x in 0 until s) {
                var v = 0f
                for (p in puffs) {
                    val px = 15.5f + p[0] * (0.4f + t); val py = 15.5f + p[1] * (0.4f + t)
                    val rad = p[2] * (0.6f + t * 1.4f)
                    val d = sqrt((x - px) * (x - px) + (y - py) * (y - py)) / rad
                    v = maxOf(v, 1 - d)
                }
                if (v <= 0f) continue
                val heat = (v * (1.3f - t)).coerceIn(0f, 1f)
                val col = when {
                    heat > 0.7f -> lerp(0xFFFFE082.toInt(), 0xFFFFFFFF.toInt(), (heat - 0.7f) / 0.3f)
                    heat > 0.35f -> lerp(0xFFFF6F00.toInt(), 0xFFFFE082.toInt(), (heat - 0.35f) / 0.35f)
                    else -> lerp(0xFF424242.toInt(), 0xFFFF6F00.toInt(), heat / 0.35f)
                }
                val a = (v * 3f).coerceIn(0f, 1f) * (1 - t * 0.7f)
                b.setPixel(i * s + x, y, (col and 0x00FFFFFF) or ((a * 255).toInt() shl 24))
            }
        }
        return b
    }

    private fun fire(): Bitmap {
        val n = 6; val s = 32
        val b = Bitmap.createBitmap(s * n, s, Bitmap.Config.ARGB_8888)
        for (i in 0 until n) {
            val ph = i * 2 * PI / n
            for (y in 0 until s) for (x in 0 until s) {
                val fy = 1 - y / (s - 1f)
                val sway = (sin(fy * 6 + ph) * 3 * fy).toFloat()
                val width = (1 - fy) * 11 + 1.5f + (sin(ph + fy * 9) * 1.2).toFloat()
                val dx = abs(x - 15.5f - sway) / width
                if (dx >= 1f) continue
                val heat = ((1 - dx) * (1 - fy * 0.9f)).coerceIn(0f, 1f)
                val col = if (heat > 0.5f) lerp(0xFFFFA000.toInt(), 0xFFFFF59D.toInt(), (heat - 0.5f) * 2) else lerp(0xFFD84315.toInt(), 0xFFFFA000.toInt(), heat * 2)
                val a = ((1 - dx) * 2).coerceIn(0f, 1f) * (1 - fy * fy)
                b.setPixel(i * s + x, y, (col and 0x00FFFFFF) or ((a * 255).toInt() shl 24))
            }
        }
        return b
    }

    // ==================================================================== sounds

    object Sfx {
        private const val RATE = 22050

        fun wav(samples: FloatArray): ByteArray {
            val o = ByteArrayOutputStream(44 + samples.size * 2)
            fun u32(v: Int) { o.write(v and 0xFF); o.write((v shr 8) and 0xFF); o.write((v shr 16) and 0xFF); o.write((v shr 24) and 0xFF) }
            fun u16(v: Int) { o.write(v and 0xFF); o.write((v shr 8) and 0xFF) }
            o.write("RIFF".toByteArray()); u32(36 + samples.size * 2); o.write("WAVE".toByteArray())
            o.write("fmt ".toByteArray()); u32(16); u16(1); u16(1); u32(RATE); u32(RATE * 2); u16(2); u16(16)
            o.write("data".toByteArray()); u32(samples.size * 2)
            for (s in samples) u16((s.coerceIn(-1f, 1f) * 32000).toInt())
            return o.toByteArray()
        }

        private fun buf(sec: Float) = FloatArray((sec * RATE).toInt())
        private fun sq(ph: Double) = if ((ph % 1.0) < 0.5) 1f else -1f
        private fun tri(ph: Double) = (4 * abs((ph % 1.0) - 0.5) - 1).toFloat()

        /** Adds a tone with a frequency sweep and linear decay. */
        private fun tone(b: FloatArray, start: Float, dur: Float, f0: Float, f1: Float, vol: Float, wave: Int = 0) {
            var ph = 0.0
            val s0 = (start * RATE).toInt(); val n = (dur * RATE).toInt()
            for (i in 0 until n) {
                val idx = s0 + i
                if (idx >= b.size) break
                val t = i / n.toFloat()
                val f = f0 + (f1 - f0) * t
                ph += f / RATE
                val v = when (wave) { 1 -> sq(ph) * 0.6f; 2 -> tri(ph); else -> sin(ph * 2 * PI).toFloat() }
                val env = minOf(1f, i / (0.004f * RATE)) * (1 - t)
                b[idx] += v * env * vol
            }
        }

        private fun noise(b: FloatArray, start: Float, dur: Float, vol: Float, lowpass: Float) {
            val r = Random(3); var y = 0f
            val s0 = (start * RATE).toInt(); val n = (dur * RATE).toInt()
            for (i in 0 until n) {
                val idx = s0 + i; if (idx >= b.size) break
                val t = i / n.toFloat()
                y += (r.nextFloat() * 2 - 1 - y) * lowpass * (1 - t * 0.8f)
                b[idx] += y * vol * (1 - t) * (1 - t)
            }
        }

        fun coin() = buf(0.35f).also { tone(it, 0f, 0.08f, 988f, 988f, 0.5f, 1); tone(it, 0.07f, 0.28f, 1319f, 1319f, 0.5f, 1) }.let(::wav)
        fun jump() = buf(0.25f).also { tone(it, 0f, 0.25f, 260f, 760f, 0.55f, 1) }.let(::wav)
        fun laser() = buf(0.28f).also { tone(it, 0f, 0.28f, 1600f, 180f, 0.5f, 1) }.let(::wav)
        fun explosion() = buf(0.8f).also { noise(it, 0f, 0.8f, 1.2f, 0.12f); tone(it, 0f, 0.5f, 90f, 40f, 0.5f) }.let(::wav)
        fun hit() = buf(0.18f).also { noise(it, 0f, 0.12f, 0.9f, 0.5f); tone(it, 0f, 0.18f, 220f, 90f, 0.5f, 1) }.let(::wav)
        fun powerup() = buf(0.6f).also { for (i in 0 until 6) tone(it, i * 0.08f, 0.14f, 440f * Math.pow(1.26, i.toDouble()).toFloat(), 460f * Math.pow(1.26, i.toDouble()).toFloat(), 0.4f, 1) }.let(::wav)
        fun click() = buf(0.05f).also { tone(it, 0f, 0.05f, 1800f, 1200f, 0.4f) }.let(::wav)
        fun win() = buf(1.2f).also {
            val notes = listOf(523f, 659f, 784f, 1047f)
            notes.forEachIndexed { i, f -> tone(it, i * 0.15f, if (i == 3) 0.6f else 0.18f, f, f, 0.4f, 1) }
            tone(it, 0.45f, 0.7f, 523f, 523f, 0.25f, 2)
        }.let(::wav)
        fun lose() = buf(1.2f).also { listOf(392f, 370f, 349f, 330f).forEachIndexed { i, f -> tone(it, i * 0.25f, if (i == 3) 0.6f else 0.26f, f, f * 0.98f, 0.4f, 1) } }.let(::wav)

        fun music(): ByteArray {
            val bpm = 120f; val beat = 60f / bpm
            val b = buf(beat * 16)
            val lead = listOf(76, 79, 83, 79, 81, 79, 76, 74, 76, 79, 83, 86, 84, 83, 79, 76)
            val bass = listOf(40, 40, 43, 43, 45, 45, 43, 43)
            fun hz(m: Int) = (440.0 * Math.pow(2.0, (m - 69) / 12.0)).toFloat()
            lead.forEachIndexed { i, m -> tone(b, i * beat, beat * 0.9f, hz(m), hz(m), 0.22f, 1) }
            bass.forEachIndexed { i, m -> tone(b, i * beat * 2, beat * 1.9f, hz(m), hz(m), 0.35f, 2) }
            for (i in 0 until 16) noise(b, i * beat, 0.05f, if (i % 2 == 0) 0.25f else 0.12f, 0.9f)
            return wav(b)
        }
    }

    // ==================================================================== shaders

    object Shaders {
        val all = listOf(
            Triple("Wave.glsl", "Wavy distortion for textured sprites (Shader Param = strength)", """
// Wave distortion. uParam = strength
vec4 effect(vec4 color, vec2 uv) {
    vec2 d = vec2(sin(uv.y * 18.0 + uTime * 5.0), cos(uv.x * 14.0 + uTime * 4.0)) * 0.015 * uParam;
    vec4 t = texture2D(uTex, uv + d) * uColor;
    return uUseTex > 0.5 ? t : color;
}
""".trimStart()),
            Triple("Dissolve.glsl", "Burn away with glowing edges (animate Shader Param 0→1)", """
// Dissolve. Animate uParam from 0 (solid) to 1 (gone) with self.setShaderParam(v)
float hash(vec2 p) { return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453); }
float noise(vec2 p) {
    vec2 i = floor(p); vec2 f = fract(p); f = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash(i), hash(i + vec2(1.0, 0.0)), f.x), mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), f.x), f.y);
}
vec4 effect(vec4 color, vec2 uv) {
    float n = noise(uv * 12.0);
    float t = clamp(uParam, 0.0, 1.0);
    if (n < t) discard;
    float edge = smoothstep(t + 0.08, t, n);
    return vec4(mix(color.rgb, vec3(1.0, 0.55, 0.1) * 2.0, edge), color.a);
}
""".trimStart()),
            Triple("HitFlash.glsl", "Flash white when damaged (Shader Param 0..1)", """
// Hit flash. Set uParam to 1 on hit and fade it to 0
vec4 effect(vec4 color, vec2 uv) {
    return vec4(mix(color.rgb, vec3(1.0), clamp(uParam, 0.0, 1.0)), color.a);
}
""".trimStart()),
            Triple("Rainbow.glsl", "Animated hue shift", """
// Rainbow hue cycling. uParam = speed
vec3 hue(float h) { return clamp(abs(mod(h * 6.0 + vec3(0.0, 4.0, 2.0), 6.0) - 3.0) - 1.0, 0.0, 1.0); }
vec4 effect(vec4 color, vec2 uv) {
    vec3 h = hue(fract(uv.x * 0.5 + uv.y * 0.5 + uTime * 0.3 * uParam));
    return vec4(color.rgb * mix(vec3(1.0), h * 1.4, 0.8), color.a);
}
""".trimStart()),
            Triple("Hologram.glsl", "Sci-fi scanlines, flicker and transparency", """
// Hologram
vec4 effect(vec4 color, vec2 uv) {
    float scan = 0.6 + 0.4 * sin(uv.y * 120.0 - uTime * 8.0);
    float flicker = 0.85 + 0.15 * sin(uTime * 37.0);
    vec3 tint = vec3(0.3, 0.9, 1.0);
    float lum = dot(color.rgb, vec3(0.3, 0.6, 0.1));
    return vec4(tint * (lum + 0.35) * scan * flicker, color.a * 0.75);
}
""".trimStart()),
            Triple("Outline.glsl", "Glowing outline around textured sprites", """
// Outline for textured sprites. uParam = thickness
vec4 effect(vec4 color, vec2 uv) {
    if (uUseTex < 0.5) return color;
    float o = 0.02 * uParam;
    float a = texture2D(uTex, uv + vec2(o, 0.0)).a + texture2D(uTex, uv - vec2(o, 0.0)).a
            + texture2D(uTex, uv + vec2(0.0, o)).a + texture2D(uTex, uv - vec2(0.0, o)).a;
    vec3 glow = vec3(1.0, 0.85, 0.2) * (0.8 + 0.2 * sin(uTime * 6.0));
    if (color.a < 0.1 && a > 0.0) return vec4(glow, 1.0);
    return color;
}
""".trimStart()),
            Triple("Toon.glsl", "Cel shading for 3D meshes", """
// Toon / cel shading. Use on a MeshRenderer. uParam = bands (default 1 -> 4 bands)
vec4 effect(vec4 color, vec2 uv) {
    float bands = 4.0 * max(uParam, 0.25);
    vec3 c = floor(color.rgb * bands + 0.5) / bands;
    return vec4(c, color.a);
}
""".trimStart()),
            Triple("Pulse.glsl", "Pulsing glow", """
// Pulse glow. uParam = speed
vec4 effect(vec4 color, vec2 uv) {
    float p = 1.0 + 0.45 * sin(uTime * 4.0 * uParam);
    return vec4(color.rgb * p, color.a);
}
""".trimStart()),
            Triple("WaterRipple.glsl", "Scrolling water ripples (works on meshes and sprites)", """
// Water ripples
vec4 effect(vec4 color, vec2 uv) {
    float r = sin(uv.x * 30.0 + uTime * 2.0) * sin(uv.y * 30.0 + uTime * 1.7);
    vec3 c = color.rgb * (0.9 + 0.2 * r) + vec3(0.0, 0.05, 0.12) * uParam;
    return vec4(c, color.a);
}
""".trimStart()),
            Triple("RetroPost.glsl", "Custom camera post effect: scanlines + 4-color palette", """
// Post-processing shader. Use it as a camera's "FX Shader" with Post FX = Custom Shader.
vec4 effect(vec4 color, vec2 uv) {
    float l = dot(color.rgb, vec3(0.299, 0.587, 0.114));
    vec3 p0 = vec3(0.06, 0.22, 0.06), p1 = vec3(0.19, 0.38, 0.19), p2 = vec3(0.55, 0.67, 0.06), p3 = vec3(0.61, 0.74, 0.06);
    vec3 c = l < 0.25 ? p0 : l < 0.5 ? p1 : l < 0.75 ? p2 : p3;
    float scan = 0.9 + 0.1 * sin(uv.y * uResolution.y * 1.5);
    return vec4(mix(color.rgb, c, clamp(uParam, 0.0, 1.0)) * scan, 1.0);
}
""".trimStart()),
        )
    }

    // ==================================================================== scripts

    object Scripts {
        val all = listOf(
            Triple("PlayerPlatformer.js", "Run, jump, flip and animate (needs Rigidbody2D)", """
// Platformer player. Params: speed=6, jump=11, runClip=HeroRun.anim
var speed = 6, jump = 11, runClip = "HeroRun.anim";
function update(dt) {
    self.vx = input.axisX * speed;
    if (input.axisX != 0) self.flipX = input.axisX < 0;
    if (input.aDown && self.grounded) { self.vy = jump; audio.play("jump.wav"); }
    if (Math.abs(self.vx) > 0.1) self.play(runClip); else self.stopAnimation();
    if (self.y < -20) scene.reload();
}
""".trimStart()),
            Triple("TopDownPlayer.js", "8-direction movement with the joystick", """
// Top-down movement. Params: speed=5
var speed = 5;
function update(dt) {
    self.move(input.axisX * speed * dt, input.axisY * speed * dt);
    if (input.axisX != 0 || input.axisY != 0)
        self.rotation = Math.atan2(input.axisY, input.axisX) * 180 / Math.PI - 90;
}
""".trimStart()),
            Triple("Player3D.js", "3D character: joystick moves on XZ, A jumps (needs Rigidbody3D)", """
// 3D character controller. Params: speed=6, jump=7
var speed = 6, jump = 7;
function update(dt) {
    self.vx = input.axisX * speed;
    self.vz = -input.axisY * speed;
    if (input.axisX != 0 || input.axisY != 0)
        self.rotY = Math.atan2(input.axisX, input.axisY) * 180 / Math.PI;
    if (input.aDown && self.grounded) self.vy = jump;
    if (self.y < -30) scene.reload();
}
""".trimStart()),
            Triple("EnemyPatrol.js", "Walks back and forth; hurts the Player", """
// Patrol between two points. Params: distance=3, speed=2
var distance = 3, speed = 2, startX = 0, dir = 1;
function start() { startX = self.x; }
function update(dt) {
    self.x += dir * speed * dt;
    if (self.x > startX + distance) dir = -1;
    if (self.x < startX - distance) dir = 1;
    self.flipX = dir < 0;
}
function onCollision(other) {
    if (other.tag == "Player") { scene.shake(0.6); audio.play("hit.wav"); other.send("hurt", 1); }
}
""".trimStart()),
            Triple("Collectible.js", "Pick-up that adds score to a ScoreText object", """
// Collectible (use a trigger collider). Params: points=1
var points = 1;
function update(dt) { self.rotation += 90 * dt; }
function onTrigger(other) {
    if (other.tag != "Player") return;
    var score = scene.find("ScoreText");
    if (score) { var n = (parseInt(score.text.replace(/[^0-9]/g, "")) || 0) + points; score.text = "Score: " + n; }
    audio.play("coin.wav");
    self.destroy();
}
""".trimStart()),
            Triple("Bullet.js", "Flies forward and destroys what it hits", """
// Bullet. Params: speed=12, life=2
var speed = 12, life = 2;
function start() { after(life, function () { self.destroy(); }); }
function update(dt) {
    var a = (self.rotation + 90) * Math.PI / 180;
    self.move(Math.cos(a) * speed * dt, Math.sin(a) * speed * dt);
}
function onTrigger(other) {
    if (other.tag == "Enemy") { other.destroy(); self.destroy(); scene.shake(0.3); audio.play("explosion.wav"); }
}
""".trimStart()),
            Triple("Spawner.js", "Spawns an inactive template object on a timer", """
// Spawner. Params: template=Enemy, interval=1.5, range=6
var template = "Enemy", interval = 1.5, range = 6;
function start() {
    every(interval, function () { scene.spawn(template, self.worldX + random(-range, range), self.worldY); });
}
""".trimStart()),
            Triple("FollowTarget.js", "Smoothly follows another object", """
// Follow. Params: target=Player, smooth=4
var target = "Player", smooth = 4, t = null;
function start() { t = scene.find(target); }
function update(dt) {
    if (!t) return;
    self.x = lerp(self.x, t.worldX, clamp(smooth * dt, 0, 1));
    self.y = lerp(self.y, t.worldY, clamp(smooth * dt, 0, 1));
}
""".trimStart()),
            Triple("Rotator.js", "Spins on X/Y/Z (works in 2D and 3D)", """
// Rotator. Params: x=0, y=90, z=0
var x = 0, y = 90, z = 0;
function update(dt) { self.rotate(x * dt, y * dt, z * dt); }
""".trimStart()),
            Triple("Health.js", "Hit points with flash, death burst and restart", """
// Health. Params: hp=3
var hp = 3, flash = 0;
function hurt(amount) {
    hp -= amount || 1; flash = 1;
    if (hp <= 0) { self.burst(40); audio.play("explosion.wav"); after(1, function () { scene.reload(); }); self.visible = false; }
}
function update(dt) { if (flash > 0) { flash = Math.max(0, flash - dt * 4); self.setShaderParam(flash); } }
""".trimStart()),
            Triple("OrbitCamera3D.js", "Put on a Camera3D: drag to orbit around a target", """
// Orbit camera. Params: target=Player, distance=10, height=4
var target = "Player", distance = 10, height = 4, angle = 0, t = null;
function start() { t = scene.find(target); }
function update(dt) {
    if (input.touching) angle += input.axisX * 90 * dt;
    angle += 10 * dt;
    var cx = t ? t.worldX : 0, cz = t ? t.worldZ : 0, cy = t ? t.worldY : 0;
    var a = angle * Math.PI / 180;
    self.setPosition(cx + Math.sin(a) * distance, cy + height, cz + Math.cos(a) * distance);
    self.rotY = angle;
    self.rotX = -Math.atan2(height, distance) * 180 / Math.PI;
}
""".trimStart()),
        )
    }

    // ==================================================================== OBJ models

    object Models {
        private class Obj {
            val sb = StringBuilder("# S Engine generated model\n")
            var n = 0
            fun v(x: Double, y: Double, z: Double): Int { sb.append(String.format(java.util.Locale.US, "v %.4f %.4f %.4f\n", x, y, z)); return ++n }
            fun f(vararg i: Int) { sb.append("f ").append(i.joinToString(" ")).append('\n') }
            /** Ring-based surface of revolution: list of (y, radius). */
            fun lathe(profile: List<Pair<Double, Double>>, seg: Int, ox: Double = 0.0, oz: Double = 0.0, cap: Boolean = true) {
                val rings = profile.map { (y, r) -> (0 until seg).map { i -> val a = i * 2 * PI / seg; v(ox + cos(a) * r, y, oz + sin(a) * r) } }
                for (k in 0 until rings.size - 1) for (i in 0 until seg) {
                    val a = rings[k][i]; val b = rings[k][(i + 1) % seg]; val c = rings[k + 1][(i + 1) % seg]; val d = rings[k + 1][i]
                    f(a, d, c, b)
                }
                if (cap) {
                    val bottom = v(ox, profile.first().first, oz); val top = v(ox, profile.last().first, oz)
                    for (i in 0 until seg) { f(bottom, rings.first()[i], rings.first()[(i + 1) % seg]); f(top, rings.last()[(i + 1) % seg], rings.last()[i]) }
                }
            }
            fun box(x0: Double, y0: Double, z0: Double, x1: Double, y1: Double, z1: Double) {
                val p = Array(8) { i -> v(if (i and 1 == 0) x0 else x1, if (i and 2 == 0) y0 else y1, if (i and 4 == 0) z0 else z1) }
                f(p[0], p[2], p[3], p[1]); f(p[4], p[5], p[7], p[6]); f(p[0], p[1], p[5], p[4])
                f(p[2], p[6], p[7], p[3]); f(p[0], p[4], p[6], p[2]); f(p[1], p[3], p[7], p[5])
            }
        }

        fun tree(): String = Obj().apply {
            lathe(listOf(0.0 to 0.12, 0.8 to 0.1), 8)
            lathe(listOf(0.6 to 0.7, 1.4 to 0.35, 1.45 to 0.0), 10)
            lathe(listOf(1.2 to 0.5, 2.0 to 0.2, 2.05 to 0.0), 10)
        }.sb.toString()

        fun rock(): String = Obj().apply {
            val r = Random(5)
            val seg = 10; val rings = 7
            val grid = (0..rings).map { j ->
                (0 until seg).map { i ->
                    val th = j * PI / rings; val ph = i * 2 * PI / seg
                    val rad = if (j == 0 || j == rings) 1.0 else 0.8 + r.nextDouble() * 0.4
                    v(sin(th) * cos(ph) * rad, cos(th) * rad * 0.7, sin(th) * sin(ph) * rad)
                }
            }
            for (j in 0 until rings) for (i in 0 until seg) f(grid[j][i], grid[j][(i + 1) % seg], grid[j + 1][(i + 1) % seg], grid[j + 1][i])
        }.sb.toString()

        fun house(): String = Obj().apply {
            box(-1.0, 0.0, -0.8, 1.0, 1.2, 0.8)
            val a = v(-1.1, 1.2, -0.9); val b = v(1.1, 1.2, -0.9); val c = v(1.1, 1.2, 0.9); val d = v(-1.1, 1.2, 0.9)
            val e = v(-1.1, 2.0, 0.0); val g = v(1.1, 2.0, 0.0)
            f(a, e, g, b); f(d, c, g, e); f(a, d, e); f(b, g, c); f(a, b, c, d)
            box(-0.25, 0.0, 0.8, 0.25, 0.7, 0.85)
            box(0.6, 1.4, -0.3, 0.8, 2.0, -0.1)
        }.sb.toString()

        fun crystal(): String = Obj().apply {
            val seg = 6
            val top = v(0.0, 1.6, 0.0); val bottom = v(0.0, -0.4, 0.0)
            val ring = (0 until seg).map { i -> val a = i * 2 * PI / seg; v(cos(a) * 0.45, 0.4, sin(a) * 0.45) }
            for (i in 0 until seg) { f(top, ring[(i + 1) % seg], ring[i]); f(bottom, ring[i], ring[(i + 1) % seg]) }
        }.sb.toString()

        fun barrel(): String = Obj().apply {
            lathe((0..8).map { k -> val y = k / 8.0 * 1.2; y to (0.42 + sin(k / 8.0 * PI) * 0.1) }, 16)
        }.sb.toString()

        fun arrow(): String = Obj().apply {
            box(-0.08, -0.08, 0.0, 0.08, 0.08, 1.0)
            val tip = v(0.0, 0.0, -0.5)
            val ring = (0 until 8).map { i -> val a = i * 2 * PI / 8; v(cos(a) * 0.25, sin(a) * 0.25, 0.0) }
            for (i in 0 until 8) f(tip, ring[i], ring[(i + 1) % 8])
            val c = v(0.0, 0.0, 0.0)
            for (i in 0 until 8) f(c, ring[(i + 1) % 8], ring[i])
        }.sb.toString()
    }
}
