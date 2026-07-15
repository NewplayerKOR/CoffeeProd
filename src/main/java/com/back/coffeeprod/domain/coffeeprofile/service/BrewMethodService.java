package com.back.coffeeprod.domain.coffeeprofile.service;

import com.back.coffeeprod.domain.coffeeprofile.dto.BrewMethodDto;
import com.back.coffeeprod.domain.coffeeprofile.entity.BrewMethod;
import com.back.coffeeprod.domain.coffeeprofile.repository.BrewMethodRepository;
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
public class BrewMethodService {

    private final BrewMethodRepository brewMethodRepository;

    // 추출법 목록을 조회함
    public List<BrewMethodDto.Response> getBrewMethods() {
        return brewMethodRepository.findAll(Sort.by(Sort.Direction.ASC, "name"))
                .stream()
                .map(BrewMethodDto.Response::new)
                .toList();
    }

    // 추출법을 등록함
    @Transactional
    public BrewMethodDto.Response createBrewMethod(
            BrewMethodDto.CreateRequest request
    ) {
        if (brewMethodRepository.existsByCode(request.getCode())) {
            throw new CustomException(ErrorCode.DUPLICATE_BREW_METHOD_CODE);
        }

        BrewMethod brewMethod = BrewMethod.builder()
                .code(request.getCode())
                .name(request.getName())
                .description(request.getDescription())
                .build();

        return new BrewMethodDto.Response(
                brewMethodRepository.save(brewMethod)
        );
    }

    // 추출법을 수정함
    @Transactional
    public BrewMethodDto.Response updateBrewMethod(
            Long brewMethodId,
            BrewMethodDto.UpdateRequest request
    ) {
        BrewMethod brewMethod = findBrewMethodById(brewMethodId);
        brewMethod.update(request.getName(), request.getDescription());

        return new BrewMethodDto.Response(brewMethod);
    }

    // 내부 공용 조회
    public BrewMethod findBrewMethodById(Long brewMethodId) {
        return brewMethodRepository.findById(brewMethodId)
                .orElseThrow(() -> new CustomException(
                        ErrorCode.BREW_METHOD_NOT_FOUND
                ));
    }
}
