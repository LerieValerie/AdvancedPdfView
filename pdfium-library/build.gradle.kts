@file:Suppress("UnstableApiUsage")

import Presentation_android_common_gradle.FlavorsConfiguratorPlugin.configureFlavors

plugins {
    id(libs.plugins.comAndroidLibrary)
    id(libs.plugins.kotlinAndroid)
}

configureFlavors()

android {
    compileSdk = Tools.Android.compileSdkVersion
    namespace = "org.benjinus.pdfium"

    defaultConfig {
        minSdk = Tools.Android.minSdkVersion
        consumerProguardFiles("proguard-rules.pro")

        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DPROJECT_ROOT_DIR=${rootProject.projectDir.path.replace(File.separatorChar, '/')}",
                    "-DANDROID_STL=c++_static",
                    "-DANDROID_PLATFORM=android-21",
                    "-DANDROID_ARM_NEON=TRUE"
                )
                cppFlags += "-std=c++11 -frtti -fexceptions"
            }
        }

    }
    ndkVersion = "25.2.9519653"

    externalNativeBuild {
        cmake {
            path = File("${projectDir}/src/main/cpp/CMakeLists.txt")
        }
    }

    sourceSets["main"].apply {
        manifest.srcFile("AndroidManifest.xml")
        java.srcDirs("src")
        res.srcDirs("res")
        jniLibs.srcDirs("src/main/cpp/pdfium/lib")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(project(Modules.Core.data))
    implementation(project(Modules.Core.domain))

    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar"))))
    implementation(kotlin(pluginToString(libs.plugins.stdlib)))
    implementation(libs.androidxCore.coreKtx)
}