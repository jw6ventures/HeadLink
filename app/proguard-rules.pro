# Keep rules for the release build. Minification is off (app/build.gradle.kts), so today
# these change nothing; they are written before they are needed, because every failure
# they prevent is at run time, not at build time.
#
# Each rule names a lookup by string. Anything reached only by name has to stay under
# that name, and R8 cannot see the reference.

# BRouter's path model: RoutingContext.setModel instantiates the class a .brf profile
# names ("---model:btools.router.KinematicModel" in car-vario.brf) with Class.forName and
# its no-argument constructor. Renamed or removed, routing fails at the first route with
# "Cannot create path-model". Kept for every model, not just the one car-vario names, so
# a different profile cannot reintroduce the failure.
-keep class * extends btools.router.OsmPathModel {
    <init>();
}

# libaap_jni finds NativeBridge by class name and registers its natives by method name
# and signature (core/aap-jni/src/lib.rs, BRIDGE_CLASS and RegisterNatives). A renamed
# native fails RegisterNatives, and the library then refuses to load.
-keep class com.jw6ventures.headlink.NativeBridge {
    native <methods>;
}

# The session callback is looked up by GetMethodID on the callback object's class, as
# onNativeEvent (JI[B)V (CALLBACK_METHOD, CALLBACK_SIGNATURE). Its implementations are
# lambdas, so the interface and every implementation keep the name.
-keep interface com.jw6ventures.headlink.SessionCallback {
    void onNativeEvent(long, int, byte[]);
}
-keepclassmembers class * implements com.jw6ventures.headlink.SessionCallback {
    void onNativeEvent(long, int, byte[]);
}

# VTM annotates a few renderer methods with javax.annotation.CheckReturnValue, which is
# compile-time only and not on the classpath. R8 treats the missing class as an error
# and refuses to build; an annotation with no class behind it changes nothing at run time.
-dontwarn javax.annotation.**
