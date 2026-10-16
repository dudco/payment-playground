package io.github.dudco.paymentplayground.product.controller

import io.github.dudco.paymentplayground.product.repository.FixedProductRepository
import io.github.dudco.paymentplayground.product.service.ProductService
import org.hamcrest.Matchers.contains
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@WebMvcTest(controllers = [ProductController::class])
@Import(ProductService::class, FixedProductRepository::class)
class ProductControllerTest(@Autowired private val mockMvc: MockMvc) {

	@Test
	fun `상품 목록은 Mock 카탈로그 전체를 정의된 순서로 반환한다`() {
		mockMvc.perform(get("/products"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(5))
			.andExpect(jsonPath("$[0].productId").value("americano"))
			.andExpect(jsonPath("$[0].name").value("아메리카노"))
			.andExpect(jsonPath("$[0].price").value(4500))
			.andExpect(jsonPath("$[*].productId").value(contains("americano", "latte", "cake", "sandwich", "cookie")))
	}

	@Test
	fun `상품 단건을 조회한다`() {
		mockMvc.perform(get("/products/cake"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.productId").value("cake"))
			.andExpect(jsonPath("$.name").value("케이크"))
			.andExpect(jsonPath("$.price").value(6000))
	}

	@Test
	fun `없는 상품은 404 PRODUCT_NOT_FOUND다`() {
		mockMvc.perform(get("/products/unknown"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"))
	}
}
