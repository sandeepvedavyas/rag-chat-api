package com.sandeepvedavyas.rag.retrieval

import kotlin.math.sqrt

object Vectors {
    fun normalize(values: FloatArray): FloatArray {
        var sumSquares = 0.0
        for (value in values) {
            sumSquares += value.toDouble() * value.toDouble()
        }
        if (sumSquares == 0.0) {
            return FloatArray(values.size)
        }
        val inverse = 1.0 / sqrt(sumSquares)
        return FloatArray(values.size) { index -> (values[index] * inverse).toFloat() }
    }

    fun dot(left: FloatArray, right: FloatArray): Double {
        require(left.size == right.size) { "Vector lengths ${left.size} and ${right.size} differ" }
        var sum = 0.0
        for (index in left.indices) {
            sum += left[index] * right[index]
        }
        return sum
    }
}
