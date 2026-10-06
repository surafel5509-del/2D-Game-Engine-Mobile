package com.sengine.engine.core

import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.coroutines.startCoroutine

/**
 * Lightweight cooperative task scheduler built on Kotlin's standard coroutine intrinsics.
 *
 * No external dependency is required: `task { ... }` runs a suspending block that can wait for
 * time, frames, conditions or other tasks. Gameplay code (and the visual scripting API) uses it
 * for delays, tweens and sequenced effects - the 2D equivalent of "co-routines" in other engines.
 */
class Task internal constructor(
    internal val name: String,
    private val scheduler: TaskScheduler
) {
    internal var continuation: Continuation<Unit>? = null
    internal var waitTime = 0f
    internal var waitFrames = 0
    internal var waitCondition: (() -> Boolean)? = null
    internal var waitingOnTask: Task? = null
    internal var pendingStart = true
    internal var finished = false
    internal var cancelled = false
    internal var exception: Throwable? = null

    val isDone: Boolean get() = finished || cancelled
    val isCancelled: Boolean get() = cancelled

    fun cancel() {
        cancelled = true
        continuation = null
        waitCondition = null
        waitingOnTask = null
    }

    internal fun complete() {
        finished = true
        continuation = null
    }
}

/**
 * Scheduler that drives every [Task]. One instance lives in the [Engine].
 * Deterministic and allocation-light: waiting tasks are only touched when their wait expires.
 */
class TaskScheduler {

    private val tasks = ArrayList<Task>(32)
    private val pendingAdd = ArrayList<Task>(8)
    private var currentTime = 0f

    /** Number of running tasks (profiler). */
    val activeCount: Int get() = tasks.size
    var totalStarted = 0L
        private set
    var totalCompleted = 0L
        private set

    /** Scope handed to task blocks. */
    inner class Scope {
        /** Suspends for [seconds] of game time (affected by time scale). */
        suspend fun delay(seconds: Float) = suspendCoroutine { cont ->
            val t = currentTask()
            t.waitTime = seconds.coerceAtLeast(0f)
            t.continuation = cont
        }

        /** Suspends until the next frame. */
        suspend fun yield() = suspendCoroutine { cont ->
            val t = currentTask()
            t.waitFrames = 1
            t.continuation = cont
        }

        /** Suspends for [count] frames. */
        suspend fun frames(count: Int) = suspendCoroutine { cont ->
            val t = currentTask()
            t.waitFrames = count.coerceAtLeast(1)
            t.continuation = cont
        }

        /** Suspends until [predicate] returns true (checked every frame). */
        suspend fun waitUntil(predicate: () -> Boolean) {
            if (predicate()) return
            suspendCoroutine { cont ->
                val t = currentTask()
                t.waitCondition = predicate
                t.continuation = cont
            }
        }

        /** Waits for another task to finish. */
        suspend fun waitFor(other: Task) {
            if (other.isDone) return
            suspendCoroutine { cont ->
                val t = currentTask()
                t.waitingOnTask = other
                t.continuation = cont
            }
        }
    }

    private var running: Task? = null
    private val scope = Scope()

    private fun currentTask(): Task = running ?: error("Task suspension used outside of a task block")

    /** Starts a task. The block runs immediately until its first suspension point. */
    fun launch(name: String = "task", block: suspend Scope.() -> Unit): Task {
        val task = Task(name, this)
        totalStarted++
        val previous = running
        running = task
        try {
            block.startCoroutine(scope, object : Continuation<Unit> {
                override val context = kotlin.coroutines.EmptyCoroutineContext
                override fun resumeWith(result: Result<Unit>) {
                    result.exceptionOrNull()?.let {
                        task.exception = it
                        failed.add(task)
                    }
                    task.complete()
                    totalCompleted++
                }
            })
        } catch (t: Throwable) {
            task.exception = t
            failed.add(task)
            task.complete()
        } finally {
            running = previous
        }
        if (!task.isDone && task.continuation != null) pendingAdd.add(task)
        return task
    }

    /** Advances every waiting task. Called once per frame from the engine loop. */
    fun update(dt: Float) {
        currentTime += dt
        if (pendingAdd.isNotEmpty()) {
            for (t in pendingAdd) if (!t.isDone && t.continuation != null) tasks.add(t)
            pendingAdd.clear()
        }
        var i = 0
        while (i < tasks.size) {
            val t = tasks[i]
            if (t.isDone || t.cancelled) {
                tasks.removeAt(i)
                continue
            }
            var ready = false
            if (t.waitingOnTask?.isDone == true) ready = true
            if (t.waitTime > 0f) {
                t.waitTime -= dt
                if (t.waitTime <= 0f) { t.waitTime = 0f; ready = true }
            }
            if (t.waitFrames > 0) {
                t.waitFrames--
                if (t.waitFrames <= 0) ready = true
            }
            t.waitCondition?.let { if (it()) { t.waitCondition = null; ready = true } }
            val cont = t.continuation
            if (ready && cont != null) {
                t.continuation = null
                t.waitingOnTask = null
                t.waitCondition = null
                val previous = running
                running = t
                try {
                    cont.resume(Unit)
                } catch (ex: Throwable) {
                    t.exception = ex
                    t.complete()
                    totalCompleted++
                } finally {
                    running = previous
                }
            }
            i++
        }
    }

    fun cancelAll() {
        for (t in tasks) t.cancel()
        tasks.clear()
        pendingAdd.clear()
        running = null
    }

    /** Tasks that ended with an exception (surfaced in the console/profiler). */
    val failed = ArrayList<Task>()

    fun clearFailures() = failed.clear()
}
