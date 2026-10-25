import java.net.URI
import java.security.MessageDigest
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// The official ZeroClaw release for Android, packaged as a native library so
// Android extracts it executable into the app's native library dir.
val zeroclawVersion = "v0.8.5"
val zeroclawSha256 = "5664f9c9161cb5946a5f852c72620d2f87d6fd9cc27b930dea1f8effaf91b04b"
// The local model's engine (ADR-0025): llama.cpp's server, MIT.
val llamaVersion = "b11381"
val llamaSha256 = "457554cd1901b88c734d0be75c90fff8d56faab853888cac26435448cb1f09d0"

// Model settings come from local.properties (never committed).
val local = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
fun localString(key: String, default: String = "") =
    "\"" + local.getProperty(key, default).replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "org.rustlanglatam.zeroclaw.android"
    compileSdk = 35
    // Strips the native libraries (llama.cpp's come with their debug symbols: 245 MB, 26 MB stripped).
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "org.rustlanglatam.zeroclaw.android"
        // MessagingStyle.Message.getMessagesFromBundleArray (the notification channel) is API 30.
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += "arm64-v8a" }
        // Developer defaults from local.properties. The owner's own values, entered in Connectors and kept encrypted on
        // the phone, take precedence (Keys.kt, ADR-0030). Release builds compile no keys: see buildTypes.release.
        buildConfigField("String", "MODEL_PROVIDER", localString("zeroclaw.provider"))
        buildConfigField("String", "MODEL", localString("zeroclaw.model"))
        buildConfigField("String", "MODEL_API_KEY", localString("zeroclaw.apiKey"))
        // The model's price, US$ per million tokens (optional): ZeroClaw counts the cost only with a rate.
        buildConfigField("String", "MODEL_INPUT_PER_MTOK", localString("zeroclaw.inputPerMTok"))
        buildConfigField("String", "MODEL_OUTPUT_PER_MTOK", localString("zeroclaw.outputPerMTok"))
        // A model that can see pictures (optional), for the photos the main model cannot see: provider, model and key.
        buildConfigField("String", "VISION_PROVIDER", localString("zeroclaw.vision.provider"))
        buildConfigField("String", "VISION_MODEL", localString("zeroclaw.vision.model"))
        buildConfigField("String", "VISION_API_KEY", localString("zeroclaw.vision.apiKey"))
        // ZeroClaw's web search (optional): DuckDuckGo needs no key but blocks searches it takes for a robot.
        buildConfigField("String", "SEARCH_PROVIDER", localString("zeroclaw.search", "duckduckgo"))
        buildConfigField("String", "SEARCH_KEY", localString("zeroclaw.searchKey"))
        // YouTube Data API key (optional): video results as options in cards; without it, the search opens in the YouTube app.
        buildConfigField("String", "YOUTUBE_KEY", localString("youtube.apiKey"))
        // Cloud voice (optional, ADR-0027): a realtime speech model's WebSocket URL, model, key, voice name and US$ per minute.
        buildConfigField("String", "VOICE_URL", localString("voice.url"))
        buildConfigField("String", "VOICE_MODEL", localString("voice.model"))
        buildConfigField("String", "VOICE_API_KEY", localString("voice.apiKey"))
        buildConfigField("String", "VOICE_NAME", localString("voice.name"))
        buildConfigField("String", "VOICE_PER_MINUTE", localString("voice.perMinute"))
        // The local model for answers without internet (optional): a GGUF file's display name, URL, SHA-256 and size.
        buildConfigField("String", "OFFLINE_MODEL_NAME", localString("offline.model.name"))
        buildConfigField("String", "OFFLINE_MODEL_URL", localString("offline.model.url"))
        buildConfigField("String", "OFFLINE_MODEL_SHA256", localString("offline.model.sha256"))
        buildConfigField("String", "OFFLINE_MODEL_BYTES", localString("offline.model.bytes"))
    }

    buildFeatures { buildConfig = true; compose = true }

    // A release for a real phone: signed with the local key (`release.*` in local.properties), shrunk and optimized.
    signingConfigs {
        create("release") {
            local.getProperty("release.storeFile")?.let { storeFile = file(it) }
            storePassword = local.getProperty("release.storePassword")
            keyAlias = local.getProperty("release.keyAlias")
            keyPassword = local.getProperty("release.keyPassword")
        }
    }
    buildTypes {
        release {
            // No key ships inside a release APK: the owner enters theirs in Connectors (ADR-0030).
            listOf("MODEL_API_KEY", "VISION_API_KEY", "SEARCH_KEY", "YOUTUBE_KEY", "VOICE_API_KEY").forEach { buildConfigField("String", it, "\"\"") }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (local.getProperty("release.storeFile") != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    // Extracted on install, so the binary can be executed from nativeLibraryDir.
    packaging {
        jniLibs { useLegacyPackaging = true }
        // The mail library's two jars carry the same license notes.
        resources { pickFirsts += setOf("META-INF/NOTICE.md", "META-INF/LICENSE.md") }
    }
}

// Unit tests assume the default language, Spanish: the assistant follows the device language, here the JVM's.
tasks.withType<Test>().configureEach {
    systemProperty("user.language", "es")
    // String resources read from the XML files: unit tests run on the JVM without Android's Resources.
    systemProperty("strings.dir", file("src/main/res").absolutePath)
    systemProperty("lexicon.file", file("src/main/assets/lexicon.json").absolutePath)
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // The owner's email accounts (Gmail, Yahoo, iCloud, any IMAP/SMTP): IMAP and SMTP with an app password (EmailAccounts.kt, PhoneEmail.kt).
    implementation("com.sun.mail:android-mail:1.6.7")
    implementation("com.sun.mail:android-activation:1.6.7")
    // The gateway's HTTP and chat API (pairing, health, /ws/chat).
    implementation("io.github.rustlanglatam:zeroclaw-api-client:0.1.2")
    implementation("androidx.security:security-crypto:1.1.0")
    implementation(platform("androidx.compose:compose-bom:2025.06.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.10.1")
    // On-device photo search (PhoneGallery): labels and faces, models bundled, nothing leaves the phone.
    implementation("com.google.mlkit:image-labeling:17.0.9")
    implementation("com.google.mlkit:face-detection:16.1.7")
    // Text in shared pictures and PDFs, recognized on the device (Latin script, bundled model).
    implementation("com.google.mlkit:text-recognition:16.0.1")
    testImplementation("junit:junit:4.13.2")
    // Android's org.json is a stub in JVM tests.
    testImplementation("org.json:json:20240303")
}

val fetchZeroclaw by tasks.registering {
    val archive = rootProject.layout.projectDirectory.file(".zeroclaw/zeroclaw-$zeroclawVersion-android.tar.gz").asFile
    val target = layout.projectDirectory.file("src/main/jniLibs/arm64-v8a/libzeroclaw.so").asFile
    outputs.file(target)
    doLast {
        if (!archive.exists()) {
            archive.parentFile.mkdirs()
            val url = "https://github.com/zeroclaw-labs/zeroclaw/releases/download/$zeroclawVersion/zeroclaw-aarch64-linux-android.tar.gz"
            URI(url).toURL().openStream().use { input -> archive.outputStream().use { input.copyTo(it) } }
        }
        val sha = MessageDigest.getInstance("SHA-256").digest(archive.readBytes()).joinToString("") { "%02x".format(it) }
        check(sha == zeroclawSha256) { "ZeroClaw archive checksum mismatch: $sha" }
        target.parentFile.mkdirs()
        copy {
            from(tarTree(resources.gzip(archive))) { include("zeroclaw") }
            into(target.parentFile)
            rename { target.name }
        }
    }
}

// llama.cpp's server for Android: the executable as a library (an app can run binaries only from its native library
// folder), with its libraries; each finds the others beside it (RUNPATH $ORIGIN: Android's linker under ARM
// translation ignores LD_LIBRARY_PATH). Needs patchelf.
val fetchLlama by tasks.registering {
    val archive = rootProject.layout.projectDirectory.file(".zeroclaw/llama-$llamaVersion-android-arm64.tar.gz").asFile
    val dir = layout.projectDirectory.dir("src/main/jniLibs/arm64-v8a").asFile
    outputs.file(File(dir, "libllama-server.so"))
    doLast {
        if (!archive.exists()) {
            archive.parentFile.mkdirs()
            val url = "https://github.com/ggml-org/llama.cpp/releases/download/$llamaVersion/llama-$llamaVersion-bin-android-arm64.tar.gz"
            URI(url).toURL().openStream().use { input -> archive.outputStream().use { input.copyTo(it) } }
        }
        val sha = MessageDigest.getInstance("SHA-256").digest(archive.readBytes()).joinToString("") { "%02x".format(it) }
        check(sha == llamaSha256) { "llama.cpp archive checksum mismatch: $sha" }
        dir.mkdirs()
        val wanted = listOf("llama-server", "libllama-server-impl.so", "libllama-common.so", "libllama.so", "libmtmd.so", "libggml.so", "libggml-base.so")
        copy {
            from(tarTree(resources.gzip(archive))) {
                include(wanted.map { "*/$it" } + "*/libggml-cpu-android_*.so")
                eachFile { path = name }
                includeEmptyDirs = false
            }
            into(dir)
            rename { if (it == "llama-server") "libllama-server.so" else it }
        }
        val patchelf = listOf(System.getenv("HOME") + "/.local/bin/patchelf", "/usr/bin/patchelf", "/usr/local/bin/patchelf").firstOrNull { File(it).canExecute() }
            ?: error("patchelf is needed to build (pip install patchelf, or your system's package)")
        dir.listFiles { f -> f.name.startsWith("libllama") || f.name.startsWith("libggml") || f.name == "libmtmd.so" }!!.forEach {
            providers.exec { commandLine(patchelf, "--set-rpath", "\$ORIGIN", it.path) }.result.get()
        }
    }
}
tasks.named("preBuild") { dependsOn(fetchZeroclaw, fetchLlama) }
