#!/bin/bash
set -e
mkdir -p app/src/main/java/tr/alihoca/turku/yeni/scan
mv *.kt app/src/main/java/tr/alihoca/turku/yeni/scan/ 2>/dev/null || true

cat << 'EOF' > settings.gradle
pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { google(); mavenCentral() } }
rootProject.name = "KitapTarama"
include ':app'
EOF

cat << 'EOF' > build.gradle
plugins {
    id 'com.android.application' version '8.2.2' apply false
    id 'org.jetbrains.kotlin.android' version '1.9.22' apply false
}
EOF

cat << 'EOF' > gradle.properties
android.useAndroidX=true
kotlin.code.style=official
org.gradle.jvmargs=-Xmx2048m
EOF

cat << 'EOF' > app/build.gradle
plugins {
    id 'com.android.application'
    id 'org.jetbrains.kotlin.android'
}
android {
    namespace 'tr.alihoca.turku.yeni'
    compileSdk 34
    defaultConfig {
        applicationId "tr.alihoca.turku.yeni.scanapp"
        minSdk 29
        targetSdk 34
        versionCode 1
        versionName "1.0"
    }
    compileOptions { sourceCompatibility JavaVersion.VERSION_17; targetCompatibility JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = '17' }
}
dependencies {
    implementation 'androidx.core:core-ktx:1.12.0'
    implementation 'androidx.appcompat:appcompat:1.6.1'
    implementation 'com.google.android.material:material:1.11.0'
    implementation 'androidx.documentfile:documentfile:1.0.1'
    implementation 'androidx.exifinterface:exifinterface:1.3.7'
}
EOF

cat << 'EOF' > app/src/main/AndroidManifest.xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application android:allowBackup="true" android:label="Ali Hoca Kitap Tarama" android:largeHeap="true" android:theme="@style/Theme.AppCompat.Light.DarkActionBar">
        <activity android:name=".scan.BookScanActivity" android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
EOF

gradle wrapper --gradle-version 8.5
./gradlew assembleDebug
