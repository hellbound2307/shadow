package com.shadow.operator.data

import com.shadow.operator.BuildConfig

data class ClientConfig(
    val workerUrl: String = "wss://shadow-worker.marwannaili-23-07.workers.dev",
    val implantId: String = "a5b43ef8-eb9b-4f16-89a6-a700978beb18",
    val operatorPubKey: String = "",
    val operatorPrivKey: String = BuildConfig.OPERATOR_PRIV_KEY ?: "",
    val autoReconnect: Boolean = true,
    val reconnectIntervalSec: Int = 30
)
