// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.radhwanalhajjaj.spectrasdr.core.dsp

/** Order statistics without sorting and without allocating, for use in the signal path (§4.4). */
object Select {
    /**
     * The value that would be at index [k] if the first [n] elements of [a] were sorted. Those
     * elements are reordered. The cost is linear on average (quickselect, median-of-three pivot).
     */
    fun kth(a: FloatArray, n: Int, k: Int): Float {
        require(n <= a.size && k in 0 until n) { "k = $k is outside 0 until $n" }
        var lo = 0
        var hi = n - 1
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (a[mid] < a[lo]) swap(a, lo, mid)
            if (a[hi] < a[lo]) swap(a, lo, hi)
            if (a[hi] < a[mid]) swap(a, mid, hi)
            val pivot = a[mid]
            var i = lo
            var j = hi
            while (i <= j) {
                while (a[i] < pivot) i++
                while (a[j] > pivot) j--
                if (i <= j) {
                    swap(a, i, j)
                    i++
                    j--
                }
            }
            // Everything up to j is at most the pivot, everything from i at least the pivot.
            if (k <= j) hi = j else if (k >= i) lo = i else return a[k]
        }
        return a[k]
    }

    private fun swap(a: FloatArray, i: Int, j: Int) {
        val t = a[i]
        a[i] = a[j]
        a[j] = t
    }
}
