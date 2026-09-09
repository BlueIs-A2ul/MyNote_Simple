# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.mynote.app.data.backup.** {
    *** Companion;
}
-keepclasseswithmembers class com.mynote.app.data.backup.** {
    kotlinx.serialization.KSerializer serializer(...);
}
