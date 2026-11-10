package app.guantera.presentation.common

import app.guantera.sonda.domain.port.Clock
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

private const val MINUTE_MILLIS = 60_000L

/** Emits the current time now and at the start of every following minute. */
fun minuteTicks(clock: Clock): Flow<Long> = flow {
    while (true) {
        val now = clock.nowMillis()
        emit(now)
        delay(MINUTE_MILLIS - now % MINUTE_MILLIS)
    }
}
