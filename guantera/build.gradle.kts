plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

android {
    namespace = "app.guantera"
    compileSdk = 36
    defaultConfig {
        applicationId = "app.guantera"
        minSdk = 24
        targetSdk = 28
        versionCode = 1
        versionName = "0.1"
    }
    buildFeatures { viewBinding = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    signingConfigs { getByName("debug") { storeFile = rootProject.file("keystore/guantera-debug.keystore"); storePassword = "android"; keyAlias = "androiddebugkey"; keyPassword = "android" } }
}

dependencies {
    implementation(project(":domain"))
    implementation(project(":storage"))
    implementation(project(":media"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("io.nayuki:qrcodegen:1.8.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.google.zxing:core:3.5.3")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}

val copyDebugApk by tasks.registering(Copy::class) {
    from(layout.buildDirectory.file("outputs/apk/debug/guantera-debug.apk"))
    into(rootProject.layout.projectDirectory.dir("dist"))
}
tasks.matching { it.name == "assembleDebug" }.configureEach { finalizedBy(copyDebugApk) }
