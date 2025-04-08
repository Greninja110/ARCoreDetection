plugins {
    id("com.android.application")
    id("kotlin-android")
    id("kotlin-kapt")
    id("kotlin-parcelize")
}

android {
    namespace = "com.yourusername.arcoredetection"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.yourusername.arcoredetection"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isDebuggable = true
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // Add this to ensure ARCore assets are properly included
    aaptOptions {
        noCompress += listOf("tflite")
    }
}

dependencies {
    // Core Android dependencies
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.10.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.6.2")

    // CameraX
    val cameraxVersion = "1.3.1"
    implementation("androidx.camera:camera-core:${cameraxVersion}")
    implementation("androidx.camera:camera-camera2:${cameraxVersion}")
    implementation("androidx.camera:camera-lifecycle:${cameraxVersion}")
    implementation("androidx.camera:camera-video:${cameraxVersion}")
    implementation("androidx.camera:camera-view:${cameraxVersion}")
    implementation("androidx.camera:camera-extensions:${cameraxVersion}")

    // ExoPlayer - Updated for compatibility
    implementation("com.google.android.exoplayer:exoplayer-core:2.19.1")
    implementation("com.google.android.exoplayer:exoplayer-rtsp:2.19.1")
    implementation("com.google.android.exoplayer:exoplayer-ui:2.19.1")

    // ARCore and SceneView - UPDATED with specific versions
    implementation("com.google.ar:core:1.40.0")

    // SceneView - Updated to newer version (this is critical)
    implementation("io.github.sceneview:sceneview:0.10.0")
    implementation("io.github.sceneview:arsceneview:0.10.0")

    // Kotlin Math for SceneView
    implementation("dev.romainguy:kotlin-math:1.5.3")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Timber for logging
    implementation("com.jakewharton.timber:timber:5.0.1")

    // Retrofit for API communication
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation("com.google.code.gson:gson:2.10.1")

    // Testing dependencies
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")

    // RTSP streaming
    implementation("com.github.pedroSG94:rtmp-rtsp-stream-client-java:2.1.8")
    implementation("com.github.pedroSG94.vlc-example-streamplayer:library:2.5.3")
    implementation("com.google.code.gson:gson:2.10.1")
}