# Tandem release keep rules (R8 full mode).
# Most libraries ship their own consumer rules: kotlinx.serialization (>= 1.5), Retrofit 3,
# OkHttp 5, Tink, Hilt/Dagger, Compose and AndroidX. The rules below cover our own code.

# Keep line numbers for readable crash reports, but hide the original source file name.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# kotlinx.serialization: keep generated serializers of our @Serializable classes.
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault,InnerClasses,Signature
-if @kotlinx.serialization.Serializable class casa.moller.tandem.**
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}
-if @kotlinx.serialization.Serializable class casa.moller.tandem.** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}
-if @kotlinx.serialization.Serializable class casa.moller.tandem.** {
    public static ** INSTANCE;
}
-keepclassmembers class <1> {
    public static <1> INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

# Retrofit: keep generic signatures of service interfaces (suspend functions, Response<T>).
# (Retrofit 3's own rules contain the same; kept here so the intent survives a library update.)
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
# The Retrofit service interfaces themselves are kept by core/network/consumer-rules.pro, which
# explains why Retrofit's conditional rule is not enough (the v0.0.1 login crash). Do not
# replace it with an "-if interface ... { @retrofit2.http.* <methods>; }" rule here.
