plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.eddie.usbmouse"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.eddie.usbmouse"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // SIN signingConfig a proposito. Estaba firmando con la clave de
            // depuracion, que es publica y viene con el SDK: cualquiera podia
            // publicar una version troyanizada que Android aceptaba como
            // ACTUALIZACION de esta app, sin un solo aviso. Mejor que
            // assembleRelease deje un APK sin firmar y haya que firmarlo con
            // una clave propia que una firma que no significa nada.
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
}
