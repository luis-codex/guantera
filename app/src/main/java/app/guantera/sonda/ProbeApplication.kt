package app.guantera.sonda

import android.app.Application

class ProbeApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.guarded("inicio de proceso") { container.recordEvent.processStarted() }
    }
}

val android.content.Context.container: AppContainer
    get() = (applicationContext as ProbeApplication).container
