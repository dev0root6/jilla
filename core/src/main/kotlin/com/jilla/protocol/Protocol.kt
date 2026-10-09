package com.jilla.protocol

object Protocol {
    const val VERSION = 1
    const val ALERT_ID_BYTES = 16
    const val KEY_ID_BYTES = 8
    const val DEFAULT_TTL = 7
    const val MAX_TTL = 15
    const val MAX_RECIPIENTS = 8
    const val MAX_PACKET_BYTES = 4096
    const val COARSE_GEOHASH_LEN = 5

    /** Alerts older than this are refused as stale/replayed. */
    const val MAX_AGE_MS = 6L * 60 * 60 * 1000

    /** Tolerated clock skew for timestamps in the future. */
    const val MAX_FUTURE_SKEW_MS = 5L * 60 * 1000

    /** Domain separation tag for the identity -> incident binding signature. */
    val BINDING_TAG = "SOSMESH-BIND-v1".toByteArray()
}
