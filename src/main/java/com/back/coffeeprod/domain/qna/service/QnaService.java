package com.back.coffeeprod.domain.qna.service;

import com.back.coffeeprod.domain.member.entity.Member;
import com.back.coffeeprod.domain.member.service.MemberService;
import com.back.coffeeprod.domain.product.entity.Product;
import com.back.coffeeprod.domain.product.entity.ProductStatus;
import com.back.coffeeprod.domain.product.repository.ProductRepository;
import com.back.coffeeprod.domain.qna.dto.QnaDto;
import com.back.coffeeprod.domain.qna.entity.Qna;
import com.back.coffeeprod.domain.qna.entity.QnaStatus;
import com.back.coffeeprod.domain.qna.repository.QnaRepository;
import com.back.coffeeprod.global.exception.CustomException;
import com.back.coffeeprod.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class QnaService {

    private final QnaRepository qnaRepository;
    private final ProductRepository productRepository;
    private final MemberService memberService;

    // 상품별 문의 목록을 공개 조회함
    public Page<QnaDto.Response> getProductQnas(
            Long productId,
            Pageable pageable
    ) {
        findVisibleProduct(productId);

        return qnaRepository.findByProductId(productId, pageable)
                .map(QnaDto.Response::new);
    }

    // 관리자 문의 목록을 조회함
    public Page<QnaDto.Response> getAdminQnas(
            QnaStatus status,
            Pageable pageable
    ) {
        return qnaRepository.findAllByStatus(status, pageable)
                .map(QnaDto.Response::new);
    }

    // 상품 문의를 작성함
    @Transactional
    public QnaDto.Response create(
            Long memberId,
            Long productId,
            QnaDto.QuestionRequest request
    ) {
        Product product = findVisibleProduct(productId);
        Member member = memberService.findMemberById(memberId);

        Qna qna = new Qna(
                member,
                product,
                request.getTitle(),
                request.getQuestion()
        );

        return new QnaDto.Response(qnaRepository.save(qna));
    }

    // 답변 전 문의를 수정함
    @Transactional
    public QnaDto.Response update(
            Long memberId,
            Long qnaId,
            QnaDto.QuestionRequest request
    ) {
        Qna qna = findQna(qnaId);
        validateOwner(qna, memberId);
        validateNotAnswered(qna);

        qna.updateQuestion(
                request.getTitle(),
                request.getQuestion()
        );

        return new QnaDto.Response(qna);
    }

    // 답변 전 문의를 삭제함
    @Transactional
    public void delete(Long memberId, Long qnaId) {
        Qna qna = findQna(qnaId);
        validateOwner(qna, memberId);
        validateNotAnswered(qna);

        qnaRepository.delete(qna);
    }

    // 관리자가 문의에 답변함
    @Transactional
    public QnaDto.Response answer(
            Long adminId,
            Long qnaId,
            QnaDto.AnswerRequest request
    ) {
        Qna qna = findQna(qnaId);
        validateNotAnswered(qna);

        Member admin = memberService.findMemberById(adminId);
        qna.answer(admin, request.getAnswer());

        return new QnaDto.Response(qna);
    }

    // 공개 가능한 상품을 조회함
    private Product findVisibleProduct(Long productId) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new CustomException(ErrorCode.PRODUCT_NOT_FOUND));

        if (product.getStatus() == ProductStatus.HIDDEN) {
            throw new CustomException(ErrorCode.PRODUCT_NOT_FOUND);
        }

        return product;
    }

    // 문의 연관 데이터를 함께 조회함
    private Qna findQna(Long qnaId) {
        return qnaRepository.findByIdWithDetails(qnaId)
                .orElseThrow(() -> new CustomException(ErrorCode.QNA_NOT_FOUND));
    }

    // 문의 작성자를 검증함
    private void validateOwner(Qna qna, Long memberId) {
        if (!qna.getMember().getId().equals(memberId)) {
            throw new CustomException(ErrorCode.QNA_ACCESS_DENIED);
        }
    }

    // 답변 완료 문의 변경을 차단함
    private void validateNotAnswered(Qna qna) {
        if (qna.isAnswered()) {
            throw new CustomException(ErrorCode.QNA_ALREADY_ANSWERED);
        }
    }
}
