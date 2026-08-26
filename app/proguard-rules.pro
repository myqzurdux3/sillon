# kotlinx.serialization garde les serializers generes
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class fr.appprepa.** {
    *** Companion;
}
-keepclasseswithmembers class fr.appprepa.** {
    kotlinx.serialization.KSerializer serializer(...);
}
