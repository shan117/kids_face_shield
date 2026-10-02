import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.google.services)
    id("kotlin-kapt")
}

// Release signing. Reads credentials from keystore.properties (gitignored) when present, so the
// build still works for anyone without the keystore (debug builds, CI). See PLAYSTORE_RELEASE_PLAN.md.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val hasKeystore = keystorePropertiesFile.exists()
val keystoreProperties = Properties().apply {
    if (hasKeystore) load(FileInputStream(keystorePropertiesFile))
}

android {
    namespace = "com.shantanu.shield"
    compileSdk = 35

    defaultConfig {
        // applicationId is your PERMANENT Play Store identity — immutable after first publish, and
        // what Firebase's google-services.json is keyed to. Finalize it BEFORE creating the Play app
        // or the Firebase project. Changing it here does NOT require changing `namespace` above.
        applicationId = "com.appsecure.shield"
        minSdk = 24
        targetSdk = 35
        versionCode = 4
        versionName = "1.0.3"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasKeystore) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // Signed with the upload key when keystore.properties is present; otherwise left for
            // Android Studio's "Generate Signed Bundle" flow.
            if (hasKeystore) signingConfig = signingConfigs.getByName("release")
            // Minify stays OFF for the first release — R8 can strip TFLite/Hilt/Compose reflection
            // without tuned keep-rules. Revisit once a minified build can be device-tested.
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
    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }
    buildFeatures {
        compose = true
        viewBinding = true
        buildConfig = true // needed for BuildConfig.DEBUG (RC fetch-interval gating)
        mlModelBinding = false // Fixed: Disabled to prevent "No metadata" errors
    }
    
    androidResources {
        noCompress += "tflite"
    }
    
    configurations.all {
        exclude(group = "com.google.ai.edge.litert", module = "litert-api")
        exclude(group = "com.google.ai.edge.litert", module = "litert")
        // Firebase/Firestore brings full Guava, which collides with the empty `listenablefuture` stub and
        // breaks CameraX's ListenableFuture (ProcessCameraProvider). Drop the stub so real Guava wins.
        exclude(group = "com.google.guava", module = "listenablefuture")

        resolutionStrategy {
            force("org.tensorflow:tensorflow-lite:2.16.1")
            force("org.tensorflow:tensorflow-lite-api:2.16.1")
            force("org.tensorflow:tensorflow-lite-support:0.4.4")
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.text.google.fonts)
    
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    
    implementation(libs.mlkit.face.detection)
    
    implementation(libs.tensorflow.lite)
    implementation(libs.tensorflow.lite.support)
    
    implementation(libs.hilt.android)
    kapt(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)
    
    implementation(libs.androidx.datastore.preferences)
    
    implementation(libs.androidx.navigation.compose)

    // Firebase Remote Config — runtime promo on/off (see FIREBASE_SETUP.md)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.config)
    // Firebase Cloud Messaging — push notifications (see MESSAGING_SETUP.md)
    implementation(libs.firebase.messaging)

    // Google Play Billing — subscriptions (see BILLING_PLAN.md)
    implementation(libs.billing.ktx)

    // ZXing core — QR pairing for Parent Remote Report (generate + scan). See PARENT_REMOTE_REPORT_PLAN.md.
    implementation(libs.zxing.core)

    // Firebase Firestore — E2E-encrypted remote report relay (stores ciphertext only)
    implementation(libs.firebase.firestore)
    // Firebase Auth — ANONYMOUS sign-in only. Gives each install a stable uid so the Firestore rules
    // can restrict a pairing's documents to the two devices that actually paired, instead of to anyone
    // who learns the pairing id. No account, login or password is ever surfaced to a user.
    // See PHASE7_AUTH_PLAN.md.
    implementation(libs.firebase.auth)
    // Real Guava on the COMPILE classpath: Firestore exposes Guava only as runtime `implementation`, so
    // with the empty `listenablefuture` stub excluded (above) CameraX's ListenableFuture would otherwise
    // be unresolved. The -android variant is the right one for an Android app.
    implementation("com.google.guava:guava:33.3.1-android")
    // WorkManager — weekly background sync of the child's encrypted report
    implementation(libs.androidx.work.runtime)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

kapt {
    correctErrorTypes = true
}
