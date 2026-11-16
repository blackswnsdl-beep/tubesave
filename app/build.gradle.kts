import java.security.KeyStore
import java.security.MessageDigest
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ── 업데이트 서명 고정 ────────────────────────────────────────────────
// Android는 설치된 앱과 "같은 키"로 서명된 APK만 업데이트로 받아 준다. 키가 다르면
// 기존 앱을 지워야만 설치된다. 그래서 어디서 빌드하든 폰에 깔린 앱과 같은 키만 쓰게 한다.
//  - 키 파일 지정: 환경변수 TUBESAVE_KEYSTORE_FILE 또는 keystore.properties 의 KEYSTORE_FILE
//  - 지정이 없으면 이 PC의 기본 디버그 키(~/.android/debug.keystore)
//  - 어느 쪽이든 gradle.properties 의 tubesave.signerSha256 과 다르면 빌드를 멈춘다.
val signingProps = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.isFile) file.inputStream().use { load(it) }
}
fun signingValue(name: String): String? =
    (System.getenv("TUBESAVE_$name") ?: signingProps.getProperty(name))?.takeIf { it.isNotBlank() }
val updateKeystore = signingValue("KEYSTORE_FILE")
val pinnedSigner = (findProperty("tubesave.signerSha256") as String).trim().lowercase()
val allowUnpinnedSigner = findProperty("tubesave.allowUnpinnedSigner") == "true"

android {
    namespace = "com.psave.tubesave"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.psave.tubesave"
        minSdk = 24
        targetSdk = 34
        versionCode = 5
        versionName = "1.4"

        ndk {
            // arm64-v8a/armeabi-v7a = 실제 폰, x86_64 = 에뮬레이터(Windows) 검증용
            // (x86_64를 빼면 에뮬레이터에서 yt-dlp 네이티브 바이너리가 없어 다운로드가 무조건 실패)
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    signingConfigs {
        getByName("debug") {
            if (updateKeystore != null) {
                storeFile = rootProject.file(updateKeystore)
                storePassword = signingValue("KEYSTORE_PASSWORD") ?: "android"
                keyAlias = signingValue("KEY_ALIAS") ?: "androiddebugkey"
                keyPassword = signingValue("KEY_PASSWORD") ?: "android"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // release 빌드도 같은 키로 서명해야 기존 앱 위에 업데이트로 설치된다.
            signingConfig = signingConfigs.getByName("debug")
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

// 서명 키가 고정된 키와 다르면 APK를 만들기 전에 멈춘다 (지워야만 설치되는 APK 방지).
android.applicationVariants.all {
    val config = signingConfig ?: return@all
    val variant = name.replaceFirstChar { it.uppercase() }
    val storeFile = config.storeFile
    val storePassword = config.storePassword.orEmpty()
    val keyAlias = config.keyAlias.orEmpty()
    val check = tasks.register("verifyUpdateSigner$variant") {
        group = "verification"
        description = "서명 키가 폰에 설치된 앱의 키와 같은지 확인"
        dependsOn("validateSigning$variant")
        doLast {
            val certificate = storeFile?.takeIf { it.isFile }
                ?.let { KeyStore.getInstance(it, storePassword.toCharArray()).getCertificate(keyAlias) }
                ?: throw GradleException("서명 키를 읽을 수 없어요: $storeFile (별칭 $keyAlias)")
            val actual = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
                .joinToString("") { "%02x".format(it) }
            if (actual == pinnedSigner) return@doLast
            val problem = "서명 키가 달라요. 이 APK는 기존 앱을 지워야만 설치돼요 (업데이트 불가).\n" +
                "  사용한 키: $actual ($storeFile)\n  고정된 키: $pinnedSigner\n" +
                "  해결: README의 '업데이트 서명 고정'을 참고해 원래 키 파일을 지정하세요."
            if (!allowUnpinnedSigner) throw GradleException(problem)
            logger.warn("경고(테스트 전용 빌드): $problem")
        }
    }
    tasks.named("package$variant") { dependsOn(check) }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
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
