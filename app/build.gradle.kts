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

        // Clave de TMDb (themoviedb.org) para la información de películas
        // (ver README, "Información de películas (TMDb)"). Si existe la
        // variable de entorno TMDB_API_KEY (p. ej. un secreto de GitHub
        // Actions) manda esa; si no, la propiedad de gradle del mismo
        // nombre; y si tampoco, esta por defecto. Vacía = función apagada.
        val tmdbKey = System.getenv("TMDB_API_KEY")
            ?: (project.findProperty("TMDB_API_KEY") as String?)
            ?: "8057a54a57714cade319b15e4fc0c3b4"
        buildConfigField("String", "TMDB_API_KEY", "\"$tmdbKey\"")
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
        buildConfig = true
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

    // Carga de iconos/miniaturas desde URL
    implementation("io.coil-kt:coil:2.6.0")
}
