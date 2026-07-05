package com.easybc.planner.update

object SemVer {
    fun normalize(value: String): String = value.trim().removePrefix("v").removePrefix("V")

    /** Positive when [left] is newer than [right]. */
    fun compare(left: String, right: String): Int {
        val leftParts = normalize(left).split('.').map { it.toIntOrNull() ?: 0 }
        val rightParts = normalize(right).split('.').map { it.toIntOrNull() ?: 0 }
        val length = maxOf(leftParts.size, rightParts.size)
        for (index in 0 until length) {
            val delta = (leftParts.getOrElse(index) { 0 }) - (rightParts.getOrElse(index) { 0 })
            if (delta != 0) return delta
        }
        return 0
    }

    fun isNewer(candidate: String, current: String): Boolean =
        compare(candidate, current) > 0
}
