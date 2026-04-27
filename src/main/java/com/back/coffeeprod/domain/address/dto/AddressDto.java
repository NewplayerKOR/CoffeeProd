package com.back.coffeeprod.domain.address.dto;

import com.back.coffeeprod.domain.address.entity.Address;
import lombok.Getter;
import lombok.NoArgsConstructor;

public class AddressDto {

    // 배송지 등록 / 수정 요청 DTO
    @Getter
    @NoArgsConstructor
    public static class Request {
        private String recipient;       // 수령인
        private String phone;           // 연락처
        private String zipcode;         // 우편번호
        private String addressLine1;    // 기본 주소
        private String addressLine2;    // 상세 주소 (동/ 호수 등)
    }

    // 배송지 응답 DTO
    @Getter
    public static class Response {
        private final Long id;
        private String recipient;
        private String phone;
        private String zipcode;
        private String addressLine1;
        private String addressLine2;
        private final boolean isDefault;  // 기본 배송지 여부

        public Response(Address address) {
            this.id = address.getId();
            this.recipient = address.getRecipient();
            this.phone = address.getPhone();
            this.zipcode = address.getZipcode();
            this.addressLine1 = address.getAddressLine1();
            this.addressLine2 = address.getAddressLine2();
            this.isDefault = address.isDefault();
        }
    }
}
