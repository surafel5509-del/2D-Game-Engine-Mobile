package com.sengine.engine.vfx

import com.sengine.engine.core.ParticleEmitter

/**
 * Professional VFX preset library for common particle effects.
 * All presets are fully configurable and can be modified at runtime.
 */
object VFXPresets {

    /**
     * Fire effect - flickering flames with heat distortion
     */
    fun createFire(): ParticleEmitter.Preset {
        return ParticleEmitter.Preset(
            name = "Fire",
            rate = 50f,
            lifetime = 1.5f,
            speed = 3f,
            direction = 90f,
            spread = 20f,
            startSize = 0.4f,
            endSize = 0.1f,
            startColor = 0xFFFFCC00.toInt(),
            endColor = 0x00FF3300.toInt(),
            gravity = -2f,
            turbulence = 0.3f,
            noise = 0.5f,
            randomness = 0.4f
        )
    }

    /**
     * Smoke effect - rising, dissipating smoke clouds
     */
    fun createSmoke(): ParticleEmitter.Preset {
        return ParticleEmitter.Preset(
            name = "Smoke",
            rate = 20f,
            lifetime = 3f,
            speed = 1.5f,
            direction = 90f,
            spread = 45f,
            startSize = 0.3f,
            endSize = 0.8f,
            startColor = 0xAA444444.toInt(),
            endColor = 0x00222222.toInt(),
            gravity = -0.5f,
            turbulence = 0.2f,
            noise = 0.3f
        )
    }

    /**
     * Explosion - rapid burst with shockwave
     */
    fun createExplosion(): ParticleEmitter.Preset {
        return ParticleEmitter.Preset(
            name = "Explosion",
            rate = 0f,
            burstCount = 50,
            lifetime = 0.8f,
            speed = 8f,
            direction = 0f,
            spread = 360f,
            startSize = 0.5f,
            endSize = 0.1f,
            startColor = 0xFFFFFF00.toInt(),
            endColor = 0x00FF0000.toInt(),
            gravity = 0f,
            turbulence = 0.5f,
            randomness = 0.6f
        )
    }

    /**
     * Sparks - small bright particles
     */
    fun createSparks(): ParticleEmitter.Preset {
        return ParticleEmitter.Preset(
            name = "Sparks",
            rate = 0f,
            burstCount = 30,
            lifetime = 0.6f,
            speed = 10f,
            direction = 0f,
            spread = 360f,
            startSize = 0.1f,
            endSize = 0.05f,
            startColor = 0xFFFFFF00.toInt(),
            endColor = 0x00FFAA00.toInt(),
            gravity = 5f
        )
    }

    /**
     * Dust - ambient floating particles
     */
    fun createDust(): ParticleEmitter.Preset {
        return ParticleEmitter.Preset(
            name = "Dust",
            rate = 10f,
            lifetime = 4f,
            speed = 0.3f,
            direction = 90f,
            spread = 180f,
            startSize = 0.15f,
            endSize = 0.2f,
            startColor = 0x44FFFFFF.toInt(),
            endColor = 0x00CCCCCC.toInt(),
            gravity = -0.1f,
            turbulence = 0.4f,
            noise = 0.6f
        )
    }

    /**
     * Rain - falling droplets
     */
    fun createRain(): ParticleEmitter.Preset {
        return ParticleEmitter.Preset(
            name = "Rain",
            rate = 100f,
            lifetime = 1.5f,
            speed = 15f,
            direction = -90f,
            spread = 5f,
            startSize = 0.05f,
            endSize = 0.05f,
            startColor = 0x88AACCFF.toInt(),
            endColor = 0x0088AAFF.toInt(),
            gravity = 10f
        )
    }

    /**
     * Snow - gently falling snowflakes
     */
    fun createSnow(): ParticleEmitter.Preset {
        return ParticleEmitter.Preset(
            name = "Snow",
            rate = 30f,
            lifetime = 5f,
            speed = 1f,
            direction = -90f,
            spread = 30f,
            startSize = 0.1f,
            endSize = 0.15f,
            startColor = 0xFFFFFFFF.toInt(),
            endColor = 0x88FFFFFF.toInt(),
            gravity = 0.5f,
            turbulence = 0.8f,
            noise = 1f
        )
    }

    /**
     * Fog - low-rolling fog effect
     */
    fun createFog(): ParticleEmitter.Preset {
        return ParticleEmitter.Preset(
            name = "Fog",
            rate = 15f,
            lifetime = 6f,
            speed = 0.5f,
            direction = 0f,
            spread = 360f,
            startSize = 1f,
            endSize = 2f,
            startColor = 0x22CCCCCC.toInt(),
            endColor = 0x00AAAAAA.toInt(),
            gravity = -0.2f,
            turbulence = 0.3f,
            noise = 0.5f
        )
    }

    /**
     * Steam - rising vapor
     */
    fun createSteam(): ParticleEmitter.Preset {
        return ParticleEmitter.Preset(
            name = "Steam",
            rate = 25f,
            lifetime = 2f,
            speed = 2f,
            direction = 90f,
            spread = 30f,
            startSize = 0.2f,
            endSize = 0.6f,
            startColor = 0x88FFFFFF.toInt(),
            endColor = 0x00CCCCCC.toInt(),
            gravity = -1.5f,
            turbulence = 0.4f
        )
    }

    /**
     * Magic - mystical swirling particles
     */
    fun createMagic(): ParticleEmitter.Preset {
        return ParticleEmitter.Preset(
            name = "Magic",
            rate = 40f,
            lifetime = 2f,
            speed = 3f,
            direction = 0f,
            spread = 360f,
            startSize = 0.2f,
            endSize = 0.1f,
            startColor = 0xFFFF00FF.toInt(),
            endColor = 0x0000FFFF.toInt(),
            gravity = 0f,
            turbulence = 1f,
            noise = 0.8f,
            rotationSpeed = 180f
        )
    }

    /**
     * Energy - glowing energy particles
     */
    fun createEnergy(): ParticleEmitter.Preset {
        return ParticleEmitter.Preset(
            name = "Energy",
            rate = 60f,
            lifetime = 1f,
            speed = 5f,
            direction = 0f,
            spread = 360f,
            startSize = 0.15f,
            endSize = 0.05f,
            startColor = 0xFF00FFFF.toInt(),
            endColor = 0x000088FF.toInt(),
            gravity = 0f,
            turbulence = 0.6f
        )
    }

    /**
     * Electricity - crackling lightning effect
     */
    fun createElectricity(): ParticleEmitter.Preset {
        return ParticleEmitter.Preset(
            name = "Electricity",
            rate = 0f,
            burstCount = 20,
            lifetime = 0.3f,
            speed = 15f,
            direction = 0f,
            spread = 360f,
            startSize = 0.08f,
            endSize = 0.02f,
            startColor = 0xFFFFFFFF.toInt(),
            endColor = 0x0000CCFF.toInt(),
            gravity = 0f,
            randomness = 1f
        )
    }

    /**
     * Blood impact - splatter effect
     */
    fun createBloodImpact(): ParticleEmitter.Preset {
        return ParticleEmitter.Preset(
            name = "Blood Impact",
            rate = 0f,
            burstCount = 25,
            lifetime = 0.8f,
            speed = 6f,
            direction = 0f,
            spread = 180f,
            startSize = 0.2f,
            endSize = 0.1f,
            startColor = 0xFFCC0000.toInt(),
            endColor = 0x00880000.toInt(),
            gravity = 8f,
            randomness = 0.5f
        )
    }

    /**
     * Water splash - water droplets
     */
    fun createWaterSplash(): ParticleEmitter.Preset {
        return ParticleEmitter.Preset(
            name = "Water Splash",
            rate = 0f,
            burstCount = 30,
            lifetime = 1f,
            speed = 5f,
            direction = 90f,
            spread = 120f,
            startSize = 0.15f,
            endSize = 0.05f,
            startColor = 0xAA4488FF.toInt(),
            endColor = 0x002244FF.toInt(),
            gravity = 12f
        )
    }

    /**
     * Shockwave - expanding ring
     */
    fun createShockwave(): ParticleEmitter.Preset {
        return ParticleEmitter.Preset(
            name = "Shockwave",
            rate = 0f,
            burstCount = 1,
            lifetime = 0.5f,
            speed = 0f,
            direction = 0f,
            spread = 0f,
            startSize = 0.5f,
            endSize = 5f,
            startColor = 0x88FFFFFF.toInt(),
            endColor = 0x00FFFFFF.toInt(),
            gravity = 0f,
            shape = ParticleEmitter.Shape.RING
        )
    }

    /**
     * Trail - following trail effect
     */
    fun createTrail(): ParticleEmitter.Preset {
        return ParticleEmitter.Preset(
            name = "Trail",
            rate = 30f,
            lifetime = 0.8f,
            speed = 0f,
            direction = 0f,
            spread = 0f,
            startSize = 0.3f,
            endSize = 0f,
            startColor = 0xAAFFFF00.toInt(),
            endColor = 0x00FF8800.toInt(),
            gravity = 0f
        )
    }

    /**
     * Speed lines - motion blur lines
     */
    fun createSpeedLines(): ParticleEmitter.Preset {
        return ParticleEmitter.Preset(
            name = "Speed Lines",
            rate = 50f,
            lifetime = 0.3f,
            speed = 20f,
            direction = 180f,
            spread = 10f,
            startSize = 0.05f,
            endSize = 0.05f,
            startColor = 0x88FFFFFF.toInt(),
            endColor = 0x00FFFFFF.toInt(),
            gravity = 0f,
            shape = ParticleEmitter.Shape.LINE
        )
    }

    /**
     * Confetti - celebration particles
     */
    fun createConfetti(): ParticleEmitter.Preset {
        return ParticleEmitter.Preset(
            name = "Confetti",
            rate = 0f,
            burstCount = 50,
            lifetime = 3f,
            speed = 4f,
            direction = 90f,
            spread = 180f,
            startSize = 0.15f,
            endSize = 0.1f,
            startColor = 0xFFFF0000.toInt(),
            endColor = 0x00FF0000.toInt(),
            gravity = 3f,
            rotationSpeed = 720f,
            randomness = 0.8f
        )
    }

    /**
     * Leaves - falling autumn leaves
     */
    fun createLeaves(): ParticleEmitter.Preset {
        return ParticleEmitter.Preset(
            name = "Leaves",
            rate = 15f,
            lifetime = 4f,
            speed = 1f,
            direction = -60f,
            spread = 30f,
            startSize = 0.2f,
            endSize = 0.2f,
            startColor = 0xFFAA4400.toInt(),
            endColor = 0x00884400.toInt(),
            gravity = 1f,
            turbulence = 1f,
            noise = 1.5f,
            rotationSpeed = 180f
        )
    }

    /**
     * Sand - blowing sand particles
     */
    fun createSand(): ParticleEmitter.Preset {
        return ParticleEmitter.Preset(
            name = "Sand",
            rate = 40f,
            lifetime = 2f,
            speed = 3f,
            direction = 0f,
            spread = 20f,
            startSize = 0.08f,
            endSize = 0.08f,
            startColor = 0xFFCCAA66.toInt(),
            endColor = 0x00AA8844.toInt(),
            gravity = 2f,
            turbulence = 0.5f
        )
    }

    /**
     * Debris - breaking/shattering pieces
     */
    fun createDebris(): ParticleEmitter.Preset {
        return ParticleEmitter.Preset(
            name = "Debris",
            rate = 0f,
            burstCount = 20,
            lifetime = 2f,
            speed = 5f,
            direction = 90f,
            spread = 150f,
            startSize = 0.2f,
            endSize = 0.1f,
            startColor = 0xFF888888.toInt(),
            endColor = 0x00444444.toInt(),
            gravity = 10f,
            rotationSpeed = 360f,
            randomness = 0.7f
        )
    }

    /**
     * Engine exhaust - vehicle exhaust
     */
    fun createEngineExhaust(): ParticleEmitter.Preset {
        return ParticleEmitter.Preset(
            name = "Engine Exhaust",
            rate = 30f,
            lifetime = 1.5f,
            speed = 2f,
            direction = 180f,
            spread = 15f,
            startSize = 0.2f,
            endSize = 0.5f,
            startColor = 0x88888888.toInt(),
            endColor = 0x00444444.toInt(),
            gravity = -0.3f,
            turbulence = 0.3f
        )
    }

    /**
     * Rocket flame - intense rocket exhaust
     */
    fun createRocketFlame(): ParticleEmitter.Preset {
        return ParticleEmitter.Preset(
            name = "Rocket Flame",
            rate = 100f,
            lifetime = 0.5f,
            speed = 8f,
            direction = 180f,
            spread = 10f,
            startSize = 0.3f,
            endSize = 0.1f,
            startColor = 0xFFFFFF00.toInt(),
            endColor = 0x00FF4400.toInt(),
            gravity = 0f,
            turbulence = 0.4f,
            randomness = 0.3f
        )
    }

    /**
     * Hit effect - impact flash
     */
    fun createHitEffect(): ParticleEmitter.Preset {
        return ParticleEmitter.Preset(
            name = "Hit Effect",
            rate = 0f,
            burstCount = 15,
            lifetime = 0.4f,
            speed = 4f,
            direction = 0f,
            spread = 360f,
            startSize = 0.2f,
            endSize = 0f,
            startColor = 0xFFFFFFFF.toInt(),
            endColor = 0x00FFFF00.toInt(),
            gravity = 0f
        )
    }

    /**
     * Muzzle flash - gun muzzle flash
     */
    fun createMuzzleFlash(): ParticleEmitter.Preset {
        return ParticleEmitter.Preset(
            name = "Muzzle Flash",
            rate = 0f,
            burstCount = 8,
            lifetime = 0.15f,
            speed = 10f,
            direction = 0f,
            spread = 30f,
            startSize = 0.4f,
            endSize = 0.1f,
            startColor = 0xFFFFFF88.toInt(),
            endColor = 0x00FF8800.toInt(),
            gravity = 0f,
            shape = ParticleEmitter.Shape.CONE
        )
    }

    /**
     * Ambient particles - subtle background particles
     */
    fun createAmbient(): ParticleEmitter.Preset {
        return ParticleEmitter.Preset(
            name = "Ambient",
            rate = 5f,
            lifetime = 6f,
            speed = 0.2f,
            direction = 90f,
            spread = 360f,
            startSize = 0.1f,
            endSize = 0.15f,
            startColor = 0x22FFFFFF.toInt(),
            endColor = 0x00FFFFFF.toInt(),
            gravity = -0.05f,
            turbulence = 0.5f,
            noise = 0.8f
        )
    }

    /**
     * Get all available presets
     */
    fun getAllPresets(): List<ParticleEmitter.Preset> {
        return listOf(
            createFire(),
            createSmoke(),
            createExplosion(),
            createSparks(),
            createDust(),
            createRain(),
            createSnow(),
            createFog(),
            createSteam(),
            createMagic(),
            createEnergy(),
            createElectricity(),
            createBloodImpact(),
            createWaterSplash(),
            createShockwave(),
            createTrail(),
            createSpeedLines(),
            createConfetti(),
            createLeaves(),
            createSand(),
            createDebris(),
            createEngineExhaust(),
            createRocketFlame(),
            createHitEffect(),
            createMuzzleFlash(),
            createAmbient()
        )
    }
}
