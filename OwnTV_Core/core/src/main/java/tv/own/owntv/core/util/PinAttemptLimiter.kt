package tv.own.owntv.core.util

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** A persisted brute-force bucket. It deliberately contains no PIN or PIN hash. */
internal data class PinAttemptRecord(
    val failures: Int,
    val blockedUntilElapsedMs: Long,
    val blockedUntilWallMs: Long,
    val bootId: Long,
    val savedElapsedMs: Long,
    val savedWallMs: Long,
)

internal interface PinAttemptStore {
    fun read(key: String): PinAttemptRecord?
    fun write(key: String, record: PinAttemptRecord)
    fun remove(key: String)
}

private class MemoryPinAttemptStore : PinAttemptStore {
    private val values = ConcurrentHashMap<String, PinAttemptRecord>()
    override fun read(key: String): PinAttemptRecord? = values[key]
    override fun write(key: String, record: PinAttemptRecord) { values[key] = record }
    override fun remove(key: String) { values.remove(key) }
}

/**
 * Tiny synchronous store for a security decision made synchronously by every PIN prompt.
 * `commit`, rather than `apply`, is intentional: killing the process immediately after a failed
 * attempt must not erase that attempt. Only counters and deadlines are stored, never credentials.
 */
private class AndroidPinAttemptStore(context: Context) : PinAttemptStore {
    private val preferences = context.applicationContext.getSharedPreferences(
        "owntv_pin_attempt_limiter",
        Context.MODE_PRIVATE,
    )

    override fun read(key: String): PinAttemptRecord? = preferences.getString(key, null)?.let { raw ->
        runCatching {
            val value = JSONObject(raw)
            PinAttemptRecord(
                failures = value.getInt("failures"),
                blockedUntilElapsedMs = value.getLong("blockedElapsed"),
                blockedUntilWallMs = value.getLong("blockedWall"),
                bootId = value.getLong("bootId"),
                savedElapsedMs = value.getLong("savedElapsed"),
                savedWallMs = value.getLong("savedWall"),
            )
        }.getOrNull()
    }

    override fun write(key: String, record: PinAttemptRecord) {
        val value = JSONObject()
            .put("failures", record.failures)
            .put("blockedElapsed", record.blockedUntilElapsedMs)
            .put("blockedWall", record.blockedUntilWallMs)
            .put("bootId", record.bootId)
            .put("savedElapsed", record.savedElapsedMs)
            .put("savedWall", record.savedWallMs)
        preferences.edit().putString(key, value.toString()).commit()
    }

    override fun remove(key: String) {
        preferences.edit().remove(key).commit()
    }
}

/**
 * Process- and reboot-safe brute-force guard for every local PIN prompt.
 *
 * Five consecutive failures start a 30-second cooldown. Further failures after each cooldown
 * double the delay up to five minutes; a valid PIN clears the durable state. During one boot the
 * monotonic clock is authoritative, so changing the device date cannot shorten a cooldown. Across
 * a reboot the persisted wall-clock deadline restores the remaining delay; a backwards wall-clock
 * jump is treated conservatively and never clears the lock.
 */
class PinAttemptLimiter internal constructor(
    private val elapsedClockMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val wallClockMs: () -> Long = { System.currentTimeMillis() },
    private val bootId: () -> Long = { 0L },
    private val store: PinAttemptStore = MemoryPinAttemptStore(),
) {
    private data class State(
        var failures: Int = 0,
        var blockedUntilElapsedMs: Long = 0L,
        var blockedUntilWallMs: Long = 0L,
        var bootId: Long = 0L,
        var savedElapsedMs: Long = 0L,
        var savedWallMs: Long = 0L,
    )

    /** Retains the source-compatible single-clock constructor used by callers and existing tests. */
    constructor(clockMs: () -> Long) : this(
        elapsedClockMs = clockMs,
        wallClockMs = clockMs,
        bootId = { 0L },
        store = MemoryPinAttemptStore(),
    )

    private val states = ConcurrentHashMap<String, State>()

    @Synchronized
    fun verify(key: String, check: () -> Boolean): Boolean {
        val nowElapsed = elapsedClockMs()
        val nowWall = wallClockMs()
        val currentBoot = bootId()
        val state = stateFor(key, nowElapsed, nowWall, currentBoot)
        if (remainingDelay(state, nowElapsed, currentBoot) > 0L) return false
        if (check()) {
            states.remove(key)
            store.remove(key)
            return true
        }
        state.failures++
        state.bootId = currentBoot
        state.savedElapsedMs = nowElapsed
        state.savedWallMs = nowWall
        if (state.failures >= MAX_FAILURES) {
            val exponent = (state.failures - MAX_FAILURES).coerceIn(0, MAX_EXPONENT)
            val cooldown = (BASE_COOLDOWN_MS shl exponent).coerceAtMost(MAX_COOLDOWN_MS)
            state.blockedUntilElapsedMs = safeAdd(nowElapsed, cooldown)
            state.blockedUntilWallMs = safeAdd(nowWall, cooldown)
        }
        persist(key, state)
        return false
    }

    @Synchronized
    fun remainingDelayMs(key: String): Long {
        val nowElapsed = elapsedClockMs()
        val nowWall = wallClockMs()
        val currentBoot = bootId()
        val state = stateFor(key, nowElapsed, nowWall, currentBoot)
        return remainingDelay(state, nowElapsed, currentBoot)
    }

    private fun stateFor(key: String, nowElapsed: Long, nowWall: Long, currentBoot: Long): State =
        states.getOrPut(key) {
            store.read(key)?.let { persisted ->
                val state = State(
                    failures = persisted.failures.coerceAtLeast(0),
                    blockedUntilElapsedMs = persisted.blockedUntilElapsedMs,
                    blockedUntilWallMs = persisted.blockedUntilWallMs,
                    bootId = persisted.bootId,
                    savedElapsedMs = persisted.savedElapsedMs,
                    savedWallMs = persisted.savedWallMs,
                )
                if (state.hasActiveDeadline() &&
                    (state.bootId != currentBoot || nowElapsed < state.savedElapsedMs)
                ) {
                    // A wall clock can be moved forward as easily as backward. After a reboot (or
                    // an OEM monotonic reset), never let that untrusted clock shorten the lock:
                    // restart at most the originally scheduled cooldown on the new monotonic clock.
                    // Five minutes is the absolute cap, so this conservative recovery remains usable.
                    val originalDelay = (state.blockedUntilElapsedMs - state.savedElapsedMs)
                        .coerceIn(BASE_COOLDOWN_MS, MAX_COOLDOWN_MS)
                    state.bootId = currentBoot
                    state.savedElapsedMs = nowElapsed
                    state.savedWallMs = nowWall
                    state.blockedUntilElapsedMs = safeAdd(nowElapsed, originalDelay)
                    state.blockedUntilWallMs = safeAdd(nowWall, originalDelay)
                    persist(key, state)
                }
                state
            } ?: State(bootId = currentBoot, savedElapsedMs = nowElapsed, savedWallMs = nowWall)
        }

    private fun State.hasActiveDeadline(): Boolean =
        blockedUntilElapsedMs > savedElapsedMs || blockedUntilWallMs > savedWallMs

    private fun remainingDelay(state: State, nowElapsed: Long, currentBoot: Long): Long {
        if (state.blockedUntilElapsedMs <= 0L && state.blockedUntilWallMs <= 0L) return 0L
        val remaining = if (state.bootId == currentBoot && nowElapsed >= state.savedElapsedMs) {
            // Same boot: elapsedRealtime/System.nanoTime is immune to manual date changes.
            state.blockedUntilElapsedMs - nowElapsed
        } else {
            // stateFor() normally re-anchors this case. Keep the conservative value here as a
            // defence if a custom store or clock changes between the two calls.
            MAX_COOLDOWN_MS
        }
        return remaining.coerceIn(0L, MAX_COOLDOWN_MS)
    }

    private fun persist(key: String, state: State) {
        store.write(
            key,
            PinAttemptRecord(
                failures = state.failures,
                blockedUntilElapsedMs = state.blockedUntilElapsedMs,
                blockedUntilWallMs = state.blockedUntilWallMs,
                bootId = state.bootId,
                savedElapsedMs = state.savedElapsedMs,
                savedWallMs = state.savedWallMs,
            ),
        )
    }

    private fun safeAdd(value: Long, increment: Long): Long =
        if (value > Long.MAX_VALUE - increment) Long.MAX_VALUE else value + increment

    companion object {
        const val MAX_FAILURES = 5
        const val BASE_COOLDOWN_MS = 30_000L
        const val MAX_COOLDOWN_MS = 300_000L
        private const val MAX_EXPONENT = 4
    }
}

/** One durable guard shared by TV and touch shells, settings dialogs and Core revalidation. */
object PinAttemptGuard {
    @Volatile
    private var limiter = PinAttemptLimiter()

    /** Must run once from each host Application before any profile UI can ask for a PIN. */
    fun initialize(context: Context) {
        val appContext = context.applicationContext
        // Resolve once for this process. Reading a clock-derived fallback on every verification
        // would make changing the device date look like a reboot on affected Fire/OEM builds.
        val currentBootId = resolveBootId(appContext)
        limiter = PinAttemptLimiter(
            elapsedClockMs = { SystemClock.elapsedRealtime() },
            wallClockMs = { System.currentTimeMillis() },
            bootId = { currentBootId },
            store = AndroidPinAttemptStore(appContext),
        )
    }

    private fun resolveBootId(context: Context): Long = runCatching {
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT).toLong()
    }.getOrElse {
        // Linux exposes a random UUID that is constant for one boot. It is available on the Android
        // and Fire OS kernels we support and avoids trusting the user-adjustable wall clock.
        val token = runCatching { java.io.File("/proc/sys/kernel/random/boot_id").readText().trim() }
            .getOrNull()
            .takeUnless { it.isNullOrBlank() }
            ?: "${System.currentTimeMillis() - SystemClock.elapsedRealtime()}"
        token.fold(0xcbf29ce484222325UL.toLong()) { hash, char ->
            (hash xor char.code.toLong()) * 0x100000001b3UL.toLong()
        }
    }

    fun verify(key: String, check: () -> Boolean): Boolean = limiter.verify(key, check)
    fun remainingDelayMs(key: String): Long = limiter.remainingDelayMs(key)
}
