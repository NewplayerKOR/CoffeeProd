package com.back.coffeeprod.domain.address.entity;

import com.back.coffeeprod.domain.member.entity.Member;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Address {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id")
    private Member member;

    private String recipient;
    private String phone;
    private String zipcode;
    private String addressLine1;
    private String addressLine2;

    @Column(name = "is_default")
    private boolean isDefault;

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
}
