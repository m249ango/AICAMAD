plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.myapplication"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.example.myapplication"
        minSdk = 24
        targetSdk = 36
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
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    // CameraX 라이브러리
    val camerax_version = "1.3.1"
    implementation("androidx.camera:camera-core:$camerax_version")
    implementation("androidx.camera:camera-camera2:$camerax_version")
    implementation("androidx.camera:camera-lifecycle:$camerax_version")
    implementation("androidx.camera:camera-view:$camerax_version")

    // MediaPipe Object Detection 라이브러리
    implementation("com.google.mediapipe:tasks-vision:0.10.14")

    // TensorFlow Lite — 풍경 모드 구도 분류 모델 (.tflite 포맷)
    //
    // Maven 저장소의 tensorflow-lite 2.13+ 는 tensorflow-lite 와 tensorflow-lite-api 가
    // 동일 namespace(org.tensorflow.lite) 를 선언하여 AGP 9.x manifest merger 오류 발생.
    //
    // 해결 방법: 두 AAR 을 app/libs/ 에 로컬 파일로 보관하고 직접 참조한다.
    // - tensorflow-lite.aar          : Interpreter.class 등 실제 구현 포함
    // - tensorflow-lite-api-patched.aar : InterpreterApi 등 인터페이스 포함
    //                                     AndroidManifest 의 package 를
    //                                     org.tensorflow.lite.api 로 패치하여
    //                                     namespace 충돌 제거
    implementation(files("libs/tensorflow-lite.aar"))
    implementation(files("libs/tensorflow-lite-api-patched.aar"))

    // OkHttp — 촬영 후 미학 점수 API 호출
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // RecyclerView — 앱 전용 갤러리 그리드
    implementation("androidx.recyclerview:recyclerview:1.3.2")
}
