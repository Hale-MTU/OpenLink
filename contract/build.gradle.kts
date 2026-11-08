// The contract: the AIDL interfaces, the parcelables they carry and the
// constants both sides agree on. Nothing else, so an app that only wants to
// speak the protocol takes no helper code with it.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "tech.mmarca.openlink.contract"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    // The apps build a ci variant (tests, lint) and a nightly one; a library without them
    // would break their classpath resolution, so the three modules carry both.
    buildTypes {
        create("ci") { initWith(getByName("debug")) }
        create("nightly") { initWith(getByName("release")) }
    }

    buildFeatures {
        aidl = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        checkReleaseBuilds = false
    }
}


dependencies {
    testImplementation(libs.junit4)
    testImplementation(libs.truth)
}
