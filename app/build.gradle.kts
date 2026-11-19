import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.OutputDirectory

plugins {
    alias(libs.plugins.android.application)
}

/**
 * Ayse CRM für Android (ab 3.0): Die Oberfläche ist dieselbe Web-App wie im Browser und unter Windows
 * (Ordner web/). Sie wird beim Bauen in die App kopiert; der Kotlin-Teil ergänzt, was ein Browser nicht
 * kann: E-Mail per IMAP/SMTP, Kamera, Dateien öffnen und speichern, PDF, Drucken und Benachrichtigungen.
 */
abstract class WebAppKopieren : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val quelle: DirectoryProperty

    @get:OutputDirectory
    abstract val ziel: DirectoryProperty

    @TaskAction
    fun kopieren() {
        val aus = ziel.get().asFile
        aus.deleteRecursively()
        aus.mkdirs()
        for (name in listOf("index.html", "manifest.webmanifest", "icon-192.png", "icon-512.png")) {
            quelle.get().file(name).asFile.copyTo(aus.resolve(name))
        }
    }
}

val webApp = tasks.register<WebAppKopieren>("webAppKopieren") {
    quelle.set(rootProject.layout.projectDirectory.dir("web"))
}

android {
    namespace = "de.aysetransport.schaden"
    compileSdk = 37

    defaultConfig {
        applicationId = "de.aysetransport.schaden"
        minSdk = 26
        targetSdk = 36
        versionCode = 16
        versionName = "5.1.0"
    }

    // Fester Signaturschlüssel, sofern hinterlegt (Umgebungsvariablen, im Workflow aus GitHub-Secrets).
    // Ohne ihn signiert Gradle mit dem Testschlüssel des jeweiligen Rechners – dann lässt sich eine
    // neue APK nicht über eine mit anderem Schlüssel gebaute installieren.
    val signaturDatei = System.getenv("SIGNATUR_DATEI")?.let { file(it) }?.takeIf { it.isFile }
    val festeSignatur = signaturDatei?.let { datei ->
        signingConfigs.create("fest") {
            storeFile = datei
            storePassword = System.getenv("SIGNATUR_PASSWORT")
            keyAlias = System.getenv("SIGNATUR_ALIAS")
            keyPassword = System.getenv("SIGNATUR_SCHLUESSEL_PASSWORT") ?: System.getenv("SIGNATUR_PASSWORT")
        }
    }

    buildTypes {
        debug {
            if (festeSignatur != null) signingConfig = festeSignatur
        }
        release {
            signingConfig = festeSignatur ?: signingConfigs.getByName("debug")
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            // Lizenz- und Indexdateien der Mail-Bibliotheken doppeln sich
            excludes += setOf("META-INF/LICENSE.md", "META-INF/NOTICE.md", "META-INF/DEPENDENCIES", "META-INF/*.SF")
            pickFirsts += setOf("META-INF/javamail.providers", "META-INF/javamail.default.providers", "META-INF/mailcap", "META-INF/javamail.charset.map", "META-INF/gfprobe-provider.xml")
        }
    }

    lint {
        disable += "OldTargetApi"
        // Die Brücke zur Web-App ist bewusst eine JavaScript-Schnittstelle; sie lädt nur eigene Dateien.
        disable += "SetJavaScriptEnabled"
        abortOnError = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.maxHeapSize = "2g"
                it.jvmArgs("--add-opens=java.base/jdk.internal.access=ALL-UNNAMED")
            }
        }
    }
}

androidComponents {
    onVariants { variante ->
        variante.sources.assets?.addGeneratedSourceDirectory(webApp, WebAppKopieren::ziel)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.webkit)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.jakarta.mail.api)
    implementation(libs.angus.mail)
    implementation(libs.angus.activation)

    testImplementation(libs.junit)
    testImplementation(libs.greenmail)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
