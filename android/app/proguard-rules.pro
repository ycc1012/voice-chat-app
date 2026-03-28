# Add project specific ProGuard rules here.
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.voicechat.MainActivity$ApiResponse { *; }
-keep class com.google.gson.** { *; }
