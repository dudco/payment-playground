package io.github.dudco.paymentplayground.product

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** 상품 조회 응답. */
data class ProductResponse(val productId: String, val name: String, val price: Long) {

	companion object {
		fun from(product: Product) = ProductResponse(product.id, product.name, product.price)
	}
}

@RestController
@RequestMapping("/products")
class ProductController(private val productCatalog: ProductCatalog) {

	@GetMapping
	fun list(): List<ProductResponse> = productCatalog.findAll().map(ProductResponse::from)

	@GetMapping("/{productId}")
	fun get(@PathVariable productId: String): ProductResponse =
		ProductResponse.from(productCatalog.findById(productId) ?: throw productNotFound())
}
