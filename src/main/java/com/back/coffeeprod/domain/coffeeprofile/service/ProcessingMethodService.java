package com.back.coffeeprod.domain.coffeeprofile.service;

import com.back.coffeeprod.domain.coffeeprofile.dto.ProcessingMethodDto;
import com.back.coffeeprod.domain.coffeeprofile.entity.ProcessingMethod;
import com.back.coffeeprod.domain.coffeeprofile.repository.ProcessingMethodRepository;
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
public class ProcessingMethodService {

    private final ProcessingMethodRepository processingMethodRepository;

    // 가공 방식 목록을 조회함
    public List<ProcessingMethodDto.Response> getProcessingMethods() {
        return processingMethodRepository.findAll(Sort.by(Sort.Direction.ASC, "name"))
                .stream()
                .map(ProcessingMethodDto.Response::new)
                .toList();
    }

    // 가공 방식을 등록함
    @Transactional
    public ProcessingMethodDto.Response createProcessingMethod(ProcessingMethodDto.CreateRequest request) {
        if (processingMethodRepository.existsByCode(request.getCode())) {
            throw new CustomException(ErrorCode.DUPLICATE_PROCESSING_METHOD_CODE);
        }

        ProcessingMethod processingMethod = ProcessingMethod.builder()
                .code(request.getCode())
                .name(request.getName())
                .description(request.getDescription())
                .build();

        return new ProcessingMethodDto.Response(processingMethodRepository.save(processingMethod));
    }

    // 가공 방식을 수정함
    @Transactional
    public ProcessingMethodDto.Response updateProcessingMethod(
            Long processingMethodId,
            ProcessingMethodDto.UpdateRequest request
    ) {
        ProcessingMethod processingMethod = findProcessingMethodById(processingMethodId);
        processingMethod.update(request.getName(), request.getDescription());
        return new ProcessingMethodDto.Response(processingMethod);
    }

    // 내부 공용 조회
    public ProcessingMethod findProcessingMethodById(Long processingMethodId) {
        return processingMethodRepository.findById(processingMethodId)
                .orElseThrow(() -> new CustomException(ErrorCode.PROCESSING_METHOD_NOT_FOUND));
    }
}
