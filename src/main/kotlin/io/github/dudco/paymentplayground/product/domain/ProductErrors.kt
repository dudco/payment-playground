package io.github.dudco.paymentplayground.product.domain

import io.github.dudco.paymentplayground.support.NotFoundException

/** 없는 상품에 대한 `404 PRODUCT_NOT_FOUND` 오류. */
fun productNotFound(): NotFoundException = NotFoundException("PRODUCT_NOT_FOUND", "상품을 찾을 수 없습니다.")
