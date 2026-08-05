import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

// Reuse the same release signing key as the TRide app (../TRide/android/app/tride-release.jks,
// copied to app/tride-release.jks) so both are signed by the same certificate. Not present in
// CI/other machines unless keystore.properties + the .jks are provided, so signing is skipped
// (unsigned release build) rather than failing the build when they're missing.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        load(keystorePropertiesFile.inputStream())
    }
}

android {
    namespace = "com.commute.app"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.commute.tyranno"
        minSdk = 24
        targetSdk = 36
        versionCode = 9
        versionName = "0.5.4"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Same app, two distribution channels, and they cannot share one binary. Google Play's Device
    // and Network Abuse policy forbids an app distributed through Play from updating itself by any
    // route other than Play, while the APK published on GitHub Releases has no store to update it
    // and so needs a built-in updater. Splitting by flavor keeps the updater — and the
    // REQUEST_INSTALL_PACKAGES permission it needs — out of the Play build entirely rather than
    // merely hidden behind a flag. See app/src/{github,play}/java/.../update/UpdateActions.kt.
    //
    // Both flavors keep the same applicationId and signing key, so either can upgrade the other
    // in place on a device.
    flavorDimensions += "distribution"
    productFlavors {
        create("github") {
            dimension = "distribution"
        }
        create("play") {
            dimension = "distribution"
        }
    }

    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            create("release") {
                storeFile = file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
            }
        }
    }

    buildTypes {
        release {
            // R8 on for release: strips unused library code (Compose and material-icons-extended
            // are most of the ~12MB), and in the play flavor it also removes the update code paths
            // that UPDATER_ENABLED = false makes unreachable. Verify a release build on a device
            // before publishing — shrinking is the one setting that can break only at runtime.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        // Signed with the same release key (when available) rather than the auto-generated debug
        // keystore, so a debug build installed for day-to-day testing shares its signing
        // certificate with a shipped release — Android refuses to upgrade a package in place
        // across different signing certs, which otherwise forces an uninstall (losing on-device
        // data) the first time a debug tester tries to install a real release, or vice versa.
        debug {
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlinOptions {
        jvmTarget = "21"
    }

    buildFeatures {
        compose = true
    }
}

// Release artifacts land in build/outputs under generic AGP-chosen names
// (app-play-release.aab, app-github-release.apk) that are identical across
// flavors and versions, so a manually copied dist/ file gives no clue which
// channel or version it is. These tasks stage each flavor's release artifact
// into dist/ under a name that encodes both: which store it's for (play/github)
// and which version it is.
val distDir = rootProject.layout.projectDirectory.dir("dist")

tasks.register<Copy>("distPlayBundle") {
    dependsOn("bundlePlayRelease")
    from(layout.buildDirectory.file("outputs/bundle/playRelease/app-play-release.aab"))
    into(distDir)
    rename { "Commute-play-${android.defaultConfig.versionName}.aab" }
}

tasks.register<Copy>("distGithubApk") {
    dependsOn("assembleGithubRelease")
    from(layout.buildDirectory.file("outputs/apk/github/release/app-github-release.apk"))
    into(distDir)
    rename { "Commute-github-${android.defaultConfig.versionName}.apk" }
}

tasks.register("dist") {
    description = "Stage this version's Play (.aab) and GitHub (.apk) release artifacts into dist/ with channel-specific names."
    dependsOn("distPlayBundle", "distGithubApk")
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    implementation("androidx.datastore:datastore-preferences:1.1.1")

    testImplementation("junit:junit:4.13.2")
    // Real org.json on the unit-test classpath: the android.jar stub throws on every call, so
    // JSONObject-based code (RecoveryJournal's encode/parse) can't be exercised without it.
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.12.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
