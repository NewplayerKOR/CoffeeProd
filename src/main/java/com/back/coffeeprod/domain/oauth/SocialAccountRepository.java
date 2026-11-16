package com.back.coffeeprod.domain.oauth;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface SocialAccountRepository extends JpaRepository<SocialAccount,Long> {
    Optional<SocialAccount> findByProviderAndSubjectHash(String provider,String subjectHash);
    boolean existsByMemberIdAndProvider(Long memberId,String provider);
    List<SocialAccount> findByMemberId(Long memberId);
}
