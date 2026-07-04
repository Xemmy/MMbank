# Minification is disabled for this personal build; rules kept for completeness.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

# kotlinx-serialization
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class com.xemmy.mmbank.** {
    kotlinx.serialization.KSerializer serializer(...);
}
