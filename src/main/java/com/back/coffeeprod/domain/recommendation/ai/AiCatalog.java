package com.back.coffeeprod.domain.recommendation.ai;

import com.back.coffeeprod.domain.product.entity.*;
import com.back.coffeeprod.domain.product.repository.ProductRepository;
import com.back.coffeeprod.domain.member.entity.MemberStatus;
import com.back.coffeeprod.domain.member.repository.MemberRepository;
import com.back.coffeeprod.global.exception.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
@RequiredArgsConstructor
public class AiCatalog {
    private final ProductRepository products;
    private final MemberRepository members;
    public record Candidate(long productId, int price, String roast, boolean decaf, short acidity, short body, short sweetness, short aroma) {}
    public record Selection(long productId,List<String> attributes) {}
    public record Recommendation(long productId,String name,int price,int stockQuantity,int weightGrams,String href,List<String> reasons) {}
    public void requireActive(long memberId) {
        var member=members.findById(memberId).orElseThrow(()->new CustomException(ErrorCode.AUTHENTICATION_REQUIRED));
        if(member.getStatus()!=MemberStatus.ACTIVE)throw new CustomException(ErrorCode.ACCESS_DENIED);
    }
    @Transactional(readOnly=true)
    public List<Candidate> candidates(long memberId) {
        requireActive(memberId);
        return products.findAiCandidates(PageRequest.of(0,20)).stream().map(p-> {
            var c=p.getCoffeeProfile();
            return new Candidate(p.getId(),p.getPrice(),p.getRoastLevel().name(),c.isDecaf(),c.getAcidity(),c.getBody(),c.getSweetness(),c.getAroma());
        }).toList();
    }
    // 외부 호출을 DB 트랜잭션 밖에서 수행하고 결과 작성 직전에 새 트랜잭션으로 다시 읽는다.
    @Transactional(readOnly=true)
    public List<Recommendation> resolve(long memberId,List<Selection> selections,Set<Long> allowed) {
        requireActive(memberId);
        var fresh=products.findAiAvailable(selections.stream().map(Selection::productId).toList());
        var byId=new HashMap<Long,Product>();fresh.forEach(p->byId.put(p.getId(),p));
        var results=new ArrayList<Recommendation>();
        for(var selection:selections) {
            var p=byId.get(selection.productId());if(p==null || !allowed.contains(p.getId()))continue;
            var c=p.getCoffeeProfile();
            var facts=Map.of("ROAST","로스팅: "+p.getRoastLevel().name(),"DECAF",c.isDecaf()?"디카페인 원두":"카페인 함유 원두",
                "ACIDITY","산미 "+c.getAcidity()+"/5", "BODY","바디 "+c.getBody()+"/5", "SWEETNESS","단맛 "+c.getSweetness()+"/5",
                "AROMA","향 "+c.getAroma()+"/5", "PRICE","판매 가격 "+p.getPrice()+"원");
            var reasons=selection.attributes().stream().map(facts::get).toList();
            results.add(new Recommendation(p.getId(),p.getName(),p.getPrice(),p.getStockQuantity(),p.getWeightGrams(),"/products/"+p.getId(),reasons));
        }
        return results;
    }
}
