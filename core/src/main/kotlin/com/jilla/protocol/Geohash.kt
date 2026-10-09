package com.jilla.protocol

/** Standard base32 geohash. Precision 5 is roughly a 4.9 km x 4.9 km cell. */
object Geohash {
    private const val BASE32 = "0123456789bcdefghjkmnpqrstuvwxyz"

    fun encode(lat: Double, lng: Double, precision: Int = Protocol.COARSE_GEOHASH_LEN): String {
        var latLo = -90.0; var latHi = 90.0
        var lngLo = -180.0; var lngHi = 180.0
        val sb = StringBuilder()
        var even = true
        var bit = 0
        var ch = 0
        while (sb.length < precision) {
            if (even) {
                val mid = (lngLo + lngHi) / 2
                if (lng >= mid) { ch = (ch shl 1) or 1; lngLo = mid } else { ch = ch shl 1; lngHi = mid }
            } else {
                val mid = (latLo + latHi) / 2
                if (lat >= mid) { ch = (ch shl 1) or 1; latLo = mid } else { ch = ch shl 1; latHi = mid }
            }
            even = !even
            if (++bit == 5) { sb.append(BASE32[ch]); bit = 0; ch = 0 }
        }
        return sb.toString()
    }

    /** Center of a geohash cell plus half-size in degrees (lat, lng). */
    data class Cell(val lat: Double, val lng: Double, val latErr: Double, val lngErr: Double)

    fun decode(hash: String): Cell {
        var latLo = -90.0; var latHi = 90.0
        var lngLo = -180.0; var lngHi = 180.0
        var even = true
        for (c in hash) {
            val v = BASE32.indexOf(c)
            require(v >= 0) { "bad geohash char $c" }
            for (bit in 4 downTo 0) {
                val on = (v shr bit) and 1 == 1
                if (even) { val m = (lngLo + lngHi) / 2; if (on) lngLo = m else lngHi = m }
                else { val m = (latLo + latHi) / 2; if (on) latLo = m else latHi = m }
                even = !even
            }
        }
        return Cell((latLo + latHi) / 2, (lngLo + lngHi) / 2, (latHi - latLo) / 2, (lngHi - lngLo) / 2)
    }

    fun isValidCoarse(s: String) = s.length <= Protocol.COARSE_GEOHASH_LEN && s.all { it in BASE32 }
}
