# JNI-accessed fields and constructors in the renderer model classes
-keepclassmembers class com.hortus1556.exilum.renderer.model.** {
    <fields>;
    <init>(...);
}

# Native method declarations
-keepclasseswithmembers class com.hortus1556.exilum.renderer.** {
    native <methods>;
}
