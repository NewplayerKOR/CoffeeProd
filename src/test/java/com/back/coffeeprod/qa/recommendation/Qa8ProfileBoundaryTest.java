package com.back.coffeeprod.qa.recommendation;

import com.back.coffeeprod.domain.product.entity.ProductStatus;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import tools.jackson.databind.JsonNode;
import java.util.*;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

class Qa8ProfileBoundaryTest extends Qa8Support {
    static final List<String> LINKS=List.of("flavorNotes","brewMethods","varieties");
    Map<String,Object> rich() {
        var body=blend();for(String field:LINKS)body.put(field,List.of(link(field,reference(field))));return body;
    }
    JsonNode save(Map<String,Object> body,boolean update,Long id) throws Exception {
        return data(http(update?"PUT":"POST",update?ADMIN_PROFILES+"/"+id:ADMIN_PROFILES,body,token(admin)),update?200:201);
    }
    void accepted(Map<String,Object> body,boolean update) throws Exception {
        Long id=update?profile(rich()):null;long count=profiles.count();
        var saved=save(body,update,id);Long savedId=saved.path("id").asLong();
        assertEquals(update?count:count+1,profiles.count());if(update)assertEquals(id,savedId);
        assertRequestedValues(body,saved);
        var stored=profileView(savedId);var queried=data(http("GET","/api/v1/coffee-profiles/"+savedId,null,null),200);
        assertAll(()->assertProfileStored(saved,stored),()->assertProfileStored(saved,queried));
    }
    void assertRequestedValues(Map<String,Object> request,JsonNode saved) {
        for(var field:request.entrySet()) {
            String name=field.getKey();var expected=tree(field.getValue());
            if(name.equals("processingMethodId")){assertEquals(expected,saved.path("processingMethod").path("id"));continue;}
            if(!List.of("flavorNotes","brewMethods","varieties","components").contains(name)) {
                assertEquals(expected,saved.path(name),"요청한 필드 저장: "+name);continue;
            }
            assertEquals(expected.size(),saved.path(name).size(),"요청한 연관 개수");
            for(int i=0;i<expected.size();i++) {
                var expectedLink=expected.get(i);var actualLink=saved.path(name).get(i);
                for(String property:List.of("flavorNoteId","brewMethodId","coffeeVarietyId","intensity","recommendationNote","originCountryCode","originRegion"))
                    if(expectedLink.has(property))assertEquals(expectedLink.path(property),actualLink.path(property),"요청한 연관 값과 순서: "+property);
                if(name.equals("components")) {
                    if(expectedLink.has("componentRatio"))assertEquals(0,new java.math.BigDecimal(expectedLink.path("componentRatio").asText())
                            .compareTo(new java.math.BigDecimal(actualLink.path("componentRatio").asText())));
                    else assertTrue(actualLink.path("componentRatio").isNull());
                }
            }
        }
    }
    void rejected(Map<String,Object> body,boolean update,int expected) throws Exception {
        Long id=profile(rich());var original=profileView(id);var before=state();
        var result=root(http(update?"PUT":"POST",update?ADMIN_PROFILES+"/"+id:ADMIN_PROFILES,body,token(admin)),expected);
        assertTrue(result.path("data").isNull());unchanged(before);assertEquals(original,profileView(id));
    }

    static Stream<Arguments> singleRejections(){return Stream.of(false,true).flatMap(update->Stream.of("missingCountry","components").map(kind->Arguments.of(update,kind)));}
    @ParameterizedTest(name="싱글 거부 / 수정{0} / {1}") @MethodSource("singleRejections")
    @DisplayName("싱글 오리진은 원산지가 필요하고 블렌드 구성요소를 허용하지 않는다")
    void invalidSingle(boolean update,String kind) throws Exception {
        var body=single();if(kind.equals("missingCountry"))body.remove("originCountryCode");else body.put("components",blend().get("components"));
        rejected(body,update,400);
    }

    static Stream<Arguments> singleFields(){return Stream.of(false,true).flatMap(update->Stream.of("originCountryCode","originRegion","farmOrCooperative","producer","altitudeMin","altitudeMax").map(field->Arguments.of(update,field)));}
    @ParameterizedTest(name="블렌드 단일 산지 / 수정{0} / {1}") @MethodSource("singleFields")
    @DisplayName("블렌드의 여섯 단일 산지 필드는 각각 등록과 수정에서 거부된다")
    void forbiddenSingleFields(boolean update,String field) throws Exception {
        var body=blend();body.put(field,field.startsWith("altitude")?1000:field.equals("originCountryCode")?"BR":"단일 산지");
        rejected(body,update,400);
    }

    static Stream<Arguments> componentCounts(){return Stream.of(false,true).flatMap(update->Stream.of(1,2,5,6).map(count->Arguments.of(update,count)));}
    @ParameterizedTest(name="구성 수 / 수정{0} / {1}") @MethodSource("componentCounts")
    @DisplayName("블렌드 구성요소 2개와5개는 허용하고1개와6개는 거부한다")
    void componentCount(boolean update,int count) throws Exception {
        var body=blend();var components=new ArrayList<Map<String,Object>>();
        for(String country:List.of("BR","CO","ET","KE","GT","PE").subList(0,count))components.add(component(country,null));
        body.put("components",components);if(count==1||count==6)rejected(body,update,400);else accepted(body,update);
    }

    static Stream<Arguments> ratioCases(){return Stream.of(false,true).flatMap(update->Stream.of("60/40","omitted","partial","sum99","zero","negative","over100","scale3").map(kind->Arguments.of(update,kind)));}
    @ParameterizedTest(name="구성 비율 / 수정{0} / {1}") @MethodSource("ratioCases")
    @DisplayName("블렌드 비율의 정상 합계 전체미입력과 부분입력 범위 정밀도를 검증한다")
    void ratios(boolean update,String kind) throws Exception {
        var body=blend();String first="60.00",second="40.00";
        switch(kind){case "omitted"->{first=null;second=null;}case "partial"->second=null;case "sum99"->first="59.00";
            case "zero"->{first="0.00";second="100.00";}case "negative"->{first="-1.00";second="100.00";}
            case "over100"->{first="100.01";second="0.01";}case "scale3"->{first="60.001";second="39.999";}}
        body.put("components",List.of(component("BR",first),component("CO",second)));
        if(kind.equals("60/40")||kind.equals("omitted"))accepted(body,update);else rejected(body,update,400);
    }

    static Stream<Arguments> componentValues(){return Stream.of("country","region","methodZero","methodMissing","nullEntry").map(kind->Arguments.of(kind));}
    @ParameterizedTest(name="구성 요소 값 / {0}") @MethodSource("componentValues")
    @DisplayName("블렌드 구성요소의 국가 지역 참조 ID와 null 항목을 검증한다")
    void componentValues(String kind) throws Exception {
        var body=blend();var first=component("BR","60.00");
        switch(kind){case "country"->first.put("originCountryCode","br");case "region"->first.put("originRegion","x".repeat(101));
            case "methodZero"->first.put("processingMethodId",0);case "methodMissing"->first.put("processingMethodId",Long.MAX_VALUE);}
        body.put("components",Arrays.asList(kind.equals("nullEntry")?null:first,component("CO","40.00")));
        rejected(body,true,kind.equals("methodMissing")?404:400);
    }

    static Stream<Arguments> linkCounts(){return Stream.of(false,true).flatMap(update->LINKS.stream().flatMap(field->Stream.of(false,true).map(over->Arguments.of(update,field,over))));}
    @ParameterizedTest(name="연관 개수 / 수정{0} / {1} / 초과{2}") @MethodSource("linkCounts")
    @DisplayName("향미5 추출법3 품종3의 최대 개수와 초과 거부를 확인한다")
    void linkCounts(boolean update,String field,boolean over) throws Exception {
        int count=(field.equals("flavorNotes")?5:3)+(over?1:0);var body=single();var links=new ArrayList<Map<String,Object>>();
        for(int i=0;i<count;i++)links.add(link(field,reference(field)));body.put(field,links);
        if(over)rejected(body,update,400);else accepted(body,update);
    }

    static Stream<Arguments> badLinks(){return Stream.of(false,true).flatMap(update->LINKS.stream().flatMap(field->Stream.of("duplicate","missingReference","nullEntry","nullId").map(kind->Arguments.of(update,field,kind))));}
    @ParameterizedTest(name="연관 오류 / 수정{0} / {1} / {2}") @MethodSource("badLinks")
    @DisplayName("연관 정보의 중복 없는 참조 null 항목과 ID 거부는 전체 변경을 롤백한다")
    void invalidLinks(boolean update,String field,String kind) throws Exception {
        var body=single();var first=link(field,kind.equals("missingReference")?Long.MAX_VALUE:reference(field));
        if(kind.equals("nullId"))first.put(field.equals("flavorNotes")?"flavorNoteId":field.equals("brewMethods")?"brewMethodId":"coffeeVarietyId",null);
        body.put(field,kind.equals("duplicate")?List.of(first,first):Arrays.asList(kind.equals("nullEntry")?null:first));
        rejected(body,update,kind.equals("missingReference")?404:400);
    }

    @ParameterizedTest(name="향미 강도 / {0}") @ValueSource(ints={0,1,5,6})
    @DisplayName("향미 강도의1과5를 허용하고0과6은 기존 연관 정보를 보존하며 거부한다")
    void intensity(int value) throws Exception {
        var body=single();var link=link("flavorNotes",reference("flavorNotes"));link.put("intensity",value);body.put("flavorNotes",List.of(link));
        if(value==0||value==6)rejected(body,true,400);else accepted(body,true);
    }

    @ParameterizedTest(name="null 목록 / {0}") @ValueSource(strings={"flavorNotes","brewMethods","varieties","components"})
    @DisplayName("null 연관 목록은 빈 목록과 구분해 거부하고 원본을 보존한다")
    void nullLists(String field) throws Exception {var body=blend();body.put(field,null);rejected(body,true,400);}

    @Test @DisplayName("추출 안내의500자는 허용하고501자는 거부한다")
    void recommendationNoteLength() throws Exception {
        var body=single();var link=link("brewMethods",reference("brewMethods"));link.put("recommendationNote","x".repeat(500));
        body.put("brewMethods",List.of(link));accepted(body,false);link.put("recommendationNote","x".repeat(501));rejected(body,true,400);
    }

    @Test @DisplayName("연관 목록 교체와 순서 변경은 저장되며 이전 연결 행을 삭제한다")
    void replacementOrderingAndOrphanRemoval() throws Exception {
        var body=rich();var original=new LinkedHashMap<String,List<Long>>();
        for(String field:LINKS){var ids=List.of(reference(field),reference(field));original.put(field,ids);body.put(field,ids.stream().map(id->link(field,id)).toList());}
        Long id=profile(body);var first=profileView(id);
        var oldJoinIds=new LinkedHashMap<String,List<Long>>();
        for(String table:List.of("coffee_profile_flavor_note","coffee_profile_brew_method","coffee_profile_variety","coffee_profile_component"))
            oldJoinIds.put(table,jdbc.queryForList("select "+table+"_id from "+table+" where coffee_profile_id=?",Long.class,id));
        for(String field:LINKS){var ids=original.get(field);body.put(field,List.of(link(field,ids.get(1)),link(field,reference(field))));}
        body.put("components",List.of(component("ET","30.00"),component("KE","70.00")));
        var next=save(body,true,id);assertProfileStored(next,profileView(id));
        assertEquals("ET",next.path("components").get(0).path("originCountryCode").asText());
        for(String field:LINKS){
            String key=field.equals("flavorNotes")?"flavorNoteId":field.equals("brewMethods")?"brewMethodId":"coffeeVarietyId";
            assertEquals(original.get(field).get(1).longValue(),next.path(field).get(0).path(key).asLong());
            assertFalse(next.path(field).valueStream().anyMatch(n->n.path(key).asLong()==original.get(field).get(0)));
        }
        for(var entry:oldJoinIds.entrySet()){
            var remaining=jdbc.queryForList("select "+entry.getKey()+"_id from "+entry.getKey()+" where coffee_profile_id=?",Long.class,id);
            assertTrue(Collections.disjoint(entry.getValue(),remaining),"이전 연결 행 제거");
            assertEquals(List.of(1,2),jdbc.queryForList("select display_order from "+entry.getKey()+" where coffee_profile_id=? order by display_order",Integer.class,id));
        }
        assertNotEquals(first.path("components"),next.path("components"));
        product(id,10000,1,ProductStatus.ON_SALE);
        var recommended=data(http("POST",RECOMMEND,Map.of("beanType","BLEND"),null),200).get(0);
        for(String field:List.of("flavorNotes","brewMethods","varieties","components"))assertEquals(next.path(field),recommended.path(field));
    }

    @Test @DisplayName("허용된 빈 연관 목록은 연결만 제거하고 참조 원본을 보존한다")
    void clearAllowedLists() throws Exception {
        var body=rich();Long id=profile(body);long flavorCount=flavors.count(),brewCount=brews.count(),varietyCount=varieties.count();
        for(String field:LINKS)body.put(field,List.of());var next=save(body,true,id);assertProfileStored(next,profileView(id));
        for(String field:LINKS)assertEquals(0,next.path(field).size());
        for(String table:List.of("coffee_profile_flavor_note","coffee_profile_brew_method","coffee_profile_variety"))
            assertEquals(0,jdbc.queryForObject("select count(*) from "+table+" where coffee_profile_id=?",Integer.class,id));
        assertEquals(flavorCount,flavors.count());assertEquals(brewCount,brews.count());assertEquals(varietyCount,varieties.count());
    }

    @Test @DisplayName("싱글과 블렌드 전환은 산지 필드와 구성요소를 새 상태에 맞춰 교체한다")
    void singleBlendTransitions() throws Exception {
        var body=single();body.put("originRegion","원본 지역");body.put("farmOrCooperative","원본 농장");body.put("producer","원본 생산자");
        body.put("altitudeMin",1000);body.put("altitudeMax",1500);Long id=profile(body);
        var blended=save(blend(),true,id);var blendedStored=profileView(id);assertEquals(2,blended.path("components").size());
        for(String field:List.of("originCountryCode","originRegion","farmOrCooperative","producer","altitudeMin","altitudeMax"))assertTrue(blended.path(field).isNull());
        var single=save(single(),true,id);var singleStored=profileView(id);assertEquals("BR",single.path("originCountryCode").asText());
        assertEquals(0,single.path("components").size());assertEquals(0,jdbc.queryForObject("select count(*) from coffee_profile_component where coffee_profile_id=?",Integer.class,id));
        assertAll(()->assertProfileStored(blended,blendedStored),()->assertProfileStored(single,singleStored));
    }

    @ParameterizedTest(name="프로필 관리 권한 / {0}") @ValueSource(strings={"anonymous","user"})
    @DisplayName("프로필 관리 변경은 비인증과 일반회원을 거부한다")
    void profileAdminOnly(String actor) throws Exception {
        var body=rich();Long id=profile(body);var before=state();
        for(String method:List.of("POST","PUT"))root(http(method,method.equals("POST")?ADMIN_PROFILES:ADMIN_PROFILES+"/"+id,
                single(),actor.equals("anonymous")?null:token(owner)),actor.equals("anonymous")?401:403);
        unchanged(before);assertEquals(profileView(id),data(http("GET","/api/v1/coffee-profiles/"+id,null,null),200));
    }

    @Test @DisplayName("프로필 수정 응답의 updatedAt은 커밋된 새 시각과 일치한다")
    void updateResponseHasCommittedAuditTimestamp() throws Exception {
        var body=single();Long id=profile(body);
        jdbc.update("update coffee_profile set updated_at=? where coffee_profile_id=?",
                java.sql.Timestamp.from(java.time.Instant.parse("2000-01-01T00:00:00Z")),id);
        var before=profileView(id);body.put("profileName","새 프로필 이름");
        var response=save(body,true,id);var stored=profileView(id);
        assertEquals("새 프로필 이름",stored.path("profileName").asText());
        assertNotEquals(before.path("updatedAt"),stored.path("updatedAt"));
        System.out.printf("QA8_PROFILE_AUDIT responseIsPrevious=%s storedWasUpdated=%s%n",
                response.path("updatedAt").equals(before.path("updatedAt")),!stored.path("updatedAt").equals(before.path("updatedAt")));
        assertProfileStored(response,stored);
    }
}
