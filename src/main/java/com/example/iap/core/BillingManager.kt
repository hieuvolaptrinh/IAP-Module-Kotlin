package com.example.iap.core

import android.app.Activity
import android.content.Context
import android.util.Log
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ConsumeParams
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.example.iap.model.BillingConfig
import com.example.iap.model.ProductItem
import com.example.iap.model.PurchaseEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.log

class BillingManager(
    private val context: Context,
    private val config: BillingConfig,
    private val storage: BillingStorage
) : BillingClientStateListener, PurchasesUpdatedListener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var billingClient: BillingClient? = null

    //StateFlow trạng thái vip
    private val _isPremiumFLow = MutableStateFlow(storage.isPremium)
    val isPremiumFlow = _isPremiumFLow.asStateFlow()

    //statèlơ trạng thái sản phẩm, giá tiền
    private val _productsFlow = MutableStateFlow<Map<String, ProductItem>>(emptyMap())
    val productsFlow = _productsFlow.asStateFlow()

    //  bắn sự kiện mua hàng (1- shot event)
    private val _purchaseEvents = MutableSharedFlow<PurchaseEvent>()
    val purchaseEvents = _purchaseEvents.asSharedFlow()

    init {
        setupBillingClient()
    }

    private fun setupBillingClient() {
        billingClient = BillingClient.newBuilder(context)
            .setListener(this)
            .enablePendingPurchases(
                PendingPurchasesParams.newBuilder()
                    .enableOneTimeProducts().build()
            )
            .enableAutoServiceReconnection() // tính năng tự kết nối lại của Billing v9
            .build()

        startConnection()
    }

    fun startConnection() {
        billingClient?.let { client ->
            if (!client.isReady) {
                client.startConnection(this)
            }
        }
    }

    override fun onBillingServiceDisconnected() {
        TODO("Not yet implemented")

    }

    override fun onBillingSetupFinished(billingResult: BillingResult) {
        if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
            log("Billing connected successfully.")
            // 1. dồng bộ lại gói người dùng đang sở hữu
            syncPurchases()
            // 2. Tải thông tin giá từ Play Store
            queryAllProductDetails()
        } else {
            log("Billing setup failed: ${billingResult.debugMessage}")
        }
    }


    private fun queryAllProductDetails() {
        val client = billingClient ?: return
        if (!client.isReady) return
        val productList = mutableListOf<QueryProductDetailsParams.Product>()
        config.subscriptionIds.forEach { id ->
            productList.add(
                QueryProductDetailsParams.Product.newBuilder()
                    .setProductId(id)
                    .setProductType(BillingClient.ProductType.SUBS)
                    .build()
            )
        }
        (config.nonConsumableIds + config.consumableIds).forEach { id ->
            productList.add(
                QueryProductDetailsParams.Product.newBuilder()
                    .setProductId(id)
                    .setProductType(BillingClient.ProductType.INAPP)
                    .build()
            )
        }
        if (productList.isEmpty()) return
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(productList)
            .build()
        client.queryProductDetailsAsync(params) { result, queryResult ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                val itemsMap = mutableMapOf<String, ProductItem>()
                for (details in queryResult.productDetailsList) {
                    val item = parseProductItem(details)
                    if (item != null) {
                        itemsMap[details.productId] = item
                    }
                }
                _productsFlow.value = itemsMap
                log("Fetched ${itemsMap.size} products.")
            }
        }
    }

    private fun parseProductItem(details: ProductDetails): ProductItem? {
        return if (details.productType == BillingClient.ProductType.SUBS) {
            // Lấy ưu đãi tốt nhất hoặc base plan mặc định
            val offers = details.subscriptionOfferDetails ?: return null
            val selectedOffer = offers.firstOrNull() ?: return null
            val pricingPhase =
                selectedOffer.pricingPhases.pricingPhaseList.firstOrNull() ?: return null
            ProductItem(
                productId = details.productId,
                title = details.title,
                description = details.description,
                formattedPrice = pricingPhase.formattedPrice,
                priceAmountMicros = pricingPhase.priceAmountMicros,
                currencyCode = pricingPhase.priceCurrencyCode,
                isSubscription = true,
                basePlanId = selectedOffer.basePlanId,
                offerToken = selectedOffer.offerToken,
                originalProductDetails = details
            )
        } else {
            val oneTime = details.oneTimePurchaseOfferDetails ?: return null
            ProductItem(
                productId = details.productId,
                title = details.title,
                description = details.description,
                formattedPrice = oneTime.formattedPrice,
                priceAmountMicros = oneTime.priceAmountMicros,
                currencyCode = oneTime.priceCurrencyCode,
                isSubscription = false,
                originalProductDetails = details
            )
        }
    }

    /**
     * Mở màn hình thanh toán của Google Play
     */
    fun launchPurchaseFlow(activity: Activity, productId: String) {
        val client = billingClient
        if (client == null || !client.isReady) {
            scope.launch { _purchaseEvents.emit(PurchaseEvent.Failed("Billing not ready", -1)) }
            return
        }
        val item = _productsFlow.value[productId]
        if (item == null) {
            scope.launch { _purchaseEvents.emit(PurchaseEvent.Failed("Product not found", -1)) }
            return
        }
        val productDetailsParams = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(item.originalProductDetails)
        if (item.isSubscription && item.offerToken != null) {
            productDetailsParams.setOfferToken(item.offerToken)
        }
        val flowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(productDetailsParams.build()))
            .build()
        client.launchBillingFlow(activity, flowParams)
    }

    override fun onPurchasesUpdated(billingResult: BillingResult, purchases: List<Purchase>?) {
        when (billingResult.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                if (purchases != null) {
                    for (purchase in purchases) {
                        handlePurchase(purchase)
                    }
                }
            }

            BillingClient.BillingResponseCode.USER_CANCELED -> {
                scope.launch { _purchaseEvents.emit(PurchaseEvent.UserCanceled) }
            }

            else -> {
                scope.launch {
                    _purchaseEvents.emit(
                        PurchaseEvent.Failed(billingResult.debugMessage, billingResult.responseCode)
                    )
                }
            }
        }
    }

    private fun handlePurchase(purchase: Purchase) {
        if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {
            val productId = purchase.products.firstOrNull().orEmpty()

            // Xử lý Consumable nếu có
            if (config.consumableIds.contains(productId)) {
                consumePurchase(purchase)
            } else {
                // Subscription và Non-Consumable bắt buộc phải Acknowledge
                if (!purchase.isAcknowledged) {
                    acknowledgePurchase(purchase, productId)
                } else {
                    updatePremiumState(true, productId)
                    scope.launch {
                        _purchaseEvents.emit(
                            PurchaseEvent.Success(
                                purchase,
                                productId
                            )
                        )
                    }
                }
            }
        } else if (purchase.purchaseState == Purchase.PurchaseState.PENDING) {
            scope.launch { _purchaseEvents.emit(PurchaseEvent.Pending) }
        }
    }

    private fun acknowledgePurchase(purchase: Purchase, productId: String) {
        val params = AcknowledgePurchaseParams.newBuilder()
            .setPurchaseToken(purchase.purchaseToken)
            .build()
        billingClient?.acknowledgePurchase(params) { result ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                updatePremiumState(true, productId)
                scope.launch { _purchaseEvents.emit(PurchaseEvent.Success(purchase, productId)) }
            } else {
                scope.launch {
                    _purchaseEvents.emit(
                        PurchaseEvent.Failed(
                            "Acknowledge failed",
                            result.responseCode
                        )
                    )
                }
            }
        }
    }

    /**
     * Restore hoặc đồng bộ giao dịch
     */
    fun syncPurchases(onComplete: ((Boolean) -> Unit)? = null) {
        val client = billingClient
        if (client == null || !client.isReady) {
            onComplete?.invoke(false)
            return
        }
        // Kiểm tra Subscriptions
        client.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.SUBS)
                .build()
        ) { resultSubs, subsPurchases ->
            val hasActiveSubs = subsPurchases.any {
                it.purchaseState == Purchase.PurchaseState.PURCHASED && it.isAcknowledged
            }
            // Kiểm tra In-App Non-consumables
            client.queryPurchasesAsync(
                QueryPurchasesParams.newBuilder()
                    .setProductType(BillingClient.ProductType.INAPP)
                    .build()
            ) { resultInApp, inAppPurchases ->
                val hasActiveInApp = inAppPurchases.any {
                    it.purchaseState == Purchase.PurchaseState.PURCHASED &&
                            it.isAcknowledged &&
                            config.nonConsumableIds.any { id -> it.products.contains(id) }
                }
                val isVip = hasActiveSubs || hasActiveInApp
                updatePremiumState(isVip)
                onComplete?.invoke(isVip)
            }
        }
    }

    private fun consumePurchase(purchase: Purchase) {
        val params = ConsumeParams.newBuilder()
            .setPurchaseToken(purchase.purchaseToken)
            .build()
        billingClient?.consumeAsync(params) { _, _ ->
            // Mua vật phẩm tiêu hao xong thì emit success
            scope.launch {
                _purchaseEvents.emit(
                    PurchaseEvent.Success(
                        purchase,
                        purchase.products.firstOrNull().orEmpty()
                    )
                )
            }
        }
    }

    private fun updatePremiumState(isVip: Boolean, productId: String? = null) {
        storage.isPremium = isVip
        if (productId != null) storage.lastPurchasedProductId = productId
        _isPremiumFLow.value = isVip
    }

    private fun log(msg: String) {
        if (config.enableDebugLogging) Log.d("Iap", msg)
    }

    fun destroy() {
        scope.cancel()
        billingClient?.endConnection()
        billingClient = null
    }
}