package com.example.iap.core

import android.content.Context
import android.content.SharedPreferences

class BillingStorage(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("billing_prefs", Context.MODE_PRIVATE)
    var isPremium: Boolean
        get() = prefs.getBoolean(KEY_IS_PREMIUM, false)
        set(value) = prefs.edit().putBoolean(KEY_IS_PREMIUM, value).apply()

    var lastPurchasedProductId: String?
        get() = prefs.getString(KEY_LAST_PRODUCT, null)
        set(value) = prefs.edit().putString(KEY_LAST_PRODUCT, value).apply()

    companion object {
        private const val KEY_IS_PREMIUM = "key_user_is_premium"
        private const val KEY_LAST_PRODUCT = "key_last_purchased_product"
    }

}