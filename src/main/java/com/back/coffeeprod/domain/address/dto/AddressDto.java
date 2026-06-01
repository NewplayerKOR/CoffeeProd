package com.back.coffeeprod.domain.address.dto;

import com.back.coffeeprod.domain.address.entity.Address;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.NoArgsConstructor;

public class AddressDto {

    // 배송지 등록 / 수정 요청 DTO
    @Getter
    @NoArgsConstructor
    public static class Request {

        @NotBlank(message = "수령인은 필수입니다.")
        private String recipient;       // 수령인

        @NotBlank(message = "연락처는 필수입니다.")
        @Pattern(
                regexp = "^01[0-9]-?//d{3,4}-?//d{4}$",
                message = "연락처 형식이 올바르지 않습니다."
        )
        private String phone;           // 연락처

        @NotBlank(message = "우편번호는 필수입니다.")
        @Pattern(
                regexp = "^//d{5}$",
                message = "우편번호는 5자리 숫자여야 합니다."
        )
        private String zipcode;         // 우편번호

        @NotBlank(message = "기본 주소는 필수입니다.")
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
