# The ZeroClaw client and its models are (de)serialized by kotlinx.serialization: keep their serializers.
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod
-keep,includedescriptorclasses class io.github.rustlanglatam.zeroclaw.**$$serializer { *; }
-keepclassmembers class io.github.rustlanglatam.zeroclaw.** { *** Companion; kotlinx.serialization.KSerializer serializer(...); }
-keep class io.github.rustlanglatam.zeroclaw.** { *; }
# Ktor and OkHttp reflect on a few optional classes.
-dontwarn org.slf4j.**
-dontwarn java.lang.management.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
# The mail library finds its providers and data handlers by name (META-INF/javamail.*, mailcap).
-keep class com.sun.mail.** { *; }
-keep class javax.mail.** { *; }
-keep class javax.activation.** { *; }
-keep class com.sun.activation.** { *; }
-dontwarn java.awt.**
-dontwarn java.beans.**
-dontwarn javax.security.sasl.**
-dontwarn javax.naming.**
# On-device vision (photo labels, faces, text): ML Kit finds its components by reflection; without these, an
# optimized build fails on the first InputImage with a null component.
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_** { *; }
-keep class com.google.android.gms.internal.mlkit_common.** { *; }
