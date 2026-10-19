package com.back.coffeeprod.qa.recommendation;

import com.back.coffeeprod.domain.product.entity.ProductStatus;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import tools.jackson.databind.JsonNode;
import java.util.*;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

class Qa8RecommendationTest extends Qa8Support {
    List<Long> ids(JsonNode result){return result.valueStream().map(n->n.path("productId").asLong()).toList();}

    @Test @DisplayName("추천은 판매중 양수재고 프로필연결 상품만 반환하고 저장소를 바꾸지 않는다")
    void availableCandidatesOnly() throws Exception {
        Long profile=profile(single());var valid=product(profile,10000,1,ProductStatus.ON_SALE);
        product(profile,9000,0,ProductStatus.ON_SALE);product(profile,8000,1,ProductStatus.SOLD_OUT);
        product(profile,7000,1,ProductStatus.HIDDEN);product(null,6000,1,ProductStatus.ON_SALE);
        var before=state();
        assertEquals(List.of(valid.getId()),ids(data(http("POST",RECOMMEND,Map.of("preferredBody",3),null),200)));unchanged(before);
    }

    @ParameterizedTest(name="디카페인 / {0}") @ValueSource(strings={"true","false","unspecified"})
    @DisplayName("디카페인은 참과 거짓을 필터링하고 미입력은 두 후보를 허용한다")
    void decafFilter(String value) throws Exception {
        var regular=product(profile(single()),10000,1,ProductStatus.ON_SALE);
        var decafProfile=single();decafProfile.put("decaf",true);
        var decaf=product(profile(decafProfile),10000,1,ProductStatus.ON_SALE);
        var body=new LinkedHashMap<String,Object>(Map.of("preferredBody",3));
        if(!value.equals("unspecified"))body.put("decaf",Boolean.parseBoolean(value));
        var response=data(http("POST",RECOMMEND,body,null),200);
        assertEquals(value.equals("true")?List.of(decaf.getId()):value.equals("false")?List.of(regular.getId()):List.of(regular.getId(),decaf.getId()),ids(response));
        for(var candidate:response){
            assertEquals(value.equals("unspecified")?4:5,candidate.path("recommendationScore").asInt());
            assertEquals(!value.equals("unspecified"),candidate.path("reasons").valueStream().anyMatch(r->r.asText().equals("디카페인 여부 조건 충족")));
        }
    }

    @ParameterizedTest(name="범주 점수 / {0}") @ValueSource(strings={"roastLevel","beanType","processingMethodId"})
    @DisplayName("로스팅 유형 가공방식은 일치에 3점을 주고 불일치 후보도 유지한다")
    void categoricalWeights(String field) throws Exception {
        var matched=single();var different=single();Object preference;
        if(field.equals("roastLevel")){preference="MEDIUM";different.put("roastLevel","DARK");}
        else if(field.equals("beanType")){preference="SINGLE_ORIGIN";different=blend();}
        else {preference=method();matched.put("processingMethodId",preference);different.put("processingMethodId",method());}
        var first=product(profile(matched),20000,1,ProductStatus.ON_SALE);
        var second=product(profile(different),10000,1,ProductStatus.ON_SALE);
        var result=data(http("POST",RECOMMEND,Map.of(field,preference),null),200);
        assertEquals(List.of(first.getId(),second.getId()),ids(result));
        assertEquals(3,result.get(0).path("recommendationScore").asInt());assertEquals(1,result.get(0).path("reasons").size());
        String reason=switch(field){case "roastLevel"->"선호 로스팅 단계와 일치";case "beanType"->"선호 원두 유형과 일치";default->"선호 가공 방식과 일치";};
        assertEquals(reason,result.get(0).path("reasons").get(0).asText());
        assertEquals(0,result.get(1).path("recommendationScore").asInt());assertEquals(0,result.get(1).path("reasons").size());
    }

    static Stream<Arguments> sensoryWeights(){return Stream.of("Acidity","Body","Sweetness","Aroma").flatMap(field->Stream.of(
            Arguments.of(field,1,0),Arguments.of(field,2,2),Arguments.of(field,3,4),Arguments.of(field,4,2),Arguments.of(field,5,0)));}
    @ParameterizedTest(name="감각 점수 / {0} 선호{1} 기대{2}") @MethodSource("sensoryWeights")
    @DisplayName("감각 차이 0은 4점 차이 1은 2점 나머지는 0점이다")
    void sensoryWeights(String field,int preferred,int expected) throws Exception {
        product(profile(single()),10000,1,ProductStatus.ON_SALE);
        var candidate=data(http("POST",RECOMMEND,Map.of("preferred"+field,preferred),null),200).get(0);
        assertEquals(expected,candidate.path("recommendationScore").asInt());
        String label=switch(field){case "Acidity"->"산미";case "Body"->"바디";case "Sweetness"->"단맛";default->"향";};
        assertEquals(expected==0?List.of():List.of(label+(expected==4?" 선호와 일치":" 선호와 유사")),
                candidate.path("reasons").valueStream().map(JsonNode::asText).toList());
    }

    @Test @DisplayName("모든 조건 일치의 26점과 추천 이유 및 공개 응답 필드를 확인한다")
    void totalScoreAndResponseContract() throws Exception {
        Long method=method();var profile=single();profile.put("processingMethodId",method);
        var product=product(profile(profile),10000,1,ProductStatus.ON_SALE);
        var candidate=data(http("POST",RECOMMEND,Map.of("roastLevel","MEDIUM","beanType","SINGLE_ORIGIN","processingMethodId",method,
                "decaf",false,"preferredAcidity",3,"preferredBody",3,"preferredSweetness",3,"preferredAroma",3),null),200).get(0);
        assertEquals(26,candidate.path("recommendationScore").asInt());
        assertEquals(List.of("디카페인 여부 조건 충족","선호 로스팅 단계와 일치","선호 원두 유형과 일치","선호 가공 방식과 일치",
                "산미 선호와 일치","바디 선호와 일치","단맛 선호와 일치","향 선호와 일치"),candidate.path("reasons").valueStream().map(JsonNode::asText).toList());
        for(String field:List.of("productId","categoryName","sku","name","price","weightGrams","imageUrl","coffeeProfileId","coffeeProfileName",
                "processingMethodName","roastLevel","beanType","decaf","acidity","body","sweetness","aroma","flavorNotes","brewMethods","varieties","components","recommendationScore","reasons"))
            assertTrue(candidate.has(field),"공개 응답 필드: "+field);
        assertEquals(product.getId().longValue(),candidate.path("productId").asLong());assertEquals(10000,candidate.path("price").asInt());
        assertTrue(candidate.path("components").isArray());assertTrue(candidate.path("flavorNotes").isArray());
        assertTrue(candidate.path("brewMethods").isArray());assertTrue(candidate.path("varieties").isArray());
    }

    @Test @DisplayName("점수 내림차순 다음 가격과 ID 오름차순으로 결정적으로 정렬한다")
    void scorePriceIdOrdering() throws Exception {
        var matched=profile(single());var other=single();other.put("roastLevel","DARK");
        var expensive=product(matched,30000,1,ProductStatus.ON_SALE);
        var tieFirst=product(matched,10000,1,ProductStatus.ON_SALE);var tieSecond=product(matched,10000,1,ProductStatus.ON_SALE);
        var zero=product(profile(other),1,1,ProductStatus.ON_SALE);
        var expected=List.of(tieFirst.getId(),tieSecond.getId(),expensive.getId(),zero.getId());
        for(int repeat=0;repeat<2;repeat++)assertEquals(expected,ids(data(http("POST",RECOMMEND,Map.of("roastLevel","MEDIUM"),null),200)));
    }

    static Stream<Arguments> limits(){return Stream.of("public","personal").flatMap(route->Stream.of(-1,0,1,10,11).map(n->Arguments.of(route,n)));}
    @ParameterizedTest(name="추천 개수 / {0} / {1}") @MethodSource("limits")
    @DisplayName("공개와 내 추천의 기본5 허용1과10 거부0과11을 확인한다")
    void countLimits(String route,int limit) throws Exception {
        Long profile=profile(single());for(int i=0;i<12;i++)product(profile,10000+i,1,ProductStatus.ON_SALE);
        data(http("PUT",PREFERENCE,Map.of("preferredBody",3),token(owner)),200);var before=state();
        var body=new LinkedHashMap<String,Object>(Map.of("preferredBody",3));if(limit!=-1)body.put("limit",limit);
        var response=http(route.equals("public")?"POST":"GET",route.equals("public")?RECOMMEND:RECOMMEND+"/me"+(limit==-1?"":"?limit="+limit),
                route.equals("public")?body:null,route.equals("public")?null:token(owner));
        if(limit==0||limit==11)root(response,400);else assertEquals(limit==-1?5:limit,data(response,200).size());
        unchanged(before);
    }

    @Test @DisplayName("후보 부족과 빈 결과도 정상이며 빈 후보에서도 잘못된 개수는 거부한다")
    void fewAndEmptyCandidates() throws Exception {
        assertEquals(0,data(http("POST",RECOMMEND,Map.of("decaf",false),null),200).size());
        root(http("POST",RECOMMEND,Map.of("decaf",false,"limit",0),null),400);
        product(profile(single()),10000,1,ProductStatus.ON_SALE);product(profile(single()),20000,1,ProductStatus.ON_SALE);
        assertEquals(2,data(http("POST",RECOMMEND,Map.of("decaf",false,"limit",10),null),200).size());
    }
}
