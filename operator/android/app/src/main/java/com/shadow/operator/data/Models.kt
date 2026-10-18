package com.shadow.operator.data

import com.shadow.operator.BuildConfig

data class ClientConfig(
    val workerUrl: String = BuildConfig.WORKER_URL,
    val implantId: String = "02edfcf2-371d-3101-a6c3-05110c4651bf",
    val operatorPubKey: String = "",
    val operatorPrivKey: String = BuildConfig.OPERATOR_PRIV_KEY ?: "",
    val autoReconnect: Boolean = true,
    val reconnectIntervalSec: Int = 30
)
