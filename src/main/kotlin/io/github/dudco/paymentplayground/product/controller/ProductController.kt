package io.github.dudco.paymentplayground.product.controller

import io.github.dudco.paymentplayground.product.service.ProductService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/products")
class ProductController(private val productService: ProductService) {

	@GetMapping
	fun list(): List<ProductResponse> = productService.findAll().map(ProductResponse::from)

	@GetMapping("/{productId}")
	fun get(@PathVariable productId: String): ProductResponse = ProductResponse.from(productService.get(productId))
}
