# sherpa JNI accesses configuration fields and result constructors by their JVM names.
-keep class com.k2fsa.sherpa.onnx.** { *; }

# Called through GetMethodID from llm_jni.cpp; not visible to the Java reachability graph.
-keepclassmembers class dev.neurostenografist.inference.LocalPunctuation {
    boolean isCancellationRequested();
}
