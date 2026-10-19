package com.back.coffeeprod.qa.recommendation;

import com.back.coffeeprod.domain.member.entity.*;
import com.back.coffeeprod.domain.product.entity.ProductStatus;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import java.util.*;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

class Qa8PreferenceHttpTest extends Qa8Support {
    static final List<String> FIELDS=List.of("roastLevel","beanType","processingMethodId","decaf",
            "preferredAcidity","preferredBody","preferredSweetness","preferredAroma");
    static Stream<Arguments> minimums() {
        return FIELDS.stream().flatMap(field->Stream.of("preference","recommend").map(route->Arguments.of(route,field)));
    }
    Map<String,Object> one(String field) {
        Object value=switch(field){case "roastLevel"->"LIGHT";case "beanType"->"SINGLE_ORIGIN";
            case "processingMethodId"->method();case "decaf"->false;default->3;};
        return Map.of(field,value);
    }
    @ParameterizedTest(name="최소 조건 / {0} / {1}") @MethodSource("minimums")
    @DisplayName("각 취향 단독 입력과 디카페인 거짓도 유효한 최소 조건이다")
    void minimumCondition(String route,String field) throws Exception {
        var body=one(field); var before=state();
        var result=data(http(route.equals("preference")?"PUT":"POST",route.equals("preference")?PREFERENCE:RECOMMEND,
                body,route.equals("preference")?token(owner):null),200);
        if(route.equals("recommend")){assertTrue(result.isArray());unchanged(before);}
        else {
            assertEquals(1,preferences.count());assertEquals(result,preferenceView(owner));
            if(field.equals("processingMethodId"))assertEquals(((Long)body.get(field)).longValue(),result.path("processingMethod").path("id").asLong());
            else assertEquals(tree(body.get(field)),result.path(field));
        }
    }

    @Test @DisplayName("미등록 취향과 내 추천은 404이고 다른 회원의 취향을 반환하지 않는다")
    void missingPreference() throws Exception {
        var other=member(Role.USER);
        data(http("PUT",PREFERENCE,Map.of("roastLevel","DARK"),token(other)),200);
        var before=state();
        error(http("GET",PREFERENCE,null,token(owner)),404,"저장된 커피 취향을 찾을 수 없습니다.");
        error(http("GET",RECOMMEND+"/me",null,token(owner)),404,"저장된 커피 취향을 찾을 수 없습니다.");
        unchanged(before);
    }

    @Test @DisplayName("취향 PUT은 같은 행을 전체 교체하고 누락 필드를 null로 초기화한다")
    void replacePreferenceAndClearOmittedFields() throws Exception {
        var first=new LinkedHashMap<String,Object>(Map.of("roastLevel","LIGHT","beanType","SINGLE_ORIGIN","processingMethodId",method(),
                "decaf",true,"preferredAcidity",1,"preferredBody",2,"preferredSweetness",3,"preferredAroma",4));
        var original=data(http("PUT",PREFERENCE,first,token(owner)),200);
        var next=data(http("PUT",PREFERENCE,Map.of("decaf",false),token(owner)),200);
        assertEquals(original.path("id"),next.path("id"));assertEquals(1,preferences.count());
        assertFalse(next.path("decaf").asBoolean());
        for(String field:List.of("roastLevel","beanType","processingMethod","preferredAcidity","preferredBody","preferredSweetness","preferredAroma"))
            assertTrue(next.path(field).isNull(),field+" 누락값 초기화");
        assertEquals(next,preferenceView(owner));
        assertEquals(next,data(http("GET",PREFERENCE,null,token(owner)),200));
    }

    @ParameterizedTest(name="빈 조건 / {0}") @ValueSource(strings={"preference","recommend","limitOnly"})
    @DisplayName("조건 없는 저장과 추천은 400이며 기존 취향을 보존한다")
    void emptyCondition(String route) throws Exception {
        data(http("PUT",PREFERENCE,Map.of("preferredBody",3),token(owner)),200); var before=state();
        error(http(route.equals("preference")?"PUT":"POST",route.equals("preference")?PREFERENCE:RECOMMEND,
                route.equals("limitOnly")?Map.of("limit",5):Map.of(),route.equals("preference")?token(owner):null),400,
                route.equals("preference")?"커피 취향을 하나 이상 입력해야 합니다.":"추천 조건을 하나 이상 입력해야 합니다.");
        unchanged(before);assertEquals(3,preferenceView(owner).path("preferredBody").asInt());
    }

    static Stream<Arguments> sensoryCases(){
        return Stream.of("preference","recommend").flatMap(route->FIELDS.stream().filter(f->f.startsWith("preferred"))
                .flatMap(field->Stream.of(0,1,5,6).map(value->Arguments.of(route,field,value))));
    }
    @ParameterizedTest(name="감각 경계 / {0} / {1}={2}") @MethodSource("sensoryCases")
    @DisplayName("네 감각 점수의 1과 5는 허용하고 0과 6은 HTTP 검증에서 거부한다")
    void sensoryBounds(String route,String field,int value) throws Exception {
        data(http("PUT",PREFERENCE,Map.of("decaf",false),token(owner)),200);var before=state();
        var response=http(route.equals("preference")?"PUT":"POST",route.equals("preference")?PREFERENCE:RECOMMEND,
                Map.of(field,value),route.equals("preference")?token(owner):null);
        if(value==0||value==6){
            var errors=root(response,400).path("errors");assertTrue(errors.isArray());
            assertTrue(errors.valueStream().anyMatch(e->field.equals(e.path("field").asText())));unchanged(before);
        }else{
            var result=data(response,200);
            if(route.equals("preference")){assertEquals(value,result.path(field).asInt());assertEquals(result,preferenceView(owner));}
            else unchanged(before);
        }
    }

    static Stream<Arguments> processingIds(){return Stream.of("preference","recommend").flatMap(r->Stream.of(-1L,0L,Long.MAX_VALUE).map(id->Arguments.of(r,id)));}
    @ParameterizedTest(name="가공 방식 / {0} / {1}") @MethodSource("processingIds")
    @DisplayName("가공 방식 ID의 양수와 존재 검증은 실패 후 기존 저장값을 보존한다")
    void invalidProcessingId(String route,Long id) throws Exception {
        data(http("PUT",PREFERENCE,Map.of("decaf",false),token(owner)),200);var before=state();
        var response=http(route.equals("preference")?"PUT":"POST",route.equals("preference")?PREFERENCE:RECOMMEND,
                Map.of("processingMethodId",id),route.equals("preference")?token(owner):null);
        if(id>0)error(response,404,"가공방식을 찾을 수 없습니다.");else root(response,400);
        unchanged(before);
    }

    static Stream<Arguments> accessCases(){return Stream.of("get","put","recommend").flatMap(r->Stream.of("anonymous","suspended","withdrawn").map(a->Arguments.of(r,a)));}
    @ParameterizedTest(name="개인 경로 / {0} / {1}") @MethodSource("accessCases")
    @DisplayName("개인 취향과 내 추천은 비인증 정지 탈퇴 접근을 거부하고 자원을 보존한다")
    void privateAccess(String route,String actor) throws Exception {
        data(http("PUT",PREFERENCE,Map.of("decaf",false),token(owner)),200);String token=token(owner);
        if(actor.equals("suspended"))status(owner,MemberStatus.SUSPENDED);
        if(actor.equals("withdrawn"))status(owner,MemberStatus.WITHDRAWN);
        var before=state();
        error(http(route.equals("put")?"PUT":"GET",route.equals("recommend")?RECOMMEND+"/me":PREFERENCE,
                route.equals("put")?Map.of("decaf",true):null,actor.equals("anonymous")?null:token),actor.equals("suspended")?403:401,
                actor.equals("anonymous")?"인증이 필요합니다.":actor.equals("suspended")?"정지된 회원입니다.":"탈퇴한 회원입니다.");
        unchanged(before);
    }

    @Test @DisplayName("두 회원의 취향 변경과 내 추천은 격리되고 직접 입력 추천과 일치한다")
    void isolationAndDirectRecommendationEquivalence() throws Exception {
        var other=member(Role.USER);
        var light=single();light.put("roastLevel","LIGHT");var dark=single();dark.put("roastLevel","DARK");
        var lightProduct=product(profile(light),15000,3,ProductStatus.ON_SALE);
        var darkProduct=product(profile(dark),10000,3,ProductStatus.ON_SALE);
        var condition=Map.<String,Object>of("roastLevel","LIGHT","beanType","SINGLE_ORIGIN","decaf",false,
                "preferredAcidity",1,"preferredBody",2,"preferredSweetness",3,"preferredAroma",4);
        data(http("PUT",PREFERENCE,condition,token(owner)),200);
        var otherPreference=data(http("PUT",PREFERENCE,Map.of("roastLevel","DARK"),token(other)),200);
        assertEquals(otherPreference,preferenceView(other));assertEquals(2,preferences.count());
        var direct=data(http("POST",RECOMMEND,condition,null),200);
        var personal=data(http("GET",RECOMMEND+"/me",null,token(owner)),200);
        assertEquals(direct,personal);assertEquals(lightProduct.getId().longValue(),personal.get(0).path("productId").asLong());
        assertEquals(darkProduct.getId().longValue(),data(http("GET",RECOMMEND+"/me",null,token(other)),200).get(0).path("productId").asLong());
        data(http("PUT",PREFERENCE,Map.of("preferredAroma",5),token(owner)),200);
        assertEquals(otherPreference,preferenceView(other));
        assertEquals(otherPreference,data(http("GET",PREFERENCE,null,token(other)),200));
    }

    @Test @DisplayName("관리자도 개인 경로에서는 자신의 취향을 저장하고 조회한다")
    void adminOwnPreference() throws Exception {
        var saved=data(http("PUT",PREFERENCE,Map.of("decaf",false),token(admin)),200);
        assertEquals(saved,data(http("GET",PREFERENCE,null,token(admin)),200));
        assertEquals(saved,preferenceView(admin));assertFalse(preferences.findByMemberId(owner.getId()).isPresent());
        assertTrue(data(http("GET",RECOMMEND+"/me",null,token(admin)),200).isArray());
    }

    static Stream<Arguments> invalidEnums(){return Stream.of("preference","recommend").flatMap(route->Stream.of("roastLevel","beanType").map(field->Arguments.of(route,field)));}
    @ParameterizedTest(name="잘못된 열거값 / {0} / {1}") @MethodSource("invalidEnums")
    @DisplayName("잘못된 로스팅과 원두 유형은400이며 취향과 상품을 보존한다")
    void invalidEnum(String route,String field) throws Exception {
        data(http("PUT",PREFERENCE,Map.of("decaf",false),token(owner)),200);var before=state();
        var response=http(route.equals("preference")?"PUT":"POST",route.equals("preference")?PREFERENCE:RECOMMEND,
                Map.of(field,"QA8_INVALID"),route.equals("preference")?token(owner):null);
        unchanged(before);
        System.out.printf("QA8_INVALID_ENUM route=%s field=%s http=%d resourcesPreserved=true%n",route,field,response.statusCode());
        root(response,400);
    }
}
