# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# Оставляем классы модели Firebase (если используешь DataSnapshot)
-keepclassmembers class com.google.firebase.** {
    *;
}

# Оставляем модели Gson (если Retrofit + Gson)
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.yourpackage.model.** { *; }  # Замени на актуальный путь к моделям

# Оставляем ViewModel и связанные с Jetpack Compose
-keep class androidx.lifecycle.** { *; }
-keepclassmembers class * extends androidx.lifecycle.ViewModel {
    <init>(...);
}

# Compose специфичные вещи
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**

# Обязательные правила для работы с Retrofit
-keep class retrofit2.** { *; }
-dontwarn retrofit2.**
-keepattributes Exceptions

# Для Coil
-dontwarn coil.**
-keep class coil.** { *; }

# Если используешь reflection:
-keepnames class kotlin.Metadata
-keepclassmembers class ** {
    @kotlin.Metadata *;
}