# Die verwendeten Bibliotheken (Room, kotlinx.serialization, WorkManager, Coil)
# bringen ihre eigenen Regeln mit. Hier stehen nur projektspezifische Ergänzungen.

# E-Mail (Jakarta Mail / Angus): Anbieter werden per Reflexion aus META-INF geladen.
-keep class org.eclipse.angus.mail.** { *; }
-keep class org.eclipse.angus.activation.** { *; }
-keep class jakarta.mail.** { *; }
-keep class jakarta.activation.** { *; }
-dontwarn java.awt.**
-dontwarn javax.security.sasl.**
-dontwarn javax.naming.**
-dontwarn org.eclipse.angus.mail.**
-dontwarn jakarta.activation.**

# GraalVM-Native-Image-Hilfen von Angus Activation werden auf Android nie geladen.
-dontwarn org.graalvm.nativeimage.**
