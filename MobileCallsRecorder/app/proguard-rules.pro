# Add project specific ProGuard rules here.
# Keep Room entities
-keep class com.callrecorder.app.data.model.** { *; }

# Keep Hilt generated classes
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
