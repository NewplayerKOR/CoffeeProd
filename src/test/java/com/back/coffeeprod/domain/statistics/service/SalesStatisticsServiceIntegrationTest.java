package com.back.coffeeprod.domain.statistics.service;

import com.back.coffeeprod.domain.address.repository.AddressRepository;
import com.back.coffeeprod.domain.cart.repository.CartItemRepository;
import com.back.coffeeprod.domain.cart.repository.CartRepository;
import com.back.coffeeprod.domain.member.entity.Member;
import com.back.coffeeprod.domain.member.entity.Role;
import com.back.coffeeprod.domain.member.repository.MemberRepository;
import com.back.coffeeprod.domain.order.entity.Orders;
import com.back.coffeeprod.domain.order.repository.OrderRepository;
import com.back.coffeeprod.domain.payment.entity.Payment;
import com.back.coffeeprod.domain.payment.entity.PaymentStatus;
import com.back.coffeeprod.domain.payment.repository.PaymentRepository;
import com.back.coffeeprod.domain.product.repository.CategoryRepository;
import com.back.coffeeprod.domain.product.repository.ProductRepository;
import com.back.coffeeprod.domain.qna.repository.QnaRepository;
import com.back.coffeeprod.domain.review.repository.ReviewRepository;
import com.back.coffeeprod.domain.statistics.dto.SalesStatisticsDto;
import com.back.coffeeprod.domain.statistics.entity.SalesStatistics;
import com.back.coffeeprod.domain.statistics.entity.SalesStatisticsUnit;
import com.back.coffeeprod.domain.statistics.repository.SalesStatisticsRepository;
import com.back.coffeeprod.global.common.time.BusinessTime;
import com.back.coffeeprod.global.exception.CustomException;
import com.back.coffeeprod.global.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:testdb;MODE=PostgreSQL;INIT=CREATE DOMAIN IF NOT EXISTS TIMESTAMPTZ AS TIMESTAMP WITH TIME ZONE",
        "spring.data.redis.host=localhost",
        "spring.data.redis.port=6379",
        "spring.data.redis.password=test",
        "jwt.secret-key=coffeeprod-test-secret-key-32bytes-minimum",
        "jwt.access-expiration=1800000",
        "jwt.refresh-expiration=1209600000",
        "pg.toss.client-key=test-client-key",
        "pg.toss.secret-key=test-secret-key"
})
class SalesStatisticsServiceIntegrationTest {

    private final SalesStatisticsService salesStatisticsService;
    private final SalesStatisticsRepository salesStatisticsRepository;
    private final PaymentRepository paymentRepository;
    private final ReviewRepository reviewRepository;
    private final QnaRepository qnaRepository;
    private final OrderRepository orderRepository;
    private final CartItemRepository cartItemRepository;
    private final CartRepository cartRepository;
    private final AddressRepository addressRepository;
    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final MemberRepository memberRepository;
    private int sequence;

    @Autowired
    SalesStatisticsServiceIntegrationTest(
            SalesStatisticsService salesStatisticsService,
            SalesStatisticsRepository salesStatisticsRepository,
            PaymentRepository paymentRepository,
            ReviewRepository reviewRepository,
            QnaRepository qnaRepository,
            OrderRepository orderRepository,
            CartItemRepository cartItemRepository,
            CartRepository cartRepository,
            AddressRepository addressRepository,
            ProductRepository productRepository,
            CategoryRepository categoryRepository,
            MemberRepository memberRepository
    ) {
        this.salesStatisticsService = salesStatisticsService;
        this.salesStatisticsRepository = salesStatisticsRepository;
        this.paymentRepository = paymentRepository;
        this.reviewRepository = reviewRepository;
        this.qnaRepository = qnaRepository;
        this.orderRepository = orderRepository;
        this.cartItemRepository = cartItemRepository;
        this.cartRepository = cartRepository;
        this.addressRepository = addressRepository;
        this.productRepository = productRepository;
        this.categoryRepository = categoryRepository;
        this.memberRepository = memberRepository;
    }

    @BeforeEach
    void setUp() {
        sequence = 0;
        paymentRepository.deleteAll();
        reviewRepository.deleteAll();
        qnaRepository.deleteAll();
        orderRepository.deleteAll();
        cartItemRepository.deleteAll();
        cartRepository.deleteAll();
        addressRepository.deleteAll();
        productRepository.deleteAll();
        categoryRepository.deleteAll();
        salesStatisticsRepository.deleteAll();
        memberRepository.deleteAll();
    }

    @Test
    void aggregateDailySales_includesOnlySuccessfulPaymentsWithinBusinessDay() {
        LocalDate statDate = LocalDate.of(2026, 7, 8);
        Member member = saveMember();
        savePayment(member, statDate, PaymentStatus.SUCCESS, 20_000, 3_000, 1_000, 22_000);
        savePayment(member, statDate, PaymentStatus.FAILED, 50_000, 0, 0, 50_000);
        savePayment(member, statDate.minusDays(1), PaymentStatus.SUCCESS, 30_000, 0, 0, 30_000);

        salesStatisticsService.aggregateDailySales(statDate);

        SalesStatistics statistics = salesStatisticsRepository.findByStatDate(statDate).orElseThrow();
        assertEquals(1, statistics.getOrderCount());
        assertEquals(20_000, statistics.getProductSalesAmount());
        assertEquals(3_000, statistics.getDeliveryFeeAmount());
        assertEquals(1_000, statistics.getUsedMileageAmount());
        assertEquals(22_000, statistics.getPaymentAmount());

        salesStatisticsService.aggregateDailySales(statDate);
        assertEquals(1, salesStatisticsRepository.count());
    }

    @Test
    void aggregateSalesRange_rejectsInvalidDateRange() {
        CustomException reversedRange = assertThrows(CustomException.class, () ->
                salesStatisticsService.aggregateSalesRange(
                        LocalDate.of(2026, 7, 10),
                        LocalDate.of(2026, 7, 9)
                )
        );
        assertEquals(ErrorCode.INVALID_STATISTICS_DATE_RANGE, reversedRange.getErrorCode());

        LocalDate from = LocalDate.of(2025, 1, 1);
        CustomException oversizedRange = assertThrows(CustomException.class, () ->
                salesStatisticsService.aggregateSalesRange(from, from.plus(366, ChronoUnit.DAYS))
        );
        assertEquals(ErrorCode.STATISTICS_DATE_RANGE_TOO_LARGE, oversizedRange.getErrorCode());
    }

    @Test
    void getSalesStatistics_groupsDailyRowsByMonth() {
        salesStatisticsRepository.save(new SalesStatistics(
                LocalDate.of(2026, 7, 1), 2, 30_000, 3_000, 1_000, 32_000
        ));
        salesStatisticsRepository.save(new SalesStatistics(
                LocalDate.of(2026, 7, 2), 1, 15_000, 0, 0, 15_000
        ));
        salesStatisticsRepository.save(new SalesStatistics(
                LocalDate.of(2026, 8, 1), 1, 10_000, 3_000, 0, 13_000
        ));

        List<SalesStatisticsDto.Response> response = salesStatisticsService.getSalesStatistics(
                LocalDate.of(2026, 7, 1),
                LocalDate.of(2026, 8, 1),
                SalesStatisticsUnit.MONTHLY
        );

        assertEquals(2, response.size());
        assertEquals("2026-07", response.get(0).getLabel());
        assertEquals(3, response.get(0).getOrderCount());
        assertEquals(45_000, response.get(0).getProductSalesAmount());
        assertEquals(47_000, response.get(0).getPaymentAmount());
        assertEquals("2026-08", response.get(1).getLabel());
        assertEquals(13_000, response.get(1).getPaymentAmount());
    }

    private Member saveMember() {
        int current = ++sequence;
        return memberRepository.save(Member.builder()
                .email("statistics" + current + "@test.com")
                .password("encoded-password")
                .name("Statistics Tester")
                .nickname("statisticsUser" + current)
                .role(Role.USER)
                .build());
    }

    private void savePayment(
            Member member,
            LocalDate paidDate,
            PaymentStatus status,
            int productTotalPrice,
            int deliveryFee,
            int usedMileage,
            int totalPrice
    ) {
        int current = ++sequence;
        Orders orders = orderRepository.save(Orders.builder()
                .member(member)
                .tossOrderId("statistics-order-" + current)
                .productTotalPrice(productTotalPrice)
                .deliveryFee(deliveryFee)
                .usedMileage(usedMileage)
                .totalPrice(totalPrice)
                .deliveryAddress("Seoul")
                .build());

        Payment payment = Payment.builder()
                .orders(orders)
                .pgProvider("TOSSPAYMENTS")
                .paymentKey("statistics-payment-" + current)
                .payMethod("CARD")
                .status(status)
                .build();
        Instant paidAt = BusinessTime.startOfDay(paidDate).plus(12, ChronoUnit.HOURS);
        ReflectionTestUtils.setField(payment, "paidAt", paidAt);
        paymentRepository.save(payment);
    }
}
