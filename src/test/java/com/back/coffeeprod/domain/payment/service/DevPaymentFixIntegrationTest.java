package com.back.coffeeprod.domain.payment.service;

import com.back.coffeeprod.domain.member.entity.Member;
import com.back.coffeeprod.domain.member.entity.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** DEV regressions extending the unchanged QA HTTP harness. */
class DevPaymentFixIntegrationTest extends QaPaymentSupport {
    @Test
    @DisplayName("상품 등록 재고는 저장하고 정보 수정의 절대 재고값은 적용하지 않는다")
    void productInformationUpdatesPreserveStockOverHttp() throws Exception {
        String admin = admin();
        var fixture = fixture();
        Long categoryId = products.findById(fixture.product()).orElseThrow().getCategory().getId();
        String sku = "DEV-" + UUID.randomUUID().toString().toUpperCase();
        String create = productRequest(categoryId, sku, "등록 상품", 6);
        var response = http("POST", "/api/v1/admin/products", create, admin);
        assertEquals(201, response.statusCode());
        var root = JSON.readTree(response.body());
        assertEquals(200, root.path("status").asInt()); // Existing creation envelope contract.
        var created = root.path("data");
        Long id = created.path("id").asLong();
        assertEquals(6, created.path("stockQuantity").asInt());
        String path = "/api/v1/admin/products/" + id;
        for (int requestedStock : new int[]{0, 9, 20}) {
            var updated = data(http("PUT", path,
                    productRequest(categoryId, sku, "수정 상품", requestedStock), admin), 200);
            assertEquals("수정 상품", updated.path("name").asString());
            assertEquals(6, updated.path("stockQuantity").asInt());
            assertEquals(6, data(http("GET", path, null, admin), 200).path("stockQuantity").asInt());
            assertEquals(6, products.findById(id).orElseThrow().getStockQuantity());
        }
    }

    @Test
    @DisplayName("JWT가 가리키는 회원이 없으면 필터에서 UTF-8 공통 응답 401로 거부한다")
    void deletedMemberCannotEscapeAuthenticationExceptionBoundary() throws Exception {
        String unique = UUID.randomUUID().toString();
        var member = members.saveAndFlush(Member.builder().email(unique + "@test.invalid")
                .nickname(unique).name("인증 검증").password("fixture").role(Role.USER).build());
        String token = token(member.getId());
        members.deleteById(member.getId());
        var response = http("GET", "/api/v1/orders", null, token);
        data(response, 401);
        assertTrue(response.body().contains("인증이 필요합니다."));
        assertFalse(response.body().contains("ID:"));
        assertFalse(response.body().contains("사용자를 찾을 수 없습니다."));
    }

    private String productRequest(Long categoryId, String sku, String name, int stock) {
        return """
                {"categoryId":%d,"sku":"%s","weightGrams":200,"name":"%s",
                 "price":10000,"stockQuantity":%d,"roastLevel":"MEDIUM"}
                """.formatted(categoryId, sku, name, stock);
    }
}
