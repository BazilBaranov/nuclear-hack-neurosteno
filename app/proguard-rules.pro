# sherpa JNI accesses configuration fields and result constructors by their JVM names.
-keep class com.k2fsa.sherpa.onnx.** { *; }

# Called through GetMethodID from llm_jni.cpp; not visible to the Java reachability graph.
-keepclassmembers class dev.neurostenografist.inference.LocalPunctuation {
    boolean isCancellationRequested();
}

# LiteRT-LM looks up this exception by name when a native backend fails.
-keep class com.google.ai.edge.litertlm.LiteRtLmJniException { *; }
# The locally packaged ONNX Runtime Java API uses JNI callbacks by class and member name.
-keep class ai.onnxruntime.** { *; }
