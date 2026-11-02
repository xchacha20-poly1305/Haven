import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

val releaseKeystore = rootProject.file("release.keystore")
val signingProperties = providers.fileContents(
    rootProject.layout.projectDirectory.file("local.properties")
).asText.orElse("").map { content ->
    Properties().apply { content.reader().use { load(it) } }
}

android {
    namespace = "sh.haven.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.xchacha20_poly1305.haven"
        resValue("string", "application_id", applicationId!!)
        minSdk = 26
        targetSdk = 35
        versionCode = 930
        versionName = "5.94.5"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    flavorDimensions += listOf("abi", "features")
    productFlavors {
        create("arm64") {
            dimension = "abi"
            ndk { abiFilters += "arm64-v8a" }
        }
        create("x64") {
            dimension = "abi"
            ndk { abiFilters += "x86_64" }
        }
        create("armv7") {
            dimension = "abi"
            ndk { abiFilters += "armeabi-v7a" }
        }

        // #510: an 80 MB download every few days is a real cost for someone
        // who only uses the terminal. `full` is what has always shipped.
        create("full") {
            dimension = "features"
            buildConfigField("boolean", "DESKTOP", "true")
        }
        create("terminal") {
            dimension = "features"
            buildConfigField("boolean", "DESKTOP", "false")
            // The native payload it drops is excluded per-variant below —
            // ProductFlavor has no `packaging` block, and putting one here
            // silently resolves against the `android` extension instead,
            // stripping the libraries from EVERY variant. Measured: it made
            // the full flavour 55 MB too.
        }
    }

    signingConfigs {
        create("release") {
            if (releaseKeystore.isFile) {
                fun signingProperty(name: String): String =
                    signingProperties.get().getProperty(name)?.takeIf { it.isNotBlank() }
                        ?: error("Missing signing property '$name' in local.properties")

                storeFile = releaseKeystore
                storePassword = signingProperty("KEYSTORE_PASS")
                keyAlias = signingProperty("ALIAS_NAME")
                keyPassword = signingProperty("ALIAS_PASS")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            // The release workflow passes -PnoLintVital: lintVitalAnalyze ran
            // concurrently with minifyWithR8 and that overlap is where the
            // 16G free runners got evicted (v5.89.0: 15.6G used, 373M
            // available, killed mid-R8). Nothing consumes lintVital's result
            // in CI — the gate lives in local release builds, which keep it.
            // (buildType-level checkReleaseBuilds is gone in AGP 9; the
            // lint {} block below is the replacement.)
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            vcsInfo.include = false
        }
        debug {
            // Use the same certificate locally so debug can update release builds.
            if (releaseKeystore.isFile) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
        resValues = true
    }

    packaging {
        resources {
            excludes += "/META-INF/versions/*/OSGI-INF/MANIFEST.MF"
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/NOTICE.md"
            excludes += "/META-INF/LICENSE.md"
            // Apache Mime4j (email MIME parsing) ships these in both its core and
            // dom jars; drop the duplicates so the APK merge doesn't conflict.
            excludes += "/META-INF/DEPENDENCIES"
            excludes += "/META-INF/{LICENSE,LICENSE.txt,NOTICE,NOTICE.txt}"
        }
        jniLibs {
            useLegacyPackaging = true
            excludes += setOf(
                "**/libvmlinux.so", "**/libuml-stub.so",
                "**/libuml-passt.so", "**/libuml-net.so",
            )
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    lint {
        // -PnoLintVital (release workflow only): skip lintVital on release
        // builds. Its analyze/report ran concurrently with minifyWithR8 and
        // that overlap is where the 16G free runners got evicted (v5.89.0:
        // 15.6G used, 373M available, killed mid-R8); nothing in CI consumes
        // its result. Local release builds, which omit the property, keep
        // the gate.
        if (project.hasProperty("noLintVital")) {
            checkReleaseBuilds = false
        }
    }
}

// Version code scheme: base * 10 + abiOffset; APK named
// haven-<version>-<abi>-<buildtype>.apk, with "-terminal" inserted for the
// stripped flavour. Rewritten from the removed applicationVariants API for
// AGP 9's Variant API; outputFileName still needs the impl cast — there is
// no public rename hook yet.
//
// ★ The terminal flavour deliberately carries the SAME versionCode as full:
// it is the same release of the same app, and changing the code would make
// one variant look like an update to the other. The consequence is that an
// updater comparing version codes will not offer a switch between them —
// switching is a deliberate sideload. Revisit if the variant ever gets its
// own F-Droid entry, which would want a distinct applicationId instead.
androidComponents {
    // #510: the terminal flavour ships the same code without the desktop
    // native payload. Excluded here rather than on the flavour because
    // ProductFlavor has no packaging block.
    //
    onVariants(selector().withFlavor("features" to "terminal")) { variant ->
        variant.packaging.jniLibs.excludes.addAll(
            // Remote desktop transports
            "**/librdp_transport.so",
            "**/libspice_transport.so",
        )
    }

    onVariants { variant ->
        val abiCodes = mapOf("arm64" to 1, "x64" to 2, "armv7" to 3)
        val abi = variant.productFlavors.firstOrNull { it.first == "abi" }?.second
        val features = variant.productFlavors.firstOrNull { it.first == "features" }?.second
        val abiCode = abiCodes[abi]
        val base = android.defaultConfig.versionCode ?: 0
        val versionName = android.defaultConfig.versionName
        val suffix = if (features == "terminal") "-terminal" else ""
        variant.outputs.forEach { output ->
            if (abiCode != null) {
                output.versionCode.set(base * 10 + abiCode)
            }
            (output as? com.android.build.api.variant.impl.VariantOutputImpl)
                ?.outputFileName?.set("haven-$versionName-$abi$suffix-${variant.buildType}.apk")
        }
    }
}

dependencies {
    implementation(project(":core:redact"))
    implementation(project(":core:ui"))
    implementation(project(":core:ssh"))
    implementation(project(":core:security"))
    implementation(project(":core:data"))

    implementation(project(":core:mosh"))
    implementation(project(":core:et"))
    implementation(project(":core:btserial"))
    implementation(project(":core:bleserial"))
    implementation(project(":core:usbserial"))
    implementation(project(":core:vnc"))
    implementation(project(":core:rdp"))
    implementation(project(":core:spice"))
    implementation(project(":core:glkvm"))
    implementation(project(":core:smb"))
    implementation(project(":core:fido"))
    implementation(project(":core:usb"))
    implementation(project(":core:terminal-haven"))

    // public types (TerminalEmulator, ScrollController, SelectionController,
    // SelectionRange, AgentSnapshot) by class. core:terminal-haven uses
    // `implementation(libs.termlib)` so the dependency doesn't leak.
    implementation(libs.termlib)
    implementation(project(":core:stepca"))
    implementation(project(":core:tunnel"))
    implementation(project(":core:knock"))
    implementation(project(":core:spa"))

    implementation(project(":feature:connections"))
    implementation(project(":feature:terminal"))
    implementation(project(":feature:sftp"))
    implementation(project(":feature:keys"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:editor"))
    implementation(project(":feature:vnc"))
    implementation(project(":feature:rdp"))
    // The app manifest declares CloudflareAccessLoginActivity, so app must
    // depend on its module directly — it only arrived transitively before,
    // which AGP 9 lint flags as MissingClass on the compile classpath.
    implementation(project(":feature:tunnel"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material3.window.size)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.ui.tooling.preview)

    implementation(libs.core.ktx)
    implementation(libs.appcompat)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime)
    implementation(libs.lifecycle.process)
    implementation(libs.biometric)
    implementation(libs.navigation.compose)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)

    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    coreLibraryDesugaring(libs.desugar.jdk.libs)

    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.coroutines.test)
    testImplementation("org.json:json:20260814")
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
