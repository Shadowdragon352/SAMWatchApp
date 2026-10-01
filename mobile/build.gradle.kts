plugins { id("com.android.application") }

android {
    namespace = "au.org.deliriumwatch.mobile"
    compileSdk = 36

    defaultConfig {
        applicationId = "au.org.deliriumwatch"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "0.1.2"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("com.google.android.gms:play-services-wearable:20.0.1")
}
