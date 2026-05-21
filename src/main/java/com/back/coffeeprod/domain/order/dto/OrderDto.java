package com.back.coffeeprod.domain.order.dto;

import com.back.coffeeprod.domain.cart.entity.GrindType;
import com.back.coffeeprod.domain.order.entity.OrderItem;
import com.back.coffeeprod.domain.order.entity.OrderStatus;
import com.back.coffeeprod.domain.order.entity.Orders;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

public class OrderDto {

    // 주문 생성시 요청 DTO
    @Getter
    @NoArgsConstructor
    public static class CreateRequest {
        private Long addressId;     // 사용할 배송지 ID
        private int usedMileage;    // 사용할 마일리지 (0이면 미사용)
    }

    // 주문 상품 응답 DTO
    @Getter
    public static class OrderItemResponse {
        private final Long orderItemId;
        private final Long productId;
        private final String productName;
        private final int orderPrice;   // 주문 당시 가격 스냅샷
        private final int quantity;
        private final GrindType grindType;
        private final int subTotal;     // orderPrice * quantity

        public OrderItemResponse(OrderItem orderItem) {
            this.orderItemId = orderItem.getId();
            this.productId = orderItem.getProduct().getId();
            this.productName = orderItem.getProduct().getName();
            this.orderPrice = orderItem.getOrderPrice();
            this.quantity = orderItem.getQuantity();
            this.grindType = orderItem.getGrindType();
            this.subTotal = orderItem.getSubTotal();
        }
    }

    // 주문 목록 응답 DTO (요약)
    @Getter
    public static class SummaryResponse {
        private final Long orderId;
        private final OrderStatus status;
        private final int totalPrice;
        private final LocalDateTime orderDate;
        private final int itemCount;            // 주문 상품 종류 수
        private final String firstProductName;  // 대표 상품명 (첫 번째)

        public SummaryResponse(Orders orders) {
            this.orderId = orders.getId();
            this.status = orders.getStatus();
            this.totalPrice = orders.getTotalPrice();
            this.orderDate = orders.getOrderDate();
            this.itemCount = orders.getOrderItems().size();
            this.firstProductName = orders.getOrderItems().isEmpty()
                    ? ""
                    : orders.getOrderItems().get(0).getProduct().getName();
        }
    }

    // 주문 상세 응답 DTO
    @Getter
    public static class DetailResponse {
        private final Long orderId;
        private final OrderStatus status;
        private final int totalPrice;
        private final int usedMileage;
        private final String deliveryAddress;
        private final String trackingNo;
        private final LocalDateTime orderDate;
        private final List<OrderItemResponse> orderItems;

        public DetailResponse(Orders orders) {
            this.orderId = orders.getId();
            this.status = orders.getStatus();
            this.totalPrice = orders.getTotalPrice();
            this.usedMileage = orders.getUsedMileage();
            this.deliveryAddress = orders.getDeliveryAddress();
            this.trackingNo = orders.getTrackingNo();
            this.orderDate = orders.getOrderDate();
            this.orderItems = orders.getOrderItems()
                    .stream()
                    .map(OrderItemResponse::new)
                    .collect(Collectors.toList());
        }
    }

    // 주문 상태 변경 요청 DTO (관리자)
    @Getter
    @NoArgsConstructor
    public static class StatusUpdateRequest {
        private OrderStatus status;
        private String trackingNo;  // 배송 처리 시 운송장 번호
    }
}
