package com.otasdk

data class OTAConfig(
    val appId: String,
    val serverUrl: String,
    val channel: String = "production",
    val crashThreshold: Int = 3,
    val checkOnResume: Boolean = true,
    /**
     * Optional ECDSA P-256 public key (PEM SPKI format) embedded at build time.
     * When set, every downloaded bundle must carry a valid server-side signature.
     * Bundles whose signature is absent or invalid are rejected before being applied.
     *
     * Generate a key pair via the dashboard (Apps → Signing Key), store the
     * private key as OTA_SIGNING_KEY in CI, and paste the public key here.
     */
    val signingPublicKey: String? = null,
)
