package com.back.coffeeprod.qa.auth;

import com.back.coffeeprod.domain.member.entity.*;
import com.back.coffeeprod.domain.address.entity.Address;
import com.back.coffeeprod.domain.address.repository.AddressRepository;
import com.back.coffeeprod.domain.cart.entity.*;
import com.back.coffeeprod.domain.cart.repository.*;
import com.back.coffeeprod.domain.order.dto.OrderDto;
import com.back.coffeeprod.domain.order.entity.OrderStatus;
import com.back.coffeeprod.domain.order.repository.OrderRepository;
import com.back.coffeeprod.domain.order.service.OrderService;
import com.back.coffeeprod.domain.product.entity.*;
import com.back.coffeeprod.domain.product.repository.*;
import com.back.coffeeprod.domain.review.entity.Review;
import com.back.coffeeprod.domain.review.repository.ReviewRepository;
import com.back.coffeeprod.domain.qna.entity.*;
import com.back.coffeeprod.domain.qna.repository.QnaRepository;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.*;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class Qa7OwnershipHttpTest extends Qa7HttpSupport {
    @Autowired AddressRepository addresses;
    @Autowired CartRepository carts;
    @Autowired CartItemRepository items;
    @Autowired OrderRepository orders;
    @Autowired OrderService orderService;
    @Autowired ProductRepository products;
    @Autowired CategoryRepository categories;
    @Autowired ReviewRepository reviews;
    @Autowired QnaRepository qnas;

    record Fixture(Member owner, Member other, Member admin, Long product, Long address, Long secondAddress,
                   Long order, Long item, Long review, Long qna) {}
    record Operation(String name, String method, String path, Object body, String denied, String absent) {}
    static final Map<String, Object> ADDRESS = Map.of("recipient", "변경 수령인", "phone", "01000000000",
            "zipcode", "12345", "addressLine1", "변경 주소", "addressLine2", "QA7");
    static final Map<String, Object> REVIEW = Map.of("rating", 5, "content", "변경 리뷰");
    static final Map<String, Object> QNA = Map.of("title", "변경 제목", "question", "변경 질문");

    Address address(Member member, boolean isDefault) {
        return addresses.saveAndFlush(Address.builder().member(member).recipient("QA7").phone("01000000000")
                .zipcode("12345").addressLine1("합성 주소").addressLine2("").isDefault(isDefault).build());
    }
    Fixture fixture() {
        var owner = member(Role.USER); var other = member(Role.USER); var admin = member(Role.ADMIN);
        tx(() -> members.findById(owner.getId()).orElseThrow().addMileage(1000));
        var first = address(owner, true); var second = address(owner, false);
        address(other, true); address(admin, true);
        var category = categories.saveAndFlush(Category.builder().name(unique()).build());
        var product = products.saveAndFlush(Product.builder().category(category).sku(unique()).weightGrams(200)
                .name("QA7 합성 원두").price(10000).stockQuantity(10).roastLevel(RoastLevel.MEDIUM).description("합성").build());
        var cart = carts.saveAndFlush(Cart.builder().member(owner).build());
        carts.saveAndFlush(Cart.builder().member(other).build()); carts.saveAndFlush(Cart.builder().member(admin).build());
        items.saveAndFlush(CartItem.builder().cart(cart).product(product).quantity(1).grindType(GrindType.WHOLE_BEAN).build());
        var input = new OrderDto.CreateRequest();
        ReflectionTestUtils.setField(input, "addressId", first.getId()); ReflectionTestUtils.setField(input, "usedMileage", 300);
        var order = orderService.createOrder(owner.getId(), input);
        var item = items.saveAndFlush(CartItem.builder().cart(cart).product(product).quantity(2).grindType(GrindType.WHOLE_BEAN).build());
        var review = reviews.saveAndFlush(new Review(owner, product, 3, "원본 리뷰"));
        var qna = qnas.saveAndFlush(new Qna(owner, product, "원본 제목", "원본 질문"));
        return new Fixture(owner, other, admin, product.getId(), first.getId(), second.getId(), order.getOrderId(),
                item.getId(), review.getId(), qna.getId());
    }
    Operation operation(String name, Fixture f, boolean missing) {
        long item = missing ? Long.MAX_VALUE : f.item();
        long address = missing ? Long.MAX_VALUE : name.equals("addressDefault") ? f.secondAddress() : f.address();
        long order = missing ? Long.MAX_VALUE : f.order(); long review = missing ? Long.MAX_VALUE : f.review();
        long qna = missing ? Long.MAX_VALUE : f.qna();
        return switch (name) {
            case "cartUpdate" -> new Operation(name, "PATCH", "/api/v1/carts/items/" + item,
                    Map.of("quantity", 4, "grindType", "WHOLE_BEAN"), "본인의 장바구니만 접근할 수 있습니다.", "장바구니 상품을 찾을 수 없습니다.");
            case "cartDelete" -> new Operation(name, "DELETE", "/api/v1/carts/items/" + item, null,
                    "본인의 장바구니만 접근할 수 있습니다.", "장바구니 상품을 찾을 수 없습니다.");
            case "addressGet", "addressUpdate", "addressDelete", "addressDefault" -> new Operation(name,
                    name.equals("addressGet") ? "GET" : name.equals("addressUpdate") ? "PUT" : name.equals("addressDelete") ? "DELETE" : "PATCH",
                    "/api/v1/members/me/addresses/" + address + (name.equals("addressDefault") ? "/default" : ""),
                    name.equals("addressUpdate") ? ADDRESS : null, "본인의 배송지만 접근할 수 있습니다.", "배송지를 찾을 수 없습니다.");
            case "orderGet", "orderCancel" -> new Operation(name, name.equals("orderGet") ? "GET" : "POST",
                    "/api/v1/orders/" + order + (name.equals("orderCancel") ? "/cancel" : ""), null,
                    "본인의 주문만 접근할 수 있습니다.", "주문을 찾을 수 없습니다.");
            case "reviewUpdate", "reviewDelete" -> new Operation(name, name.equals("reviewUpdate") ? "PUT" : "DELETE",
                    "/api/v1/reviews/" + review, name.equals("reviewUpdate") ? REVIEW : null,
                    "본인의 리뷰만 접근할 수 있습니다.", "리뷰를 찾을 수 없습니다.");
            case "qnaUpdate", "qnaDelete" -> new Operation(name, name.equals("qnaUpdate") ? "PUT" : "DELETE",
                    "/api/v1/qnas/" + qna, name.equals("qnaUpdate") ? QNA : null,
                    "본인의 QnA만 접근할 수 있습니다.", "QnA를 찾을 수 없습니다.");
            default -> throw new IllegalArgumentException(name);
        };
    }
    static Stream<String> operations() { return Stream.of("cartUpdate", "cartDelete", "addressGet", "addressUpdate", "addressDelete",
            "addressDefault", "orderGet", "orderCancel", "reviewUpdate", "reviewDelete", "qnaUpdate", "qnaDelete"); }
    static Stream<Arguments> actorsAndOperations() {
        return operations().flatMap(op -> Stream.of("owner", "other", "admin", "anonymous").map(actor -> Arguments.of(op, actor)));
    }
    String token(Fixture f, String actor) { return switch (actor) {
        case "owner" -> access(f.owner()); case "other" -> access(f.other()); case "admin" -> access(f.admin()); default -> null;
    }; }

    List<Object> state(Fixture f) {
        var result = new ArrayList<Object>();
        result.add(jdbc.queryForList("select stock_quantity from product where product_id=?", f.product()));
        for (var member : List.of(f.owner(), f.other(), f.admin())) {
            result.add(jdbc.queryForList("select mileage,mileage_recovery_due from member where member_id=?", member.getId()));
            result.add(jdbc.queryForList("select address_id,is_default,recipient,address_line1 from address where member_id=? order by address_id", member.getId()));
        }
        result.add(jdbc.queryForList("select * from orders where order_id=?", f.order()));
        result.add(jdbc.queryForList("select * from cart_item where cart_item_id=?", f.item()));
        result.add(jdbc.queryForList("select * from reviews where review_id=?", f.review()));
        result.add(jdbc.queryForList("select * from product_qna where qna_id=?", f.qna()));
        for (String table : List.of("orders", "cart", "cart_item", "payment", "payment_operation", "address", "reviews", "product_qna"))
            result.add(jdbc.queryForObject("select count(*) from " + table, Long.class));
        return result;
    }
    void unchanged(Fixture f, List<Object> before) {
        assertTrue(before.equals(state(f)), "거부 후 모든 대상 자원과 개수 보존 (원문 생략)");
        assertEquals(9, products.findById(f.product()).orElseThrow().getStockQuantity());
        assertEquals(700, members.findById(f.owner().getId()).orElseThrow().getMileage());
        assertEquals(OrderStatus.PENDING, orders.findById(f.order()).orElseThrow().getStatus());
        assertEquals(2, items.findById(f.item()).orElseThrow().getQuantity());
        assertEquals("원본 리뷰", reviews.findById(f.review()).orElseThrow().getContent());
        assertEquals("원본 질문", qnas.findById(f.qna()).orElseThrow().getQuestion());
        assertTrue(addresses.findById(f.address()).orElseThrow().isDefault());
    }

    @ParameterizedTest(name = "{0} / {1}") @MethodSource("actorsAndOperations")
    @DisplayName("소유자만 자원을 다루고 타인 관리자 비인증 요청은 자원을 보존한다")
    void ownershipMatrix(String name, String actor) throws Exception {
        var f = fixture(); var op = operation(name, f, false); var before = state(f);
        var response = request(op.method(), op.path(), op.body(), token(f, actor));
        if (!actor.equals("owner")) {
            error(response, actor.equals("anonymous") ? 401 : 403, actor.equals("anonymous") ? "인증이 필요합니다." : op.denied());
            unchanged(f, before); return;
        }
        var value = data(response, 200);
        switch (name) {
            case "cartUpdate" -> {
                assertEquals(4, items.findById(f.item()).orElseThrow().getQuantity());
                assertEquals(4, value.path("totalQuantity").asInt());
            }
            case "cartDelete" -> { assertFalse(items.existsById(f.item())); assertEquals(0, value.path("totalQuantity").asInt()); }
            case "addressGet" -> { assertEquals(f.address().longValue(), value.path("id").asLong()); unchanged(f, before); }
            case "addressUpdate" -> { assertEquals("변경 수령인", addresses.findById(f.address()).orElseThrow().getRecipient()); assertEquals("변경 수령인", value.path("recipient").asText()); }
            case "addressDelete" -> { assertFalse(addresses.existsById(f.address())); assertTrue(addresses.findById(f.secondAddress()).orElseThrow().isDefault()); }
            case "addressDefault" -> { assertFalse(addresses.findById(f.address()).orElseThrow().isDefault());
                assertTrue(addresses.findById(f.secondAddress()).orElseThrow().isDefault()); assertEquals(f.secondAddress().longValue(), value.path("id").asLong()); }
            case "orderGet" -> { assertEquals(f.order().longValue(), value.path("orderId").asLong()); unchanged(f, before); }
            case "orderCancel" -> {
                assertEquals("CANCELED", value.path("status").asText()); assertEquals(OrderStatus.CANCELED, orders.findById(f.order()).orElseThrow().getStatus());
                assertEquals(10, products.findById(f.product()).orElseThrow().getStockQuantity());
                assertEquals(1000, members.findById(f.owner().getId()).orElseThrow().getMileage());
            }
            case "reviewUpdate" -> { assertEquals("변경 리뷰", reviews.findById(f.review()).orElseThrow().getContent()); assertEquals(5, value.path("rating").asInt()); }
            case "reviewDelete" -> assertFalse(reviews.existsById(f.review()));
            case "qnaUpdate" -> { assertEquals("변경 질문", qnas.findById(f.qna()).orElseThrow().getQuestion()); assertEquals("변경 질문", value.path("question").asText()); }
            case "qnaDelete" -> assertFalse(qnas.existsById(f.qna()));
            default -> fail(name);
        }
    }

    @ParameterizedTest @MethodSource("operations")
    @DisplayName("없는 객체는 타인 소유 객체와 구분되는 404이고 자원을 보존한다")
    void absentResources(String name) throws Exception {
        var f = fixture(); var before = state(f); var op = operation(name, f, true);
        // ORDER_NOT_FOUND uses the same stable message as the existing ErrorCode contract.
        error(request(op.method(), op.path(), op.body(), access(f.owner())), 404,
                name.startsWith("order") ? "주문을 찾을 수 없습니다." : op.absent());
        unchanged(f, before);
    }

    @Test @DisplayName("타인의 배송지로 주문을 만들면 403이고 장바구니 재고 마일리지가 보존된다")
    void orderWithOthersAddress() throws Exception {
        var f = fixture();
        items.saveAndFlush(CartItem.builder().cart(carts.findByMemberId(f.other().getId()).orElseThrow())
                .product(products.findById(f.product()).orElseThrow()).quantity(1).grindType(GrindType.WHOLE_BEAN).build());
        var before = state(f);
        error(request("POST", "/api/v1/orders", Map.of("addressId", f.address(), "usedMileage", 0), access(f.other())),
                403, "본인의 배송지만 접근할 수 있습니다.");
        unchanged(f, before);
    }

    @Test @DisplayName("장바구니 배송지 주문 목록은 현재 회원의 자원만 반환한다")
    void ownListsOnly() throws Exception {
        var f = fixture();
        var cart = data(request("GET", "/api/v1/carts", null, access(f.other())), 200);
        assertEquals(0, cart.path("items").size());
        var addresses = data(request("GET", "/api/v1/members/me/addresses", null, access(f.other())), 200);
        assertEquals(1, addresses.size()); assertFalse(addresses.valueStream().anyMatch(a -> a.path("id").asLong() == f.address()));
        assertEquals(0, data(request("GET", "/api/v1/orders", null, access(f.other())), 200).path("content").size());
        assertEquals(f.item().longValue(), data(request("GET", "/api/v1/carts", null, access(f.owner())), 200).path("items").get(0).path("cartItemId").asLong());
        assertEquals(f.order().longValue(), data(request("GET", "/api/v1/orders", null, access(f.owner())), 200).path("content").get(0).path("orderId").asLong());
    }

    @ParameterizedTest @ValueSource(strings = {"product", "reviews", "qnas"})
    @DisplayName("상품 리뷰 문의 조회는 비로그인 사용자에게 공개된다")
    void publicReads(String route) throws Exception {
        var f = fixture();
        var value = data(request("GET", "/api/v1/products/" + f.product() + (route.equals("product") ? "" : "/" + route), null, null), 200);
        if (!route.equals("product")) assertEquals(1, value.path("content").size());
    }

    static Stream<Arguments> adminRoutes() {
        return Stream.of("members", "orders", "qnas", "answer").flatMap(route ->
                Stream.of("anonymous", "other", "admin").map(actor -> Arguments.of(route, actor)));
    }
    @ParameterizedTest(name = "{0} / {1}") @MethodSource("adminRoutes")
    @DisplayName("관리자 회원 주문 문의 조회와 답변은 ADMIN에게만 허용된다")
    void adminMatrix(String route, String actor) throws Exception {
        var f = fixture(); var before = state(f);
        var result = request(route.equals("answer") ? "PATCH" : "GET",
                "/api/v1/admin/" + (route.equals("answer") ? "qnas/" + f.qna() + "/answer" : route),
                route.equals("answer") ? Map.of("answer", "관리자 답변") : null, token(f, actor));
        if (!actor.equals("admin")) {
            error(result, actor.equals("anonymous") ? 401 : 403, actor.equals("anonymous") ? "인증이 필요합니다." : "접근 권한이 없습니다.");
            unchanged(f, before);
        } else {
            var value = data(result, 200);
            if (route.equals("answer")) {
                var stored = qnas.findById(f.qna()).orElseThrow(); assertEquals(QnaStatus.ANSWERED, stored.getStatus());
                assertEquals("관리자 답변", stored.getAnswer()); assertEquals("관리자 답변", value.path("answer").asText());
            } else { assertTrue(value.path("content").isArray()); unchanged(f, before); }
        }
    }
}
