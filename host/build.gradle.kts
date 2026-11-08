// The host side: identity resolution from the binder, the trust store, capability gating
// and listener bookkeeping. An app extends OpenLinkHostService and implements the
// capabilities it owns. The logic lives in OpenLinkHostCore, which knows no Android
// framework beyond PendingIntent and Parcelable, so it is tested on the JVM.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "tech.mmarca.openlink.host"
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
    testImplementation(libs.junit4)
    testImplementation(libs.truth)
}
