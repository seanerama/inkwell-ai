import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// --- Release signing (ADR-0005, catalog method gradle-github-releases) ---
// CI decodes the base64 keystore from GitHub Actions secrets into a file and
// exports its path + credentials as env vars (see .github/workflows/release.yml
// and android/keystore.properties.example). When no keystore is present (local
// dev, PR CI, assembleDebug) the release build stays unsigned instead of failing.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        load(FileInputStream(keystorePropertiesFile))
    }
}
fun signingValue(propKey: String, envKey: String): String? =
    keystoreProperties.getProperty(propKey) ?: System.getenv(envKey)

val releaseStoreFilePath = signingValue("storeFile", "INKWELL_KEYSTORE_PATH")
val hasReleaseSigning = releaseStoreFilePath != null && file(releaseStoreFilePath).exists()

android {
    namespace = "com.inkwell"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.inkwell"
        minSdk = 31
        targetSdk = 34
        versionCode = 2
        versionName = "0.0.2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseStoreFilePath!!)
                storePassword = signingValue("storePassword", "INKWELL_KEYSTORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "INKWELL_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "INKWELL_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        getByName("debug") {
            // Ping kill-switch (Stage 2): the Ping button is gated by
            // BuildConfig.PING_ENABLED. Kept ON in debug so the walking-skeleton
            // round-trip stays available for manual checks alongside Send.
            buildConfigField("boolean", "PING_ENABLED", "true")
            // Send kill-switch (Stage 6 dark-launch flag, THIS stage's feature): the
            // canvas.annotate loop (Send button) is gated by BuildConfig.SEND_ENABLED.
            // ON in debug so the loop is testable; OFF in release until the Handoff
            // Tester passes the three-box test and a follow-up PR flips it ON.
            buildConfigField("boolean", "SEND_ENABLED", "true")
            // Ink kill-switch (Stage 3 feature flag): default ON in BOTH build types —
            // the app has no purpose with ink off. When OFF the launch screen is the
            // settings/pairing screen instead of the canvas.
            buildConfigField("boolean", "INK_ENABLED", "true")
            // One-tap ask kill-switch (Stage 7 dark-launch flag, THIS stage's feature):
            // Send posts `canvas.ask` with no instruction; OFF restores the Stage-6
            // sheet-first `canvas.annotate` flow. Documented exception (stage-7 spec,
            // Acceptance): default ON in debug AND release, because prod is not
            // promoted, staging is the only environment, and the server-side
            // AGENT_ENABLED already dark-launches all agent work.
            buildConfigField("boolean", "ONE_TAP_ASK", "true")
            // Full-vocabulary kill-switch (Stage 9 dark-launch flag, THIS stage's
            // feature): ON draws all nine agent-output annotation types natively (arrows
            // with heads, ellipse/rect groupings, strikethrough, path, and margin_notes
            // in a right-hand gutter); OFF falls back to the Stage-7 behaviour where the
            // six non-native types render as labelled boxes. Documented exception (same
            // rationale as ONE_TAP_ASK): default ON in debug AND release, because prod is
            // not promoted, staging is the only environment, and AGENT_ENABLED already
            // dark-launches all agent work. Flip to "false" to restore the labelled boxes
            // without a code change.
            buildConfigField("boolean", "FULL_VOCABULARY", "true")
            // Card-actions kill-switch (Stage 10 dark-launch flag, THIS stage's feature):
            // ON makes cards real objects — action buttons (confirm/reject), device-driven
            // state changes, tappable anchors, Markdown bodies, and the Ask/Mark-up
            // job-type picker. OFF restores the Stage-7 read-only cards (no action
            // buttons, no state changes, no picker). Documented exception (same rationale
            // as ONE_TAP_ASK/FULL_VOCABULARY): default ON in debug AND release, because
            // prod is not promoted, staging is the only environment, and AGENT_ENABLED
            // already dark-launches all agent work. Flip to "false" to restore read-only
            // cards without a code change.
            buildConfigField("boolean", "CARD_ACTIONS", "true")
            // Library kill-switch (Stage 11 dark-launch flag, THIS stage's feature): ON
            // makes the app open on the Library (folders + canvases per space, with
            // create / rename / move / delete-to-Trash / open, and a titled canvas with a
            // back button). OFF restores today's behaviour: open the first canvas directly
            // (MainActivity → CanvasScreen). Documented exception (same rationale as
            // ONE_TAP_ASK/FULL_VOCABULARY/CARD_ACTIONS): default ON in debug AND release,
            // because prod is not promoted, staging is the only environment. Flip to
            // "false" to restore the direct-to-canvas launch without a code change.
            buildConfigField("boolean", "LIBRARY", "true")
        }
        getByName("release") {
            // Stage 6 has landed: Send replaces the walking-skeleton Ping round-trip,
            // so PING_ENABLED goes OFF in release (as scheduled in the Stage-2 spec and
            // PairingScreen's kill-switch note). Pairing "Check" (/health) is ungated
            // and still verifies connectivity in release.
            buildConfigField("boolean", "PING_ENABLED", "false")
            // Send stays dark in release (default OFF) until the Handoff Tester passes
            // the three-box test; a follow-up PR flips this ON.
            // Flipped ON for staging verification by the Release Operator (2026-09-15): the
            // three-box Handoff test runs on the signed release APK; prod is not promoted.
            buildConfigField("boolean", "SEND_ENABLED", "true")
            buildConfigField("boolean", "INK_ENABLED", "true")
            // Stage 7 one-tap ask: ON in release by documented exception (see the debug
            // block and the stage-7 spec). Flip to "false" to fall back to the Stage-6
            // sheet-first annotate flow without a code change.
            buildConfigField("boolean", "ONE_TAP_ASK", "true")
            // Stage 9 full vocabulary: ON in release by documented exception (see the
            // debug block and the stage-9 spec). Flip to "false" to fall back to the
            // Stage-7 labelled boxes for the six non-native types without a code change.
            buildConfigField("boolean", "FULL_VOCABULARY", "true")
            // Stage 10 card actions: ON in release by documented exception (see the debug
            // block and the stage-10 spec). Flip to "false" to fall back to the Stage-7
            // read-only cards (no action buttons, no state changes, no picker).
            buildConfigField("boolean", "CARD_ACTIONS", "true")
            // Stage 11 Library: ON in release by documented exception (see the debug block
            // and the stage-11 spec). Flip to "false" to fall back to the direct-to-canvas
            // launch (open the first canvas) without a code change.
            buildConfigField("boolean", "LIBRARY", "true")
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = libs.versions.composeCompiler.get()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    // The `contracts` source set carries the agent-output Kotlin types (contract
    // agent-output, ADR-0007). Per ADR-0007 the preferred path is generation from
    // contracts/schema/agent-output.v1.schema.json via quicktype into
    // build/generated/contracts; quicktype cannot express the closed anyOf union
    // as a kotlinx-serialization sealed hierarchy (it flattens to one all-optional
    // class), so this stage takes the ADR-0007 fallback: committed hand-written
    // classes in src/contracts/kotlin plus the shared fixture tests. To switch to
    // generation later, repoint this srcDir at "build/generated/contracts/kotlin".
    sourceSets {
        getByName("main") {
            kotlin.srcDir("src/contracts/kotlin")
        }
        // Room's exported schemas as androidTest assets so MigrationTestHelper can load
        // them (Stage 10 v1→v2 migration test). Additive; no effect on the app APK.
        getByName("androidTest") {
            assets.srcDirs("$projectDir/schemas")
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// Export the Room schema for review (contract ink-storage). schemas/ is committed.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

// --- Contract class generation task (ADR-0007) ---
// Runs the pinned quicktype from android/package.json against the frozen schema.
// Kept wired for drift inspection; the app compiles the committed fallback classes
// (see the sourceSets note above). Requires Node; not on the assembleDebug path.
tasks.register<Exec>("generateContracts") {
    group = "contracts"
    description = "Generate agent-output Kotlin types from the frozen JSON Schema via pinned quicktype (ADR-0007)."
    val schema = rootProject.file("../contracts/schema/agent-output.v1.schema.json")
    val outDir = layout.buildDirectory.dir("generated/contracts/kotlin/com/inkwell/contracts/generated")
    inputs.file(schema)
    outputs.dir(outDir)
    doFirst { outDir.get().asFile.mkdirs() }
    workingDir = projectDir.parentFile // android/
    commandLine(
        "npx", "--no-install", "quicktype",
        "--lang", "kotlin",
        "--framework", "kotlinx",
        "--package", "com.inkwell.contracts.generated",
        "--src-lang", "schema",
        "--src", schema.absolutePath,
        "-o", outDir.get().file("AgentOutputGenerated.kt").asFile.absolutePath,
    )
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.androidx.security.crypto)

    implementation(libs.androidx.work.runtime.ktx)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.serialization.json)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
