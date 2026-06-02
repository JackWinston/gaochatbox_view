# ============================================
# 项目混淆规则
# ============================================

# 保留行号信息，便于调试崩溃堆栈
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ============================================
# Kotlin
# ============================================
-keep class kotlin.Metadata { *; }
-dontwarn kotlin.**
-keepclassmembers class **$WhenMappings {
    <fields>;
}
-keepclassmembers class kotlin.Metadata {
    public <methods>;
}

# ============================================
# AndroidX / Material
# ============================================
-keep class com.google.android.material.** { *; }
-dontwarn com.google.android.material.**
-keep class androidx.** { *; }
-dontwarn androidx.**

# ============================================
# Hilt / Dagger
# ============================================
-dontwarn dagger.hilt.**
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper { *; }
-keepclasseswithmembers class * {
    @dagger.hilt.* <fields>;
}
-keepclasseswithmembers class * {
    @dagger.hilt.* <methods>;
}

# ============================================
# Room
# ============================================
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**
-keep class * implements androidx.room.EntityDeletionOrUpdateAdapter
-keep class * implements androidx.room.EntityInsertionAdapter
-keep class * implements androidx.room.EntityDeletionOrUpdateAdapter
-keepclassmembers class * {
    @androidx.room.* <fields>;
    @androidx.room.* <methods>;
}

# ============================================
# Retrofit + OkHttp
# ============================================
-dontwarn retrofit2.**
-keep class retrofit2.** { *; }
-keepattributes Signature
-keepattributes Exceptions

-dontwarn okhttp3.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-dontwarn okio.**
-keep class okio.** { *;}

# 保留 Retrofit 接口方法
-keepclasseswithmembers class * {
    @retrofit2.http.* <methods>;
}

# ============================================
# Gson
# ============================================
-keepattributes Signature
-keepattributes *Annotation*
-dontwarn sun.misc.**
-keep class com.google.gson.** { *; }
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# ============================================
# DataStore
# ============================================
-keep class androidx.datastore.** { *; }
-dontwarn androidx.datastore.**

# ============================================
# Markwon (Markdown 渲染)
# ============================================
-keep class io.noties.markwon.** { *; }
-dontwarn io.noties.markwon.**

# ============================================
# BRVAH (BaseRecyclerViewAdapterHelper)
# ============================================
-keep class com.chad.library.** { *; }
-dontwarn com.chad.library.**

# ============================================
# 保留数据模型类 (Room Entities)
# ============================================
-keep class com.gao.chatbox.view.data.local.db.entity.** { *; }
-keep class com.gao.chatbox.view.domain.model.** { *; }

# ============================================
# 保留枚举
# ============================================
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ============================================
# 保留 Parcelable
# ============================================
-keep class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}

# ============================================
# 保留 Serializable
# ============================================
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# ============================================
# 保留自定义 View 构造方法
# ============================================
-keepclasseswithmembers class * {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

# ============================================
# 保留 JavaScript 接口 (如果有 WebView)
# ============================================
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# ============================================
# 移除日志 (release 构建)
# ============================================
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}
