import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Personal-build convenience: bake an API key into the APK so it never has to be
// typed on the device. Set `inkling.apiKey=sk-ant-...` in local.properties (gitignored)
// or export INKLING_API_KEY before building. Anyone holding the APK can extract the
// key — only do this for builds that stay on your own device.
val builtInApiKey: String = run {
    val props = Properties()
    val local = rootProject.file("local.properties")
    if (local.exists()) local.inputStream().use { props.load(it) }
    props.getProperty("inkling.apiKey") ?: System.getenv("INKLING_API_KEY") ?: ""
}

android {
    namespace = "com.ommahida.inkling"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.ommahida.inkling"
        // Supernote A5X / A6X run Android 8.1 (API 27); Nomad and Manta run Android 11 (API 30).
        minSdk = 27
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        buildConfigField("String", "BUILT_IN_API_KEY", "\"$builtInApiKey\"")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/DEPENDENCIES",
            "META-INF/LICENSE*",
            "META-INF/NOTICE*",
            "META-INF/*.kotlin_module",
        )
    }
}

// The reply typeface (Dancing Script, SIL OFL 1.1 — see ../licenses/) is fetched at
// build time so the repo carries no font binaries.
val replyFont = layout.projectDirectory.file("src/main/res/font/dancing_script.ttf").asFile
val downloadReplyFont by tasks.registering {
    outputs.file(replyFont)
    onlyIf { !replyFont.exists() }
    doLast {
        replyFont.parentFile.mkdirs()
        uri("https://raw.githubusercontent.com/google/fonts/main/ofl/dancingscript/DancingScript%5Bwght%5D.ttf")
            .toURL().openStream().use { input ->
                replyFont.outputStream().use { input.copyTo(it) }
            }
    }
}
tasks.named("preBuild") { dependsOn(downloadReplyFont) }

dependencies {
    implementation("com.anthropic:anthropic-java:2.34.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
