plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "de.aysetransport.schaden"
    compileSdk = 37

    defaultConfig {
        applicationId = "de.aysetransport.schaden"
        minSdk = 26
        // Bewusst Android 16: Das Laufzeitverhalten dieser Stufe ist getestet. Auf neueren
        // Geräten läuft die App im Kompatibilitätsmodus unverändert weiter.
        targetSdk = 36
        versionCode = 2
        versionName = "1.1.0"

        // Vorgabe in den Einstellungen; die PIN wird beim Verbinden abgefragt.
        buildConfigField("String", "CLOUD_URL", "\"https://script.google.com/macros/s/AKfycbw_wOlZk2pXCaHAVOOCjwhDnWP7p9XeoIz8NisZR7NApIj-Xfgd_lP5j8KjPE11OFbQuA/exec\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        // Siehe Kommentar an targetSdk.
        disable += "OldTargetApi"
    }

    sourceSets {
        // Für den Migrationstest: die exportierten Datenbankschemata (Robolectric liest die Assets der Debug-Variante)
        getByName("debug").assets.directories.add("$projectDir/schemas")
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                // Die Abgleich-Tests laufen gegen das Cloud-Skript – Änderungen daran sollen sie neu auslösen.
                it.inputs.dir(rootProject.file("cloud")).withPropertyName("cloudSkript")
                // Bildschirmfotos der Oberflächentests landen in app/build/outputs/roborazzi
                it.systemProperty("roborazzi.test.record", "true")
                it.maxHeapSize = "2g"
                // Robolectric greift für Android 16 auf interne JDK-Klassen zu.
                it.jvmArgs("--add-opens=java.base/jdk.internal.access=ALL-UNNAMED")
            }
        }
    }
}

kotlin {
    compilerOptions {
        optIn.addAll(
            "androidx.compose.material3.ExperimentalMaterial3Api",
            "androidx.compose.foundation.layout.ExperimentalLayoutApi",
        )
    }
}

ksp {
    // Datenbankschema versionieren, damit spätere Migrationen geprüft werden können
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.coil.compose)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.androidx.work.testing)
    testImplementation(libs.androidx.room.testing)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
}

// Das Datenbankschema entsteht beim Kompilieren (KSP) und wird für den Migrationstest als Asset gebraucht.
tasks.matching { it.name == "mergeDebugAssets" }.configureEach { dependsOn("kspDebugKotlin") }
