package com.sengine.engine

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.SoundPool
import android.media.ToneGenerator
import android.os.Build
import com.sengine.project.Project
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 2D audio engine.
 *
 * Short sounds (SFX, UI, impacts) go through a [SoundPool] with real stream pooling, per-voice
 * pitch/pan and 2D spatial attenuation; music and ambience go through [MediaPlayer] so long tracks
 * stream instead of being decoded into memory. Five buses (Music / SFX / UI / Ambience / Master)
 * give a mixer with per-bus volume and mute.
 *
 * Everything degrades gracefully: in a headless JVM (unit tests) or when no audio device is
 * available the system reports [available] = false and every call becomes a no-op instead of
 * crashing the game.
 */
class AudioSystem(private val project: Project) {

    object Bus {
        const val MUSIC = 0
        const val SFX = 1
        const val UI = 2
        const val AMBIENCE = 3
        const val MASTER = 4
        val names = arrayOf("Music", "SFX", "UI", "Ambience", "Master")
        fun name(i: Int) = names.getOrElse(i) { "SFX" }
        fun indexOf(name: String) = names.indexOfFirst { it.equals(name, true) }.let { if (it < 0) SFX else it }
    }

    /** One playing stream (SoundPool voice or music player). */
    class Voice(val handle: Int, val name: String, val bus: Int) {
        var streamId = 0
        var music: MediaPlayer? = null
        var volume = 1f            // user volume for this voice
        var baseVolume = 1f        // bus * spatial * fade
        var targetVolume = 1f
        var fadeSpeed = 0f
        var pitch = 1f
        var pan = 0f
        var loop = false
        var spatial = false
        var x = 0f; var y = 0f
        var minDistance = 2f; var maxDistance = 20f
        var paused = false
        val effective get() = volume * baseVolume
    }

    var available = false
        private set
    /** Master switch used by the editor / settings. */
    var muted = false
    /** Maximum simultaneous SoundPool streams (24 keeps low-end devices happy). */
    var maxStreams = 24
    var musicVolumePercent = 0.8f
    private var pool: SoundPool? = null
    private var tone: ToneGenerator? = null
    private val busVolume = floatArrayOf(1f, 1f, 1f, 1f, 1f)
    private val soundIds = HashMap<String, Int>()
    private val loading = HashSet<String>()
    private val failed = HashSet<String>()
    private val voices = ArrayList<Voice>(32)
    private val freeVoices = ArrayList<Voice>(32)
    private var handleSeq = 1
    private val cacheDirLazy = HashMap<String, String>()
    private var context: Context? = null
    /** Listener position for 2D spatial panning/attenuation (usually the camera). */
    var listenerX = 0f
    var listenerY = 0f
    /** Half width of the audible screen in world units (used for panning). */
    var listenerHalfWidth = 8f
    var statsLoaded = 0
    var statsPlayed = 0L
    var statsDropped = 0L

    /** Android context used to read sounds packaged inside the exported APK. */
    fun attachContext(c: Context?) {
        context = c
    }

    fun start() {
        if (available) return
        try {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            pool = SoundPool.Builder().setMaxStreams(maxStreams).setAudioAttributes(attrs).build()
            pool?.setOnLoadCompleteListener { _, _, status -> if (status != 0) statsDropped++ }
            available = pool != null
        } catch (e: Throwable) {
            pool = null
            available = false
        }
        if (available) statsLoaded = 0
    }

    fun release() {
        try { pool?.release() } catch (_: Throwable) {}
        pool = null
        for (v in voices) try { v.music?.release() } catch (_: Throwable) {}
        voices.clear()
        freeVoices.clear()
        soundIds.clear()
        loading.clear()
        failed.clear()
        try { tone?.release() } catch (_: Throwable) {}
        tone = null
        available = false
    }

    // ---------------------------------------------------------------- assets
    /** Resolves an asset name to a readable file path (project asset, then packaged APK asset). */
    private fun resolve(name: String): String? {
        val local = project.assetFile(name)
        if (local.exists()) return local.absolutePath
        cacheDirLazy[name]?.let { if (File(it).exists()) return it }
        val ctx = context ?: return null
        return try {
            val dir = File(ctx.cacheDir, "audio").apply { mkdirs() }
            val out = File(dir, name.replace('/', '_'))
            if (!out.exists() || out.length() == 0L) {
                ctx.assets.open(name).use { input -> out.outputStream().use { input.copyTo(it) } }
            }
            val path = out.absolutePath
            cacheDirLazy[name] = path
            path
        } catch (e: Throwable) {
            failed.add(name)
            null
        }
    }

    private fun soundId(name: String): Int {
        val p = pool ?: return 0
        soundIds[name]?.let { return it }
        if (name in failed || name in loading) return 0
        val path = resolve(name) ?: run { failed.add(name); return 0 }
        return try {
            loading.add(name)
            val id = p.load(path, 1)
            if (id == 0) { failed.add(name); 0 } else {
                soundIds[name] = id
                statsLoaded = soundIds.size
                id
            }
        } catch (e: Throwable) {
            failed.add(name)
            0
        }
    }

    /** Pre-loads a set of clips so gameplay never hitches (asset streaming / validation). */
    fun preload(names: Collection<String>) {
        if (!available) return
        for (n in names) soundId(n)
    }

    // ---------------------------------------------------------------- playback
    /**
     * Plays a short sound. Returns a handle usable with [stop], [setPitch], [fade], or 0 when the
     * sound could not be played.
     */
    fun play(
        name: String, volume: Float = 1f, loop: Boolean = false, bus: Int = Bus.SFX,
        pitch: Float = 1f, pan: Float = 0f
    ): Int {
        if (name.isBlank()) return 0
        if (!available) return playHeadless(name, volume, loop, bus, pitch, pan)
        val p = pool ?: return 0
        val id = soundId(name)
        if (id == 0) return 0
        if (voices.count { !it.paused && it.music == null } >= maxStreams) {
            // steal the quietest voice instead of dropping the sound entirely
            val victim = voices.filter { it.music == null && !it.paused }.minByOrNull { it.effective }
            if (victim != null) stop(victim.handle)
        }
        val v = obtainVoice(bus)
        v.streamId = p.play(
            id, channelVolume(volume, bus, pan), channelVolumeRight(volume, bus, pan),
            1, if (loop) -1 else 0, pitch.coerceIn(0.5f, 2f)
        )
        if (v.streamId == 0) {
            statsDropped++
            freeVoice(v)
            return 0
        }
        v.pitch = pitch
        v.pan = pan
        statsPlayed++
        return v.handle
    }

    /** Plays a world-positioned sound with 2D attenuation and stereo panning. */
    fun playAt(
        name: String, x: Float, y: Float, volume: Float = 1f, loop: Boolean = false,
        bus: Int = Bus.SFX, minDistance: Float = 2f, maxDistance: Float = 20f
    ): Int {
        val dx = x - listenerX
        val dy = y - listenerY
        val distance = kotlin.math.sqrt(dx * dx + dy * dy)
        val atten = if (maxDistance <= minDistance) 1f
        else (1f - ((distance - minDistance) / (maxDistance - minDistance))).coerceIn(0f, 1f)
        val pan = if (listenerHalfWidth <= 0.01f) 0f else (dx / listenerHalfWidth).coerceIn(-1f, 1f)
        val h = play(name, volume * atten, loop, bus, 1f, pan)
        if (h != 0) {
            val v = voices.firstOrNull { it.handle == h }
            v?.let {
                it.spatial = true; it.x = x; it.y = y
                it.minDistance = minDistance; it.maxDistance = maxDistance
            }
        }
        return h
    }

    /** Playback counter for the profiler even when audio is unavailable (headless tests). */
    private var headlessHandles = 0

    private fun playHeadless(
        name: String, volume: Float, loop: Boolean, bus: Int, pitch: Float, pan: Float
    ): Int {
        if (name.isBlank()) return 0
        val v = obtainVoice(bus)
        v.baseVolume = volume
        v.volume = volume
        v.loop = loop
        v.pitch = pitch
        v.pan = pan
        headlessHandles++
        statsPlayed++
        return v.handle
    }

    /** Music / long ambience streamed through MediaPlayer. */
    fun music(name: String, volume: Float = musicVolumePercent, loop: Boolean = true, bus: Int = Bus.MUSIC): Int {
        stopBus(Bus.MUSIC, 0.2f)
        if (name.isBlank()) return 0
        if (!available) return playHeadless(name, volume, loop, bus, 1f, 0f)
        val path = resolve(name) ?: return 0
        return try {
            val mp = MediaPlayer()
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            mp.setDataSource(path)
            mp.isLooping = loop
            mp.setVolume(volume, volume)
            mp.prepare()
            mp.start()
            val v = obtainVoice(bus)
            v.music = mp
            v.volume = volume
            v.baseVolume = 1f
            v.loop = loop
            statsPlayed++
            v.handle
        } catch (e: Throwable) {
            failed.add(name)
            0
        }
    }

    fun stopMusic(fade: Float = 0.4f) = stopBus(Bus.MUSIC, fade)

    fun stop(handle: Int, fade: Float = 0f) {
        val v = voices.firstOrNull { it.handle == handle } ?: return
        if (fade > 0f) {
            v.targetVolume = 0f
            v.fadeSpeed = v.baseVolume / fade
            v.loop = false
            return
        }
        v.music?.let { try { it.stop() } catch (_: Throwable) {}; try { it.release() } catch (_: Throwable) {} }
        if (v.streamId != 0) try { pool?.stop(v.streamId) } catch (_: Throwable) {}
        freeVoice(v)
    }

    fun stopBus(bus: Int, fade: Float = 0f) {
        for (v in voices.filter { it.bus == bus }) stop(v.handle, fade)
    }

    fun stopAll() {
        stopBus(Bus.MUSIC, 0f)
        stopBus(Bus.SFX, 0f)
        stopBus(Bus.UI, 0f)
        stopBus(Bus.AMBIENCE, 0f)
    }

    fun pauseAll() {
        for (v in voices) {
            v.paused = true
            v.music?.let { try { it.pause() } catch (_: Throwable) {} }
        }
        try { pool?.autoPause() } catch (_: Throwable) {}
    }

    fun resumeAll() {
        for (v in voices) {
            v.paused = false
            v.music?.let { try { it.start() } catch (_: Throwable) {} }
        }
        try { pool?.autoResume() } catch (_: Throwable) {}
    }

    fun isPlaying(handle: Int): Boolean = voices.any { it.handle == handle && !it.paused }

    fun activeVoices() = voices.count { !it.paused }

    // ---------------------------------------------------------------- voice control
    fun setVolume(handle: Int, volume: Float) {
        val v = voices.firstOrNull { it.handle == handle } ?: return
        v.volume = volume.coerceIn(0f, 4f)
        v.targetVolume = v.volume
        applyVolume(v)
    }

    fun setPitch(handle: Int, pitch: Float) {
        val v = voices.firstOrNull { it.handle == handle } ?: return
        v.pitch = pitch.coerceIn(0.5f, 2f)
        if (v.streamId != 0) try { pool?.setRate(v.streamId, v.pitch) } catch (_: Throwable) {}
    }

    fun setPan(handle: Int, pan: Float) {
        val v = voices.firstOrNull { it.handle == handle } ?: return
        v.pan = pan.coerceIn(-1f, 1f)
        applyVolume(v)
    }

    /** Fades a voice towards [target] over [seconds] (0 = stop when silent). */
    fun fade(handle: Int, target: Float, seconds: Float) {
        val v = voices.firstOrNull { it.handle == handle } ?: return
        v.targetVolume = target.coerceIn(0f, 1f)
        v.fadeSpeed = if (seconds <= 0.001f) Float.MAX_VALUE else 1f / seconds
    }

    /** Fades the whole mix (menus, scene transitions). */
    fun fadeBus(bus: Int, target: Float, seconds: Float) {
        setBusVolume(bus, target, seconds)
    }

    private fun obtainVoice(bus: Int): Voice {
        val v = freeVoices.removeLastOrNull() ?: Voice(handleSeq++, "", bus)
        v.streamId = 0
        v.music = null
        v.volume = 1f
        v.baseVolume = 1f
        v.targetVolume = 1f
        v.fadeSpeed = 0f
        v.pitch = 1f
        v.pan = 0f
        v.paused = false
        v.spatial = false
        voices.add(v)
        return v
    }

    private fun freeVoice(v: Voice) {
        voices.remove(v)
        v.music = null
        v.streamId = 0
        if (freeVoices.size < 64) freeVoices.add(v)
    }

    private fun channelVolume(volume: Float, bus: Int, pan: Float): Float {
        val b = if (muted) 0f else busVolume[bus] * busVolume[Bus.MASTER]
        val p = (1f - pan.coerceIn(0f, 1f))
        return (volume * b * p).coerceIn(0f, 1f)
    }

    private fun channelVolumeRight(volume: Float, bus: Int, pan: Float): Float {
        val b = if (muted) 0f else busVolume[bus] * busVolume[Bus.MASTER]
        val p = (1f + pan.coerceIn(-1f, 0f))
        return (volume * b * p).coerceIn(0f, 1f)
    }

    private fun applyVolume(v: Voice) {
        val b = if (muted) 0f else busVolume[v.bus] * busVolume[Bus.MASTER]
        val vol = (v.volume * v.baseVolume * b).coerceIn(0f, 1f)
        val left = vol * (1f - v.pan.coerceIn(0f, 1f))
        val right = vol * (1f + v.pan.coerceIn(-1f, 0f))
        if (v.streamId != 0) try { pool?.setVolume(v.streamId, left, right) } catch (_: Throwable) {}
        v.music?.let { try { it.setVolume(left, right) } catch (_: Throwable) {} }
    }

    // ---------------------------------------------------------------- mixer
    fun setBusVolume(bus: Int, value: Float, fadeSeconds: Float = 0f) {
        val target = value.coerceIn(0f, 1f)
        if (fadeSeconds > 0.001f) {
            busFadeTarget[bus] = target
            busFadeSpeed[bus] = abs(target - busVolume[bus]) / fadeSeconds
        } else {
            busVolume[bus] = target
            busFadeSpeed[bus] = 0f
            applyBusVolume()
        }
    }

    fun busVolume(bus: Int): Float = busVolume[bus]
    fun busVolumes(): FloatArray = busVolume.clone()


    private val busFadeTarget = FloatArray(5)
    private val busFadeSpeed = FloatArray(5)

    private fun applyBusVolume() {
        for (v in voices) {
            v.baseVolume = 1f
            applyVolume(v)
        }
    }

    // ---------------------------------------------------------------- frame update (fades, spatial)
    fun update(dt: Float) {
        // bus fades
        for (i in busVolume.indices) {
            if (busFadeSpeed[i] <= 0f) continue
            val target = busFadeTarget[i]
            val step = busFadeSpeed[i] * dt
            busVolume[i] = if (busVolume[i] < target) min(target, busVolume[i] + step)
            else max(target, busVolume[i] - step)
            if (abs(busVolume[i] - target) <= 0.001f) {
                busVolume[i] = target
                busFadeSpeed[i] = 0f
                applyBusVolume()
            }
        }
        if (dt <= 0f) return
        val done = ArrayList<Voice>(4)
        for (v in voices) {
            // per-voice fades
            if (v.fadeSpeed > 0f) {
                val target = v.targetVolume
                var b = v.baseVolume
                b = if (b < target) min(target, b + v.fadeSpeed * dt) else max(target, b - v.fadeSpeed * dt)
                v.baseVolume = b
                applyVolume(v)
                if (abs(b - target) <= 0.001f) {
                    v.fadeSpeed = 0f
                    v.baseVolume = target
                    if (target <= 0.001f) {
                        if (v.music != null || v.streamId != 0) { stop(v.handle, 0f); continue }
                        done.add(v)
                        continue
                    }
                }
            }
            // music streams end on their own when not looping
            val mp = v.music
            if (mp != null) {
                val ended = try { !mp.isPlaying && !v.paused } catch (_: Throwable) { true }
                if (ended) { stop(v.handle, 0f); continue }
            }
            // 2D spatial update
            if (v.spatial) {
                val dx = v.x - listenerX
                val dy = v.y - listenerY
                val distance = kotlin.math.sqrt(dx * dx + dy * dy)
                val atten = if (v.maxDistance <= v.minDistance) 1f
                else (1f - ((distance - v.minDistance) / (v.maxDistance - v.minDistance))).coerceIn(0f, 1f)
                v.pan = if (listenerHalfWidth <= 0.01f) 0f else (dx / listenerHalfWidth).coerceIn(-1f, 1f)
                val b = if (muted) 0f else busVolume[v.bus] * busVolume[Bus.MASTER]
                val vol = (v.volume * b * atten).coerceIn(0f, 1f)
                if (v.streamId != 0) {
                    try {
                        pool?.setVolume(v.streamId, vol * (1f - v.pan.coerceIn(0f, 1f)),
                            vol * (1f + v.pan.coerceIn(-1f, 0f)))
                    } catch (_: Throwable) {}
                }
                if (atten <= 0.001f && v.streamId != 0) { stop(v.handle, 0f); continue }
            }
        }
        for (v in done) freeVoice(v)
    }

    // ---------------------------------------------------------------- helpers
    /** Diagnostic tone used by the editor (audio "are speakers working?" check). */
    fun beep(durationMs: Int = 80) {
        val runnable = Thread {
            try {
                val t = tone ?: ToneGenerator(AudioManager.STREAM_MUSIC, 70).also { tone = it }
                t.startTone(ToneGenerator.TONE_PROP_BEEP, durationMs)
            } catch (_: Throwable) {
            }
        }
        runnable.isDaemon = true
        try { runnable.start() } catch (_: Throwable) {}
        statsPlayed++
    }

    /** Text report for the profiler console. */
    fun stats(): String =
        "audio: ${if (available) "on" else "off"} voices=${activeVoices()}/$maxStreams " +
            "loaded=$statsLoaded played=$statsPlayed dropped=$statsDropped music=${"%.0f".format(busVolume[Bus.MUSIC] * 100)}% sfx=${"%.0f".format(busVolume[Bus.SFX] * 100)}%"

    fun clearFailures() {
        failed.clear()
    }
}
