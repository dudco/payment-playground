package io.github.dudco.paymentplayground.customer

/**
 * 요청을 보낸 고객의 ID를 Controller 파라미터(`String`)로 주입받는다.
 *
 * 값을 어디서 가져오는지는 [HeaderCurrentCustomerResolver]가 정한다.
 * 인증을 도입하면 Controller는 그대로 두고 resolver만 교체한다.
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
annotation class CurrentCustomer
