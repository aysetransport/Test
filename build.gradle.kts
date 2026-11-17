plugins {
    alias(libs.plugins.android.application) apply false
    // Legt die Kotlin-Version fest, mit der AGP den Kotlin-Code übersetzt.
    alias(libs.plugins.kotlin.compose) apply false
}
