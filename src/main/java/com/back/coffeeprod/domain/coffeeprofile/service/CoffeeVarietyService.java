package com.back.coffeeprod.domain.coffeeprofile.service;

import com.back.coffeeprod.domain.coffeeprofile.dto.CoffeeVarietyDto;
import com.back.coffeeprod.domain.coffeeprofile.entity.CoffeeVariety;
import com.back.coffeeprod.domain.coffeeprofile.repository.CoffeeVarietyRepository;
import com.back.coffeeprod.global.exception.CustomException;
import com.back.coffeeprod.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CoffeeVarietyService {

    private final CoffeeVarietyRepository coffeeVarietyRepository;

    // 커피 품종 목록을 조회함
    public List<CoffeeVarietyDto.Response> getCoffeeVarieties() {
        return coffeeVarietyRepository.findAll(Sort.by(Sort.Direction.ASC, "name"))
                .stream()
                .map(CoffeeVarietyDto.Response::new)
                .toList();
    }

    // 커피 품종을 등록함
    @Transactional
    public CoffeeVarietyDto.Response createCoffeeVariety(
            CoffeeVarietyDto.CreateRequest request
    ) {
        if (coffeeVarietyRepository.existsByCode(request.getCode())) {
            throw new CustomException(ErrorCode.DUPLICATE_COFFEE_VARIETY_CODE);
        }

        CoffeeVariety coffeeVariety = CoffeeVariety.builder()
                .code(request.getCode())
                .name(request.getName())
                .description(request.getDescription())
                .build();

        return new CoffeeVarietyDto.Response(
                coffeeVarietyRepository.save(coffeeVariety)
        );
    }

    // 커피 품종을 수정함
    @Transactional
    public CoffeeVarietyDto.Response updateCoffeeVariety(
            Long coffeeVarietyId,
            CoffeeVarietyDto.UpdateRequest request
    ) {
        CoffeeVariety coffeeVariety = findCoffeeVarietyById(coffeeVarietyId);
        coffeeVariety.update(request.getName(), request.getDescription());

        return new CoffeeVarietyDto.Response(coffeeVariety);
    }

    // 내부 공용 조회함
    public CoffeeVariety findCoffeeVarietyById(Long coffeeVarietyId) {
        return coffeeVarietyRepository.findById(coffeeVarietyId)
                .orElseThrow(() -> new CustomException(
                        ErrorCode.COFFEE_VARIETY_NOT_FOUND
                ));
    }
}
