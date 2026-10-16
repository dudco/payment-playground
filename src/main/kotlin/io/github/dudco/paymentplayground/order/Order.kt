package io.github.dudco.paymentplayground.order

import io.github.dudco.paymentplayground.support.InstantTextConverter
import jakarta.persistence.CascadeType
import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.OneToMany
import jakarta.persistence.OrderBy
import jakarta.persistence.Table
import java.time.Instant

/** 주문 상태. `PAID`는 결제 스펙에서 추가한다. */
enum class OrderStatus { CREATED }

/** 주문에 담긴 상품 한 줄. 주문 시점의 상품명과 단가를 스냅샷으로 보관한다. */
@Entity
@Table(name = "order_lines")
class OrderLine(
	productId: String,
	productName: String,
	unitPrice: Long,
	quantity: Int,
) {
	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	@Column(name = "id", columnDefinition = "TEXT")
	var id: String? = null
		protected set

	@Column(name = "product_id", nullable = false, columnDefinition = "TEXT")
	var productId: String = productId
		protected set

	@Column(name = "product_name", nullable = false, columnDefinition = "TEXT")
	var productName: String = productName
		protected set

	@Column(name = "unit_price", nullable = false, columnDefinition = "INTEGER")
	var unitPrice: Long = unitPrice
		protected set

	@Column(name = "quantity", nullable = false, columnDefinition = "INTEGER")
	var quantity: Int = quantity
		protected set

	@Column(name = "line_amount", nullable = false, columnDefinition = "INTEGER")
	var lineAmount: Long = unitPrice * quantity
		protected set

	init {
		require(productId.isNotBlank()) { "productId는 비어 있을 수 없습니다." }
		require(productName.isNotBlank()) { "productName은 비어 있을 수 없습니다." }
		require(unitPrice >= 0) { "단가는 0 이상이어야 합니다." }
		require(quantity in MIN_QUANTITY..MAX_QUANTITY) {
			"수량은 $MIN_QUANTITY 이상 $MAX_QUANTITY 이하여야 합니다."
		}
	}

	companion object {
		const val MIN_QUANTITY = 1
		const val MAX_QUANTITY = 99
	}
}

/** 주문. 생성 후 바뀌면 안 되는 값은 `protected set`으로 막고, 라인은 상품 ID 오름차순으로 담는다. */
@Entity(name = "CustomerOrder")
@Table(name = "orders")
class Order(
	idempotencyKey: String,
	customerId: String,
	lines: List<OrderLine>,
	now: Instant,
) {
	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	@Column(name = "id", columnDefinition = "TEXT")
	var id: String? = null
		protected set

	@Column(name = "idempotency_key", nullable = false, unique = true, columnDefinition = "TEXT")
	var idempotencyKey: String = idempotencyKey
		protected set

	@Column(name = "customer_id", nullable = false, columnDefinition = "TEXT")
	var customerId: String = customerId
		protected set

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, columnDefinition = "TEXT")
	var status: OrderStatus = OrderStatus.CREATED
		protected set

	@OneToMany(cascade = [CascadeType.ALL])
	@JoinColumn(name = "order_id", nullable = false, updatable = false, columnDefinition = "TEXT")
	@OrderBy("productId ASC")
	var lines: List<OrderLine> = lines.sortedBy { it.productId }
		protected set

	@Column(name = "total_amount", nullable = false, columnDefinition = "INTEGER")
	var totalAmount: Long = lines.sumOf { it.lineAmount }
		protected set

	@Convert(converter = InstantTextConverter::class)
	@Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "TEXT")
	var createdAt: Instant = now
		protected set

	@Convert(converter = InstantTextConverter::class)
	@Column(name = "updated_at", nullable = false, columnDefinition = "TEXT")
	var updatedAt: Instant = now
		protected set

	init {
		require(idempotencyKey.isNotBlank()) { "idempotencyKey는 비어 있을 수 없습니다." }
		require(customerId.isNotBlank()) { "customerId는 비어 있을 수 없습니다." }
		require(lines.isNotEmpty()) { "주문 라인은 비어 있을 수 없습니다." }
		require(lines.map { it.productId }.toSet().size == lines.size) { "같은 상품을 두 번 담을 수 없습니다." }
	}
}
