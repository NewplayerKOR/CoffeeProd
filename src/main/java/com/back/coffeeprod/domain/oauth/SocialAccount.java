package com.back.coffeeprod.domain.oauth;

import com.back.coffeeprod.domain.member.entity.Member;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity @Getter @NoArgsConstructor
@Table(name="social_account",uniqueConstraints={@UniqueConstraint(name="uk_social_identity",columnNames={"provider","subject_hash"}),@UniqueConstraint(name="uk_social_member_provider",columnNames={"member_id","provider"})})
public class SocialAccount {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="member_id") private Member member;
    @Column(nullable=false,length=16) private String provider;
    @Column(name="subject_hash",nullable=false,length=64) private String subjectHash;
    public SocialAccount(Member member,String provider,String subjectHash) { this.member=member;this.provider=provider;this.subjectHash=subjectHash; }
}
