package com.back.coffeeprod.domain.recommendation.ai;

import com.back.coffeeprod.global.exception.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AiRecommendationService {
    private final AiCatalog catalog;
    private final AiModel model;
    private final AiQuota quota;
    private final JsonMapper json=JsonMapper.builder().build();
    public List<AiCatalog.Recommendation> recommend(long memberId,String preference) {
        if(preference==null || preference.isBlank() || preference.length()>1000)throw new CustomException(ErrorCode.AI_INPUT_TOO_LARGE);
        var candidates=catalog.candidates(memberId);
        if(candidates.isEmpty())throw new CustomException(ErrorCode.AI_NO_CANDIDATES);
        if(!model.available())throw new CustomException(ErrorCode.AI_UNAVAILABLE);
        // 원문 입력·회원 식별자·주문 정보를 외부 로그나 결과에 남기지 않는다.
        String input=json.writeValueAsString(Map.of("preference",preference.strip(),"candidates",candidates));
        var reservation=quota.reserve(memberId);
        catalog.requireActive(memberId);
        var result=model.generate(input,Duration.ofSeconds(15));
        quota.settle(reservation,result.usage());
        if(!result.complete() || result.text()==null || result.text().length()>32768)throw new CustomException(ErrorCode.AI_INVALID_RESPONSE);
        var allowed=candidates.stream().map(AiCatalog.Candidate::productId).collect(Collectors.toSet());
        var selections=parse(result.text(),allowed);
        var response=catalog.resolve(memberId,selections,allowed);
        if(response.isEmpty())throw new CustomException(ErrorCode.AI_INVALID_RESPONSE);
        return response;
    }
    List<AiCatalog.Selection> parse(String text,Set<Long> allowed) {
        try {
            var root=json.readTree(text);var items=root.path("items");
            if(!root.isObject() || root.size()!=1 || !items.isArray() || items.isEmpty() || items.size()>5)throw new IllegalArgumentException();
            var selections=new ArrayList<AiCatalog.Selection>();var seen=new HashSet<Long>();
            var attributes=Set.of("ROAST","DECAF","ACIDITY","BODY","SWEETNESS","AROMA","PRICE");
            for(var item:items) {
                if(!item.isObject() || item.size()!=2 || !item.path("productId").isIntegralNumber() || !item.path("productId").canConvertToLong())throw new IllegalArgumentException();
                long id=item.path("productId").asLong(); var evidence=item.path("attributes");
                if(!allowed.contains(id) || !seen.add(id) || !evidence.isArray() || evidence.isEmpty() || evidence.size()>3)throw new IllegalArgumentException();
                var facts=new ArrayList<String>();
                for(var value:evidence)if(value.isTextual() && attributes.contains(value.asText()) && !facts.contains(value.asText()))facts.add(value.asText());else throw new IllegalArgumentException();
                selections.add(new AiCatalog.Selection(id,List.copyOf(facts)));
            }
            return selections;
        } catch (RuntimeException e) { throw new CustomException(ErrorCode.AI_INVALID_RESPONSE); }
    }
}
