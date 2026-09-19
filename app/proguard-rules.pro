# The Shizuku user service is instantiated by NAME through reflection from a process
# that Shizuku starts itself (app_process). R8 cannot see that reference, so the class
# and its no-arg constructor must be kept or release builds fail to bind the service.
-keep class com.audioprobe.ProbeService { public <init>(); *; }

# The AIDL-generated Stub/Proxy pair is referenced by interface name across processes.
-keep class com.audioprobe.IProbeService { *; }
-keep class com.audioprobe.IProbeService$Stub { *; }
-keep class com.audioprobe.IProbeService$Stub$Proxy { *; }

# Shizuku's own entry points (provider + binder plumbing).
-keep class rikka.shizuku.** { *; }
-keep class moe.shizuku.** { *; }
