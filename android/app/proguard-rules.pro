# kotlinx.serialization emits serializers at compile time. Keep generic signatures
# used by Retrofit when resolving suspend response types.
-keepattributes Signature, InnerClasses, EnclosingMethod
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclasseswithmembers,allowshrinking,allowoptimization,includedescriptorclasses class * {
    @retrofit2.http.* <methods>;
}
