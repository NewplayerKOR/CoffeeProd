package com.back.coffeeprod.domain.address.entity;

import com.back.coffeeprod.domain.member.entity.Member;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "address")
public class Address {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "address_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id")
    private Member member;

    @Column(nullable = false, length = 50)
    private String recipient;   // 수령인

    @Column(nullable = false, length = 20)
    private String phone;   // 연락처

    @Column(nullable = false, length = 10)
    private String zipcode; // 우편번호

    @Column(nullable = false)
    private String addressLine1;    // 기본 주소

    private String addressLine2;    // 상세 주소 (동/ 호수 등)

    @Column(name = "is_default", nullable = false)
    private boolean isDefault;  // 기본 배송지 여부

    @Builder
    public Address(Member member, String recipient, String phone, String zipcode,
                   String addressLine1, String addressLine2, boolean isDefault) {
        this.member = member;
        this.recipient = recipient;
        this.phone = phone;
        this.zipcode = zipcode;
        this.addressLine1 = addressLine1;
        this.addressLine2 = addressLine2;
        this.isDefault = isDefault;
    }

    // 배송지 정보 수정
    public void update(String recipient, String phone, String zipcode,
                       String addressLine1, String addressLine2) {
        this.recipient = recipient;
        this.phone = phone;
        this.zipcode = zipcode;
        this.addressLine1 = addressLine1;
        this.addressLine2 = addressLine2;
    }

    // 기본 배송지로 설정
    public void setDefault() {
        this.isDefault = true;
    }

    // 기본 배송지 해제
    public void unsetDefault() {
        this.isDefault = false;
    }
}
