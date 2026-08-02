plugins {
    id("com.android.application")
}

val stableKeystore = rootProject.file(".signing/html-apk-stable.p12")
val stableStorePassword = "HTML_APK_BUILDER_STABLE_2026"
val stableKeyAlias = "html-apk-stable"
val stableKeyPassword = "HTML_APK_BUILDER_STABLE_2026"

android {
    namespace = "com.yoann.monapplication"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.yoann.monapplication"
        minSdk = 24
        targetSdk = 35
        versionCode = 206756921
        versionName = "21.0.0"
    }

    signingConfigs {
        create("stable") {
            if (stableKeystore.exists()) {
                storeFile = stableKeystore
                storePassword = stableStorePassword
                keyAlias = stableKeyAlias
                keyPassword = stableKeyPassword
                storeType = "PKCS12"
            }
        }
    }

    buildTypes {
        debug {
            if (stableKeystore.exists()) {
                signingConfig = signingConfigs.getByName("stable")
            }
        }

        release {
            isMinifyEnabled = false
            if (stableKeystore.exists()) {
                signingConfig = signingConfigs.getByName("stable")
            }
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
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.5.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
    implementation("androidx.mediarouter:mediarouter:1.7.0")
    implementation("com.google.android.gms:play-services-cast-framework:22.0.0")
}
