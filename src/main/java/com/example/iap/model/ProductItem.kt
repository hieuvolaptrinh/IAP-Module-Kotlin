package com.example.iap.model

import com.android.billingclient.api.ProductDetails


//# Model chứa thông tin giá hiển thị cho UI
//bọc gọn lại dữ liệu productDetails của google thành model trực quan, ui chỉ việc lấy text ra hiển thị
data class ProductItem(
    val productId: String,
    val title: String,
    val description: String,
    val formattedPrice: String,
    val priceAmountMicros: Long,
    val currencyCode: String,
    val isSubscription: Boolean,
    val basePlanId: String? = null,
    val offerToken: String? = null,
    val originalProductDetails: ProductDetails
)