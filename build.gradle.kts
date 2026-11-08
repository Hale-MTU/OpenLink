plugins {
    alias(libs.plugins.android.library) apply false
}

allprojects {
    group = "tech.mmarca.openlink"
    version = providers.gradleProperty("openlink.version").orElse("0.1.0-SNAPSHOT").get()
}

// The whole gate in one invocation: unit tests and lint of the three libraries.
tasks.register("verifyCi") {
    group = "verification"
    description = "Runs the unit tests and lint of every module."
    dependsOn(
        ":contract:testDebugUnitTest", ":host:testDebugUnitTest", ":client:testDebugUnitTest",
        ":contract:lintDebug", ":host:lintDebug", ":client:lintDebug",
    )
}
