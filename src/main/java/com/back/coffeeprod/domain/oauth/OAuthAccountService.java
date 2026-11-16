package com.back.coffeeprod.domain.oauth;

import com.back.coffeeprod.domain.member.entity.*;
import com.back.coffeeprod.domain.member.dto.MemberDto;
import com.back.coffeeprod.domain.member.repository.MemberRepository;
import com.back.coffeeprod.global.exception.*;
import com.back.coffeeprod.global.security.jwt.JwtUtil;
import com.back.coffeeprod.global.security.service.RefreshTokenService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
@Transactional
public class OAuthAccountService {
    private final MemberRepository members;private final SocialAccountRepository accounts;
    private final PasswordEncoder passwords;private final JwtUtil jwt;private final RefreshTokenService refresh;
    private final AuthStore store;private final EmailVerificationService emailVerification;
    public OAuthAccountService(MemberRepository members,SocialAccountRepository accounts,PasswordEncoder passwords,JwtUtil jwt,RefreshTokenService refresh,AuthStore store,EmailVerificationService emailVerification) {
        this.members=members;this.accounts=accounts;this.passwords=passwords;this.jwt=jwt;this.refresh=refresh;this.store=store;this.emailVerification=emailVerification;
    }
    public Member active(Long id) {
        if(id==null)throw new CustomException(ErrorCode.AUTHENTICATION_REQUIRED);
        Member m=members.findLockedById(id).orElseThrow(()->new CustomException(ErrorCode.MEMBER_NOT_FOUND));
        if(m.getStatus()==MemberStatus.WITHDRAWN)throw new CustomException(ErrorCode.WITHDRAW_MEMBER);
        if(m.getStatus()==MemberStatus.SUSPENDED)throw new CustomException(ErrorCode.SUSPENDED_MEMBER);
        return m;
    }
    public List<String> providers(Long id) { active(id);return accounts.findByMemberId(id).stream().map(SocialAccount::getProvider).toList(); }
    public void confirmLink(Long id,String password,String grant) {
        Member m=active(id);
        if(m.getPassword()!=null) {
            if(password==null || !passwords.matches(password,m.getPassword()))throw new CustomException(ErrorCode.INVALID_PASSWORD);
        } else if(!("LINK:"+id).equals(grant==null?null:store.consume("confirmation:"+grant)))throw new CustomException(ErrorCode.OAUTH_CONFIRMATION_REQUIRED);
    }
    private Optional<SocialAccount> account(Map<String,String> tx) { return accounts.findByProviderAndSubjectHash(tx.get("provider"),tx.get("subjectHash")); }
    public void requireSignupCandidate(Map<String,String> tx) {
        if(!"LOGIN".equals(tx.get("purpose")) || account(tx).isPresent())throw new CustomException(ErrorCode.OAUTH_TRANSACTION_INVALID);
    }
    private void claim(String id) {
        if(!store.transition(id,"READY","DONE"))throw new CustomException(ErrorCode.OAUTH_TRANSACTION_INVALID);
    }
    private MemberDto.TokenResponse tokens(Member m) {
        active(m.getId());
        String access=jwt.generateAccessToken(m.getId(),m.getRole()), r=jwt.generateRefreshToken(m.getId());
        refresh.save(m.getId(),r);return new MemberDto.TokenResponse(access,r);
    }
    public Map<String,Object> exchange(String id,Map<String,String> tx,Long authenticatedId) {
        if(!"LOGIN".equals(tx.get("purpose"))) {
            checkTarget(tx,authenticatedId);
            if("PROVE_LINK".equals(tx.get("purpose"))) {
                requireOwned(tx,authenticatedId);claim(id);String grant=AuthStore.random();store.value("confirmation:"+grant,"LINK:"+authenticatedId,300);
                return Map.of("status","LINK_PROOF","confirmation",grant);
            }
            return Map.of("status",tx.get("purpose")+"_CONFIRM","provider",tx.get("provider"));
        }
        var linked=account(tx);
        if(linked.isPresent()) {Member m=active(linked.get().getMember().getId());claim(id);return Map.of("status","LOGIN","tokens",tokens(m));}
        return Map.of("status","SIGNUP","email",tx.getOrDefault("email",""),"emailVerified",Boolean.parseBoolean(tx.get("verified")),"provider",tx.get("provider"));
    }
    public MemberDto.TokenResponse signup(String id,Map<String,String> tx,String email,String name,String nickname,String grant) {
        if(!"LOGIN".equals(tx.get("purpose")))throw new CustomException(ErrorCode.OAUTH_TRANSACTION_INVALID);
        if(account(tx).isPresent())throw new CustomException(ErrorCode.OAUTH_CONFLICT);
        if(members.existsByEmail(email))throw new CustomException(ErrorCode.DUPLICATE_EMAIL);
        if(members.existsByNickname(nickname))throw new CustomException(ErrorCode.DUPLICATE_NICKNAME);
        if(!Boolean.parseBoolean(tx.get("verified")) || !email.equals(tx.get("email")))
            emailVerification.consume(EmailVerificationService.Purpose.OAUTH_SIGNUP_EMAIL,email,id,grant);
        claim(id);
        Member m=members.saveAndFlush(Member.builder().email(email).name(name).nickname(nickname).password(null).role(Role.USER).build());
        accounts.saveAndFlush(new SocialAccount(m,tx.get("provider"),tx.get("subjectHash")));
        return tokens(m);
    }
    public void checkTarget(Map<String,String> tx,Long id) {
        active(id);
        if(!id.toString().equals(tx.get("memberId")))throw new CustomException(ErrorCode.ACCESS_DENIED);
        if(System.currentTimeMillis()>Long.parseLong(tx.getOrDefault("confirmationExpires","0")))throw new CustomException(ErrorCode.OAUTH_CONFIRMATION_REQUIRED);
    }
    private void requireOwned(Map<String,String> tx,Long id) {
        var linked=account(tx).orElseThrow(()->new CustomException(ErrorCode.OAUTH_CONFIRMATION_REQUIRED));
        if(!linked.getMember().getId().equals(id))throw new CustomException(ErrorCode.ACCESS_DENIED);
    }
    public void link(String id,Map<String,String> tx,Long memberId) {
        checkTarget(tx,memberId);
        if(!"LINK".equals(tx.get("purpose")))throw new CustomException(ErrorCode.OAUTH_TRANSACTION_INVALID);
        if(account(tx).isPresent() || accounts.existsByMemberIdAndProvider(memberId,tx.get("provider")))throw new CustomException(ErrorCode.OAUTH_CONFLICT);
        claim(id);accounts.saveAndFlush(new SocialAccount(active(memberId),tx.get("provider"),tx.get("subjectHash")));
    }
    public void withdraw(String id,Map<String,String> tx,Long memberId) {
        checkTarget(tx,memberId);
        if(!"WITHDRAW".equals(tx.get("purpose")))throw new CustomException(ErrorCode.OAUTH_TRANSACTION_INVALID);
        requireOwned(tx,memberId);claim(id);Member m=active(memberId);refresh.delete(memberId);m.withdraw();
    }
}
