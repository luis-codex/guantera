package app.guantera

import android.os.SystemClock
import app.guantera.sonda.domain.port.Clock

object DeviceClock : Clock {
    override fun nowMillis(): Long = System.currentTimeMillis()
    override fun elapsedRealtimeMillis(): Long = SystemClock.elapsedRealtime()
    override fun uptimeMillis(): Long = SystemClock.uptimeMillis()
}
