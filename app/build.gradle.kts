plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.superplayer"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.superplayer"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

    }

    // Firma FIJA para las APK de depuración (ver README, "Actualizar sin
    // desinstalar"): sin esto, cada compilación de GitHub Actions genera una
    // clave de depuración nueva, Android ve "otra app" y obliga a
    // desinstalar la anterior (perdiendo favoritos y ajustes). Con la misma
    // clave en todas las compilaciones, la nueva se instala encima de la
    // vieja. Es una clave de uso personal, sin ningún secreto real: solo
    // sirve para que Android reconozca las actualizaciones como de la misma app.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        // Media3/ExoPlayer marca casi toda su API de bajo nivel (DASH/HLS,
        // DRM, MediaSource.Factory, MediaSession...) como @UnstableApi. Las
        // clases que la usan también llevan @OptIn(UnstableApi::class); esto
        // es solo una red de seguridad adicional a nivel de módulo.
        freeCompilerArgs += listOf("-opt-in=androidx.media3.common.util.UnstableApi")
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.activity:activity-ktx:1.9.3")

    // Media3 / ExoPlayer: soporte DASH + HLS + UI del reproductor + sesión
    // multimedia (reproducción en segundo plano / pantalla de bloqueo)
    val media3Version = "1.10.1"
    implementation("androidx.media3:media3-exoplayer:$media3Version")
    implementation("androidx.media3:media3-exoplayer-dash:$media3Version")
    implementation("androidx.media3:media3-exoplayer-hls:$media3Version")
    implementation("androidx.media3:media3-datasource:$media3Version")
    implementation("androidx.media3:media3-ui:$media3Version")
    implementation("androidx.media3:media3-common:$media3Version")
    implementation("androidx.media3:media3-session:$media3Version")

    // Chromecast: "enviar" el canal actual a un Chromecast (o cualquier
    // dispositivo compatible con Google Cast) usando el receptor
    // multimedia genérico de Google, sin receptor propio (ver
    // CastOptionsProviderImpl). media3-cast trae CastPlayer, que envuelve
    // el ExoPlayer de PlaybackService para que la misma MediaSession sirva
    // tanto en local como "enviada" (ver PlaybackService.onCreate).
    implementation("androidx.media3:media3-cast:$media3Version")
    implementation("com.google.android.gms:play-services-cast-framework:22.3.1")
    implementation("androidx.mediarouter:mediarouter:1.2.5")

    // Fila "Continuar viendo" en el inicio de Android TV (Watch Next)
    implementation("androidx.tvprovider:tvprovider:1.0.0")

    // Carga de iconos/miniaturas desde URL
    implementation("io.coil-kt:coil:2.6.0")
}
