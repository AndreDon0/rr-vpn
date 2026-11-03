plugins { id("com.android.application") }
android {
    namespace = "com.dream.driftpool"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.dream.driftpool"
        minSdk = 26
        targetSdk = 35
        versionCode = providers.gradleProperty("appVersionCode").orNull?.toInt() ?: 6
        versionName = providers.gradleProperty("appVersionName").orNull ?: "0.5.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += providers.gradleProperty("targetAbis").orNull?.split(",")
            ?: listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    bundle { language { enableSplit = false } }
    packaging {
        resources.excludes += listOf("META-INF/DEPENDENCIES")
        jniLibs.useLegacyPackaging = true
    }
}
dependencies {
    implementation(files("libs/libv2ray.aar"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20231013")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
}
