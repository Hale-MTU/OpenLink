// The client side: binds to a host, says hello, keeps the listener registered across
// reconnects, replays what the app asked for (profile, sessions, subscriptions) when the
// host comes back, and exposes the state as flows. The state machine is in
// OpenLinkClientCore, tested on the JVM against a fake connection.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "tech.mmarca.openlink.client"
    compileSdk = 37
    defaultConfig { minSdk = 26 }
    buildTypes {
        create("ci") { initWith(getByName("debug")) }
        create("nightly") { initWith(getByName("release")) }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint { checkReleaseBuilds = false }
}


dependencies {
    api(project(":contract"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit4)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
}
