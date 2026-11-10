plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

android {
    namespace = "app.guantera.sonda"
    compileSdk = 36
    defaultConfig {
        applicationId = "app.guantera.sonda"
        minSdk = 24
        targetSdk = 28
        versionCode = 1
        versionName = "1.0"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    signingConfigs { getByName("debug") { storeFile = rootProject.file("keystore/guantera-debug.keystore"); storePassword = "android"; keyAlias = "androiddebugkey"; keyPassword = "android" } }
}
dependencies {
    implementation(project(":domain"))
    implementation(project(":data"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
}

val copyDebugApk by tasks.registering(Copy::class) {
    from(layout.buildDirectory.file("outputs/apk/debug/app-debug.apk"))
    into(rootProject.layout.projectDirectory.dir("dist"))
    rename { "guantera-sonda-debug.apk" }
}
tasks.matching { it.name == "assembleDebug" }.configureEach { finalizedBy(copyDebugApk) }
