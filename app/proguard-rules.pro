# ProGuard rules for Phone Compute Worker
# Sprint 1: minimal — keep gRPC and Kotlin classes.

# Keep gRPC generated classes
-keep class io.grpc.** { *; }
-dontwarn io.grpc.**

# Keep protobuf lite
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.protobuf.**

# Keep Kotlin coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# Keep Timber
-keep class timber.log.** { *; }
