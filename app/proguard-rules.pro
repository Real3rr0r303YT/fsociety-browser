# GeckoView ships its own consumer rules; keep the embedded tor JNI bridge intact.
-keep class org.torproject.jni.** { *; }
-keep class net.freehaven.tor.control.** { *; }
-dontwarn org.mozilla.geckoview.**
