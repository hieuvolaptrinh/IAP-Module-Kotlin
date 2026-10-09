package com.example.iap

import android.app.Activity
import android.content.Context
import com.example.iap.core.BillingManager
import com.example.iap.core.BillingStorage
import com.example.iap.model.BillingConfig

class IapHelper private constructor(
    context: Context, config: BillingConfig
) {
    private val storage = BillingStorage(context)
    val billingManager = BillingManager(context, config, storage)

    val isPremium: Boolean
        get() = storage.isPremium


    val isPremiumFlow = billingManager.isPremiumFlow
    val productsFlow = billingManager.productsFlow
    val purchaseEvents = billingManager.purchaseEvents


    fun buy(activity: Activity, productId: String) {
        billingManager.launchPurchaseFlow(activity, productId)
    }

    fun restore(onComplete: (Boolean) -> Unit) {
        billingManager.syncPurchases(onComplete)
    }

    companion object {
        @Volatile
        private var instance: IapHelper? = null
        fun init(context: Context, config: BillingConfig): IapHelper {
            return instance ?: synchronized(this) {
                instance ?: IapHelper(context.applicationContext, config).also { instance = it }
            }
        }

        fun getInstance(): IapHelper {
            return instance
                ?: error("IapHelper must be initialized in Application.onCreate() first!")
        }
    }
}