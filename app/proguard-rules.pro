# ============================================================
#  ProGuard / R8 规则（当前 release 未开启混淆，保留备用）
# ============================================================

# 保留 Activity / View 的构造方法（XML 反射创建自定义 View 时必需）
-keepclassmembers class * extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

# 保留自定义 View（SparklineView）
-keep class com.example.signlanguage.SparklineView { *; }

# 保留 TTS 相关回调
-keep class android.speech.tts.** { *; }

# 保留行号，方便线上排错
-keepattributes SourceFile,LineNumberTable
