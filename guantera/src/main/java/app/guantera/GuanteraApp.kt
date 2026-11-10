package app.guantera

import android.app.Application

class GuanteraApp : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}
