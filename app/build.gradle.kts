plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.psave.tubesave"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.psave.tubesave"
        minSdk = 24
        targetSdk = 34
        versionCode = 2
        versionName = "1.1"

        ndk {
            // arm64-v8a/armeabi-v7a = 실제 폰, x86_64 = 에뮬레이터(Windows) 검증용
            // (x86_64를 빼면 에뮬레이터에서 yt-dlp 네이티브 바이너리가 없어 다운로드가 무조건 실패)
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // yt-dlp 안드로이드 포팅 + ffmpeg (mp3 추출)
    implementation("io.github.junkfood02.youtubedl-android:library:0.18.1")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1")

    // 백그라운드/잠금화면 재생 (ExoPlayer + MediaSession)
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-session:1.4.1")
}
