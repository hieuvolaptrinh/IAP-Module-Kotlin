package com.example.iap.model


// # Cấu hình danh sách ID (App ngoài truyền vào)
data class BillingConfig(
    val subscriptionIds: List<String> = emptyList(),
    val nonConsumableIds: List<String> = emptyList(),
    val consumableIds: List<String> = emptyList(),
    val base64PublicKey: String? = null, // Public key Play Console (nếu muốn verify)
    val enableDebugLogging: Boolean = false
)