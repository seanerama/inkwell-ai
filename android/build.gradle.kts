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
        // LENIENT: every module in gradle.lockfile resolves to exactly its locked
        // version, but a module absent from the lock state does not fail the build.
        // Needed because Gradle resolves `kotlin-stdlib-common` (an empty relocation
        // artifact since Kotlin 1.9.20) unstably in the androidTest runtime classpath —
        // it appears during `lint` but not during `--write-locks` — so STRICT mode
        // fails CI on a module that carries no code. Everything real stays pinned.
        lockMode.set(LockMode.LENIENT)
    }
}
