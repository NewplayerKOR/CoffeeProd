package com.back.coffeeprod.domain.address.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.back.coffeeprod.domain.address.entity.Address;

public interface AddressRepository extends JpaRepository<Address, Long> {

    List<Address> findByMemberId(Long memberId);
}
