package com.example.iap.model

import com.android.billingclient.api.Purchase


//# Event kết quả mua hàng (Success, Canceled, Error)
//Sealed interface cho kết quả giao dịch, dễ dàng xử lý trong when.
sealed interface PurchaseEvent {
    data class Success(val purchase: Purchase, val productId: String) : PurchaseEvent

    data class Failed(val message: String, val responseCode: Int) : PurchaseEvent

    data object UserCanceled : PurchaseEvent
    data object Pending : PurchaseEvent
}