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
            // Kill-switch (Stage 6 dark-launch flag): the Ping button is gated by
            // BuildConfig.PING_ENABLED. ON in debug, OFF in release until Stage 6.
            buildConfigField("boolean", "PING_ENABLED", "true")
        }
        getByName("release") {
            buildConfigField("boolean", "PING_ENABLED", "false")
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

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.serialization.json)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
