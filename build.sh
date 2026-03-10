#!/usr/bin/env bash
set -e

JAVA_HOME="/d/translate_app/.sdk/jdk17"
GRADLE_HOME="/d/translate_app/.sdk/gradle-8.6"
ANDROID_HOME="/d/translate_app/.sdk/android"
export JAVA_HOME GRADLE_HOME ANDROID_HOME
export PATH="$JAVA_HOME/bin:$GRADLE_HOME/bin:$PATH"

echo "==> Java version"
java -version

echo "==> Gradle version"
gradle --version | head -3

cd /d/translate_app

echo "==> Generating Gradle wrapper JAR..."
gradle wrapper --gradle-version 8.6

echo "==> Writing local.properties..."
# Use forward slashes — Android's properties parser accepts them and they
# don't require any escaping, unlike backslashes in .properties files.
echo "sdk.dir=D:/translate_app/.sdk/android" > local.properties

echo "==> Building debug APK..."
./gradlew assembleDebug 2>&1

echo "==> Build complete"
find . -name "*.apk" -not -path "*/.sdk/*"
