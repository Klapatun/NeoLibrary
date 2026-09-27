# Add project specific ProGuard rules here.
# Keep our model and db classes (used via reflection-free code, but safe to keep).
-keep class com.example.mylibrary.model.** { *; }
