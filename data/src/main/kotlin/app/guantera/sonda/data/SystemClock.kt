package app.guantera.sonda.data

import app.guantera.sonda.domain.port.Clock

class SystemClock : Clock {
    override fun nowMillis() = System.currentTimeMillis()
    override fun elapsedRealtimeMillis() = android.os.SystemClock.elapsedRealtime()
    override fun uptimeMillis() = android.os.SystemClock.uptimeMillis()
}
