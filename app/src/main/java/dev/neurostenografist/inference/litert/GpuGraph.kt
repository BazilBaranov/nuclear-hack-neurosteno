package dev.neurostenografist.inference.litert

import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.TensorBuffer
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal class GpuGraph(
    file: File,
    inputNames: List<String>,
    outputName: String,
) : AutoCloseable {
    private val options = CompiledModel.Options(Accelerator.GPU).apply {
        gpuOptions = CompiledModel.GpuOptions(precision = CompiledModel.GpuOptions.Precision.FP32)
    }
    private val model = CompiledModel.create(file.absolutePath, options, null)
    private val input = linkedMapOf<String, TensorBuffer>()
    private val output = linkedMapOf<String, TensorBuffer>()

    init {
        try {
            for (name in inputNames) input[name] = model.createInputBuffer(name)
            output[outputName] = model.createOutputBuffer(outputName)
        } catch (failure: Throwable) {
            close()
            throw failure
        }
    }

    fun write(name: String, values: FloatArray) = input.getValue(name).writeFloat(values)

    fun run(): FloatArray {
        model.run(input, output)
        return output.values.single().readFloat()
    }

    override fun close() {
        try {
            input.values.forEach { it.close() }
            output.values.forEach { it.close() }
        } finally {
            model.close()
        }
    }
}

internal fun File.readLittleEndianFloats(count: Int): FloatArray {
    require(length() == count.toLong() * Float.SIZE_BYTES) { "Unexpected tensor size: $name" }
    return FloatArray(count).also {
        ByteBuffer.wrap(readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(it)
    }
}
