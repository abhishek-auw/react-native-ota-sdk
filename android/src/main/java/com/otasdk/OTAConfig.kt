package com.otasdk

data class OTAConfig(
    val appId: String,
    val serverUrl: String,
    val channel: String = "production",
    val crashThreshold: Int = 3,
    val checkOnResume: Boolean = true,
)
