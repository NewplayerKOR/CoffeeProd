package com.back.coffeeprod.domain.address.repository;

import com.back.coffeeprod.domain.address.entity.Address;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AddressRepository extends JpaRepository<Address, Long> {

    // 특정 회원의 전체 배송지 목록 조회
    List<Address> findByMemberId(Long memberId);

    // 특정 회원의 배송지 수 조회
    int countByMemberId(Long memberId);

    // 특정 회원의 기본 배송지 조회
    // 새 기본 배송지 설정 전 기존의 기본 배송지 해제에 사용
    Optional<Address> findByMemberIdAndIsDefaultTrue(Long memberId);
}
