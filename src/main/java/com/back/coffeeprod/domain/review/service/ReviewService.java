package com.back.coffeeprod.domain.review.service;

import com.back.coffeeprod.domain.member.entity.Member;
import com.back.coffeeprod.domain.member.service.MemberService;
import com.back.coffeeprod.domain.order.repository.OrderRepository;
import com.back.coffeeprod.domain.product.entity.Product;
import com.back.coffeeprod.domain.product.entity.ProductStatus;
import com.back.coffeeprod.domain.product.repository.ProductRepository;
import com.back.coffeeprod.domain.review.dto.ReviewDto;
import com.back.coffeeprod.domain.review.entity.Review;
import com.back.coffeeprod.domain.review.repository.ReviewRepository;
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
public class ReviewService {

    private final ReviewRepository reviewRepository;
    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final MemberService memberService;

    public Page<ReviewDto.Response> getReviews(Long productId, Pageable pageable) {
        findVisibleProduct(productId);

        return reviewRepository.findByProductId(productId, pageable)
                .map(ReviewDto.Response::new);
    }

    @Transactional
    public ReviewDto.Response create(
            Long memberId,
            Long productId,
            ReviewDto.Request request
    ) {
        Product product = findVisibleProduct(productId);

        // 구매 이력을 검증함
        if (!orderRepository.existsPurchasedProduct(memberId, productId)) {
            throw new CustomException(ErrorCode.REVIEW_PURCHASE_REQUIRED);
        }

        // 중복 리뷰를 차단함
        if (reviewRepository.existsByMemberIdAndProductId(memberId, productId)) {
            throw new CustomException(ErrorCode.REVIEW_ALREADY_EXISTS);
        }

        Member member = memberService.findMemberById(memberId);
        Review review = new Review(
                member,
                product,
                request.getRating(),
                request.getContent()
        );

        return new ReviewDto.Response(reviewRepository.save(review));
    }

    @Transactional
    public ReviewDto.Response update(
            Long memberId,
            Long reviewId,
            ReviewDto.Request request
    ) {
        Review review = findReview(reviewId);
        validateOwner(review, memberId);

        review.update(request.getRating(), request.getContent());
        return new ReviewDto.Response(review);
    }

    @Transactional
    public void delete(Long memberId, Long reviewId) {
        Review review = findReview(reviewId);
        validateOwner(review, memberId);

        reviewRepository.delete(review);
    }

    private Product findVisibleProduct(Long productId) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new CustomException(ErrorCode.PRODUCT_NOT_FOUND));

        if (product.getStatus() == ProductStatus.HIDDEN) {
            throw new CustomException(ErrorCode.PRODUCT_NOT_FOUND);
        }

        return product;
    }

    private Review findReview(Long reviewId) {
        return reviewRepository.findById(reviewId)
                .orElseThrow(() -> new CustomException(ErrorCode.REVIEW_NOT_FOUND));
    }

    private void validateOwner(Review review, Long memberId) {
        if (!review.getMember().getId().equals(memberId)) {
            throw new CustomException(ErrorCode.REVIEW_ACCESS_DENIED);
        }
    }
}
