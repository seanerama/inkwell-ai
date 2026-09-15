// Top-level build file. Plugins are declared here (apply false) and applied per module.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
}

// Dependency locking (ADR-0001): lock every configuration in every project so the
// resolved graph is reproducible. Commit the generated gradle.lockfile(s) via
// `./gradlew dependencies --write-locks` (also on :app with
// `./gradlew :app:dependencies --write-locks`).
allprojects {
    dependencyLocking {
        lockAllConfigurations()
    }
}
