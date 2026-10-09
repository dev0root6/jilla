plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("plugin.compose")
}

// Authority (backend + poles) X25519 public keys are bundled at build time.
// backend/main.py writes backend/keys/authority.pub on first start; pole keys go in keys/poles/*.pub.
val authorityKeys: String = run {
    val files = listOf("backend/keys", "keys/poles")
        .flatMap { rootProject.file(it).listFiles()?.toList() ?: emptyList() }
        .filter { it.name.endsWith(".pub") }
        .sortedBy { it.name }
    if (files.isEmpty()) {
        throw GradleException("No authority keys found. Start the backend once (it writes backend/keys/authority.pub).")
    }
    files.joinToString(",") { it.readText().trim() }
}

android {
    namespace = "com.jilla"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.jilla"
        minSdk = 26
        targetSdk = 35
        versionCode = 6
        versionName = "0.3.3"
        buildConfigField("String", "AUTHORITY_KEYS", "\"$authorityKeys\"")
        buildConfigField("String", "DEFAULT_BACKEND_URL", "\"http://127.0.0.1:8000\"")
        buildConfigField("String", "DEFAULT_MESH_HOST", "\"127.0.0.1\"")
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += setOf("META-INF/versions/9/OSGI-INF/MANIFEST.MF", "META-INF/*.kotlin_module")
    }
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":core"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("com.google.android.gms:play-services-auth:21.2.0") // Phone Number Hint API
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    debugImplementation("androidx.compose.ui:ui-tooling")
}

// Copy each built APK to <repo>/apk/jilla-v<versionName>-build<versionCode>-<buildType>.apk
androidComponents {
    onVariants { variant ->
        val out = variant.outputs.single()
        val copy = tasks.register<Copy>("copy${variant.name.replaceFirstChar { it.uppercase() }}ApkToRepo") {
            from(variant.artifacts.get(com.android.build.api.artifact.SingleArtifact.APK)) { include("*.apk") }
            into(rootProject.layout.projectDirectory.dir("apk"))
            rename { "jilla-v${out.versionName.get()}-build${out.versionCode.get()}-${variant.buildType}.apk" }
        }
        afterEvaluate { tasks.named("assemble${variant.name.replaceFirstChar { it.uppercase() }}") { finalizedBy(copy) } }
    }
}
