import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Firma de release: en local sale de keystore.properties (fuera de git);
// en GitHub Actions, de variables de entorno que llenan los secretos del repo.
val firma = Properties().apply {
    val archivo = rootProject.file("keystore.properties")
    if (archivo.exists()) archivo.inputStream().use { load(it) }
}
fun valorFirma(clave: String, env: String): String? = firma.getProperty(clave) ?: System.getenv(env)

android {
    namespace = "com.deibee.voxtypee"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.deibee.voxtypee"
        minSdk = 29
        targetSdk = 36
        versionCode = (project.findProperty("versionCode") as String?)?.toInt() ?: 1
        versionName = (project.findProperty("versionName") as String?) ?: "0.1.0-dev"
    }

    signingConfigs {
        create("release") {
            val ruta = valorFirma("storeFile", "VOXTYPEE_KEYSTORE")
            if (ruta != null) {
                storeFile = file(ruta)
                storePassword = valorFirma("storePassword", "VOXTYPEE_KEYSTORE_PASSWORD")
                keyAlias = valorFirma("keyAlias", "VOXTYPEE_KEY_ALIAS")
                keyPassword = valorFirma("keyPassword", "VOXTYPEE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName("release").takeIf { it.storeFile != null }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
