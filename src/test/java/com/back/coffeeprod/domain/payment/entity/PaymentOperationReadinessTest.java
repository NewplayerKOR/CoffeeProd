package com.back.coffeeprod.domain.payment.entity;

import com.back.coffeeprod.domain.member.entity.Member;
import com.back.coffeeprod.domain.member.entity.Role;
import com.back.coffeeprod.domain.order.entity.Orders;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.*;
import java.time.*;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PaymentOperationReadinessTest {
    private PaymentOperation operation() {
        var member = Member.builder().email("readiness@test.invalid").name("fixture").nickname("fixture")
                .password("fixture").role(Role.USER).build();
        var order = Orders.builder().member(member).tossOrderId("COFFEE-readiness").totalPrice(1000)
                .productTotalPrice(1000).deliveryFee(0).usedMileage(0).deliveryAddress("fixture").build();
        return new PaymentOperation(order, PaymentOperation.Type.CONFIRM, "key", "USER", 1L);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("신규 승인과 관리자 즉시 재확인은 DB 소수점 반올림 경계에서도 점유 가능하다")
    void immediateWorkSurvivesTimestampRounding(boolean reconcile) throws Exception {
        Instant boundary = Instant.parse("2026-10-19T00:00:00.123456789Z");
        var op = operation();
        if (reconcile) {
            op.claim(boundary, Duration.ofSeconds(120), false);
            op.unknown("PG_UNKNOWN");
            assertNotNull(op.getNextAttemptAt());
            op.scheduleReconcile();
        }
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:" + UUID.randomUUID())) {
            connection.createStatement().execute("create table due_work(status varchar(24), next_at timestamp(6) with time zone)");
            try (var insert = connection.prepareStatement("insert into due_work values (?, ?)")) {
                insert.setString(1, "CONTROL");
                insert.setObject(2, boundary.atOffset(ZoneOffset.UTC));
                insert.executeUpdate();
                insert.setString(1, op.getStatus().name());
                assertNull(op.getNextAttemptAt());
                insert.setNull(2, Types.TIMESTAMP_WITH_TIMEZONE);
                insert.executeUpdate();
            }
            try (var rows = connection.createStatement().executeQuery("select next_at from due_work where status='CONTROL'")) {
                assertTrue(rows.next());
                Instant rounded = rows.getObject(1, OffsetDateTime.class).toInstant();
                assertTrue(rounded.isAfter(boundary), "이전 즉시 실행 시각은 DB 반올림으로 미래가 될 수 있다");
            }
            try (var query = connection.prepareStatement("select count(*) from due_work where status in ('READY','UNKNOWN') and (next_at is null or next_at <= ?)")) {
                query.setObject(1, boundary.atOffset(ZoneOffset.UTC));
                try (var rows = query.executeQuery()) {
                    assertTrue(rows.next());
                    assertEquals(1, rows.getInt(1));
                }
            }
            assertTrue(op.due(boundary));
        }
    }

    @Test
    @DisplayName("예약된 재시도와 유효한 임대는 즉시 작업 처리로 앞당기지 않는다")
    void backoffAndLeaseKeepTheirDeadlines() {
        var op = operation();
        Instant start = Instant.now();
        op.claim(start, Duration.ofSeconds(120), false);
        assertFalse(op.due(start.plusSeconds(119)));
        assertTrue(op.due(start.plusSeconds(120)));
        op.unknown("PG_UNKNOWN");
        Instant due = op.getNextAttemptAt();
        assertFalse(op.due(due.minusNanos(1)));
        assertTrue(op.due(due));
        op.manual("POST_LIMIT");
        assertFalse(op.due(due.plusSeconds(1000)));
        op.scheduleReconcile();
        assertTrue(op.due(Instant.now()));
    }
}
