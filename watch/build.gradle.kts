plugins { id("com.android.application") }

android {
    namespace = "au.org.deliriumwatch.watch"
    compileSdk = 36

    defaultConfig {
        applicationId = "au.org.deliriumwatch"
        minSdk = 30
        targetSdk = 36
        versionCode = 3
        versionName = "0.1.3"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
    implementation("com.google.android.gms:play-services-wearable:20.0.1")
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar"))))
}
