package com.back.coffeeprod.domain.payment.service;

import com.back.coffeeprod.domain.member.entity.Member;
import com.back.coffeeprod.global.exception.CustomException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;

class QaMileageFormulaTest {
    @ParameterizedTest @CsvSource({"0,0,0,1000,0,1000","200,500,0,1000,0,300",
            "0,500,300,0,200,0","0,200,500,0,0,300","500,0,0,100,400,0"})
    @DisplayName("AT1-A12 잔액 사용분 대기액 적립분의 정산 산식과 명시된 예시가 일치한다")
    void formula(int balance,int used,int due,int earned,int expectedBalance,int expectedDue) {
        var m=Member.builder().email("qa@test.invalid").name("QA").nickname("QA").password("QA").build();
        ReflectionTestUtils.setField(m,"mileage",balance);ReflectionTestUtils.setField(m,"mileageRecoveryDue",due);
        m.settleMileage(used,earned);assertEquals(expectedBalance,m.getMileage());assertEquals(expectedDue,m.getMileageRecoveryDue());
    }
    @Test @DisplayName("AT1-A12 정수 범위를 넘는 적립과 음수 입력은 기존 잔액을 변경하지 않는다")
    void overflow() {
        var m=Member.builder().build();m.addMileage(Integer.MAX_VALUE);
        assertThrows(CustomException.class,()->m.addMileage(1));assertEquals(Integer.MAX_VALUE,m.getMileage());
        assertThrows(CustomException.class,()->m.settleMileage(-1,0));assertEquals(Integer.MAX_VALUE,m.getMileage());
    }
}
