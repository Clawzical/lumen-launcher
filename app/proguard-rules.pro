# kotlinx.serialization: keep generated serializers
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class dev.lumen.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class dev.lumen.**$$serializer { *; }

# Ktor / coroutines reference optional classes that aren't on the desktop classpath
-dontwarn io.ktor.**
-dontwarn kotlinx.coroutines.debug.**
-dontwarn org.slf4j.**
