package com.sengine.engine.core

/**
 * Object pooling system for high-performance object reuse.
 * Eliminates garbage collection pressure by recycling GameObject instances.
 */
class ObjectPool(private val scene: Scene) {
    private val pools = HashMap<String, Pool>()
    private var nextPoolId = 0L

    /**
     * Register a pool for a template object.
     * @param templateName Name of the template GameObject in the scene
     * @param initialSize Number of objects to pre-create
     * @param maxSize Maximum pool size (0 = unlimited)
     */
    fun register(templateName: String, initialSize: Int = 10, maxSize: Int = 0): Pool {
        val template = scene.find(templateName) ?: throw IllegalArgumentException("Template '$templateName' not found")
        val pool = Pool(templateName, scene, maxSize)
        pool.template = template
        // Pre-warm
        for (i in 0 until initialSize) {
            val obj = createFromTemplate(template)
            obj.active = false
            pool.available.add(obj)
        }
        pools[templateName] = pool
        return pool
    }

    /** Get or create a pool. */
    fun getPool(templateName: String): Pool? = pools[templateName]

    /** Obtain an object from the pool (or create one if pool is empty). */
    fun obtain(templateName: String): GameObject? {
        val pool = pools[templateName]
        if (pool != null) {
            val obj = pool.obtain()
            if (obj != null) return obj
        }
        // No pool or pool empty, create from template
        val template = scene.find(templateName) ?: return null
        return createFromTemplate(template)
    }

    /** Return an object to its pool. */
    fun release(go: GameObject) {
        go.active = false
        // Reset components
        for (c in go.components) c.resetRuntime()
        // Find the pool
        for ((_, pool) in pools) {
            if (pool.release(go)) return
        }
        // No matching pool, just deactivate
    }

    /** Release all objects in all pools. */
    fun releaseAll() {
        for ((_, pool) in pools) pool.releaseAll()
    }

    /** Get total statistics. */
    fun stats(): PoolStats {
        var totalActive = 0
        var totalAvailable = 0
        var totalCreated = 0
        for ((_, pool) in pools) {
            totalActive += pool.activeCount
            totalAvailable += pool.availableCount
            totalCreated += pool.totalCreated
        }
        return PoolStats(pools.size, totalActive, totalAvailable, totalCreated)
    }

    private fun createFromTemplate(template: GameObject): GameObject {
        val copy = scene.duplicate(template, null)
        copy.active = false
        return copy
    }

    data class PoolStats(val poolCount: Int, val activeObjects: Int, val availableObjects: Int, val totalCreated: Int)
}

/**
 * A pool for a single type of object.
 */
class Pool(val templateName: String, val scene: Scene, val maxSize: Int = 0) {
    var template: GameObject? = null
    val available = ArrayDeque<GameObject>()
    val active = HashSet<GameObject>()
    var totalCreated = 0

    /** Get an object from the pool. Returns null if pool is empty and at max size. */
    fun obtain(): GameObject? {
        val obj = available.removeFirstOrNull()
        if (obj != null) {
            obj.active = true
            active.add(obj)
            return obj
        }
        if (maxSize > 0 && active.size >= maxSize) return null
        // Create new
        val tmpl = template ?: return null
        val copy = scene.duplicate(tmpl, null)
        copy.active = true
        active.add(copy)
        totalCreated++
        return copy
    }

    /** Return an object to the pool. */
    fun release(go: GameObject): Boolean {
        if (go !in active) return false
        active.remove(go)
        go.active = false
        for (c in go.components) c.resetRuntime()
        available.addLast(go)
        return true
    }

    /** Release all active objects. */
    fun releaseAll() {
        for (go in active.toList()) {
            go.active = false
            for (c in go.components) c.resetRuntime()
            available.addLast(go)
        }
        active.clear()
    }

    /** Shrink the pool by removing excess available objects. */
    fun shrink(keepCount: Int) {
        while (available.size > keepCount) {
            val go = available.removeLastOrNull() ?: break
            go.destroyed = true
            scene.remove(go)
        }
    }

    val activeCount: Int get() = active.size
    val availableCount: Int get() = available.size
}

/**
 * Generic typed pool for non-GameObject objects (e.g., particles, projectiles data).
 */
class TypedPool<T>(val factory: () -> T, val reset: (T) -> Unit = {}, val maxSize: Int = 0) {
    private val pool = ArrayDeque<T>()
    private var totalCreated = 0

    fun obtain(): T {
        val obj = pool.removeFirstOrNull()
        if (obj != null) return obj
        totalCreated++
        return factory()
    }

    fun release(obj: T) {
        reset(obj)
        if (maxSize <= 0 || pool.size < maxSize) {
            pool.addLast(obj)
        }
    }

    fun prewarm(count: Int) {
        for (i in 0 until count) {
            pool.addLast(factory())
            totalCreated++
        }
    }

    val size: Int get() = pool.size
    val created: Int get() = totalCreated
}
