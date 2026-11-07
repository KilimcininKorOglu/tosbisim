import com.google.protobuf.gradle.proto
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.protobuf")
}

// Release signing: host/signing/ holds the key (release.jks + signing.properties, gitignored); each value can also
// come from a HERMESSIM_SIGNING_* environment variable, which wins (CI). Every update must be signed with this key,
// or phones refuse to install it over the previous version. Back it up.
val signingDir = rootProject.file("../signing")
val signingProps = Properties().apply {
    val f = signingDir.resolve("signing.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signingValue(key: String, env: String): String? =
    System.getenv(env)?.takeIf { it.isNotEmpty() } ?: signingProps.getProperty(key)
val releaseStore = signingValue("storeFile", "HERMESSIM_SIGNING_STORE_FILE")

android {
    namespace = "com.keremgok.hermessim.host"
    compileSdk = 36
    signingConfigs {
        create("release") {
            if (releaseStore != null) {
                storeFile = signingDir.resolve(releaseStore)      // relative to host/signing/, or absolute
                storePassword = signingValue("storePassword", "HERMESSIM_SIGNING_STORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "HERMESSIM_SIGNING_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "HERMESSIM_SIGNING_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false      // reflection on system APIs + protobuf-lite: no shrinking
            signingConfig = if (releaseStore != null) signingConfigs.getByName("release") else null
        }
    }

    defaultConfig {
        applicationId = "com.keremgok.hermessim.host"
        minSdk = 30
        targetSdk = 33   // TODO(M6): 34+ needs foregroundServiceType declarations
        versionCode = 63
        versionName = "1.0.0"
    }
    sourceSets["main"].apply {
        // Device profiles (repo-root profiles/) ship as assets, e.g. assets/qcom/sm8250-samsung-r8q.json
        assets.srcDir("$rootDir/../../profiles")
        // The Magisk module (no APK inside) the app installs itself once rooted: host/magisk/pack-embed.sh
        assets.srcDir("src/embed/assets")
        // Shared host<->client protocol (repo-root protocol/, MIT)
        proto { srcDir("$rootDir/../../protocol") }
    }
    // Not for the phone: the support page's own table and its hand-written source.
    // Appended to aapt's default ignore pattern.
    androidResources.ignoreAssetsPattern =
        "!.svn:!.git:!.ds_store:!*.scc:.*:!CVS:!thumbs.db:!picasa.ini:!*~:!devices.json:!support.src.json"
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

protobuf {
    protoc { artifact = "com.google.protobuf:protoc:4.31.1" }
    generateProtoTasks {
        all().forEach { task -> task.builtins { create("java") { option("lite") } } }
    }
}

dependencies {
    implementation("com.google.protobuf:protobuf-javalite:4.31.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")   // remote mode WebSocket (Apache-2.0)
    compileOnly(project(":hidden-stubs"))                    // hidden framework classes, the phone's at run time
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")       // Android's org.json is only a stub in local unit tests
}

// Every permission the app requests is either on the module's privapp allowlist or an ordinary (normal/runtime) one
// reviewed here. A privileged permission missing from the allowlist stops some phones from booting once the module
// installs us as a priv-app (enforce mode): a new library or manifest line must fail the build, not a phone (audit,
// 19 Oct 2026).
val ordinaryPermissions = setOf(
    "ACCESS_NETWORK_STATE", "ACCESS_NOTIFICATION_POLICY", "ACCESS_WIFI_STATE", "BLUETOOTH_ADVERTISE", "BLUETOOTH_CONNECT",
    "CALL_PHONE", "CHANGE_WIFI_STATE", "FOREGROUND_SERVICE", "FOREGROUND_SERVICE_CONNECTED_DEVICE", "INTERNET",
    "POST_NOTIFICATIONS", "READ_PHONE_STATE", "REQUEST_IGNORE_BATTERY_OPTIMIZATIONS",
    "READ_SMS", "RECEIVE_BOOT_COMPLETED", "RECEIVE_MMS", "RECEIVE_SMS", "RECEIVE_WAP_PUSH", "SEND_SMS", "WAKE_LOCK",
    "WRITE_SETTINGS", "BLUETOOTH", "BLUETOOTH_ADMIN",
).map { "android.permission.$it" }.toSet()
tasks.matching { it.name.startsWith("process") && it.name.endsWith("MainManifest") }.configureEach {
    doLast {
        val manifest = outputs.files.asFileTree.matching { include("**/AndroidManifest.xml") }.files.firstOrNull() ?: return@doLast
        val requested = Regex("""uses-permission[^>]*android:name="([^"]+)"""").findAll(manifest.readText()).map { it.groupValues[1] }.toSet()
        val allowlist = rootProject.file("../magisk/template/system/etc/permissions/privapp-permissions-com.keremgok.hermessim.host.xml")
        val privileged = Regex("""permission name="([^"]+)"""").findAll(allowlist.readText()).map { it.groupValues[1] }.toSet()
        val unknown = requested - privileged - ordinaryPermissions
        if (unknown.isNotEmpty()) throw GradleException(
            "Permissions neither in the privapp allowlist nor reviewed as ordinary: $unknown. Add privileged ones to " +
                "${allowlist.name}, ordinary ones to ordinaryPermissions in app/build.gradle.kts.")
    }
}
