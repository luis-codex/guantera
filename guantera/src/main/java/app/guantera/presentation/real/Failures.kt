package app.guantera.presentation.real

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Turns a failure of the data layer into something the caller can answer with.
 *
 * A [CancellationException] only means "stop" when the coroutine asking was really cancelled. One
 * that comes out of a call while we are still active (a closed Room, for one) is a failure, and
 * rethrowing it would end `scope.launch` without a word: no result, no error, nothing on the log.
 */
internal suspend fun reportUnlessCancelled(where: String, error: Exception) {
    if (error is CancellationException) currentCoroutineContext().ensureActive()
    logFailure(where, error)
}

/** Unit tests run against the stub android.jar, where Log throws: a log line must never be the failure. */
internal fun logFailure(where: String, error: Throwable) {
    try {
        Log.e("Guantera", "Fallo en $where", error)
    } catch (_: RuntimeException) {
    }
}
