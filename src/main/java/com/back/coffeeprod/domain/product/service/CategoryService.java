package com.back.coffeeprod.domain.product.service;

import com.back.coffeeprod.domain.product.dto.CategoryDto;
import com.back.coffeeprod.domain.product.entity.Category;
import com.back.coffeeprod.domain.product.repository.CategoryRepository;
import com.back.coffeeprod.global.exception.CustomException;
import com.back.coffeeprod.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true) // 기본 읽기 전용
public class CategoryService {

    private final CategoryRepository categoryRepository;

    // [공개] 카테고리 전체 목록 조회
    public List<CategoryDto.Response> getAllCategories() {
        return categoryRepository.findAll()
                .stream()
                .map(CategoryDto.Response::new)
                .collect(Collectors.toList());
    }

    // [관리자] 카테고리 등록
    @Transactional
    public CategoryDto.Response createCategory(CategoryDto.Request request) {
        // 카테고리 중복 검증
        if (categoryRepository.existsByName(request.getName())) {
            throw new CustomException(ErrorCode.DUPLICATE_CATEGORY_NAME);
        }

        Category category = Category.builder()
                .name(request.getName())
                .build();

        return new CategoryDto.Response(categoryRepository.save(category));
    }

    // [관리자] 카테고리 수정
    @Transactional
    public CategoryDto.Response updateCategory(Long categoryId, CategoryDto.Request request) {
        Category category = findCategoryById(categoryId);

        // 자기 자신을 제외한 다른 카테고리와 이름이 중복되면 수정할 수 없음
        if (categoryRepository.existsByNameAndIdNot(request.getName(), categoryId)) {
            throw new CustomException(ErrorCode.DUPLICATE_CATEGORY_NAME);
        }

        category.updateName(request.getName()); // Dirty Checking -> 자동 UPDATE
        return new CategoryDto.Response(category);
    }


    // [내부 공용] ID로 카테고리 조회 (없으면 예외)
    public Category findCategoryById(Long categoryId) {
        return categoryRepository.findById(categoryId)
                .orElseThrow(() -> new CustomException(ErrorCode.CATEGORY_NOT_FOUND));
    }

}
