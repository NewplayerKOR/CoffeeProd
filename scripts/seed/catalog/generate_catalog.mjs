import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const scriptDir = dirname(fileURLToPath(import.meta.url));
const csvDir = join(scriptDir, "csv");

const categories = [
    { category_code: "SINGLE_ORIGIN", name: "싱글 오리진" },
    { category_code: "BLEND", name: "블렌드" },
    { category_code: "DECAF", name: "디카페인" }
];

const processingMethods = [
    ["WASHED", "Washed", "점액질을 물로 제거해 향미의 명료도와 산뜻한 인상을 표현함"],
    ["NATURAL", "Natural", "커피 체리를 과육째 건조해 과일 향과 단맛을 표현함"],
    ["HONEY", "Honey", "점액질 일부를 남겨 단맛과 질감의 균형을 표현함"],
    ["PULPED_NATURAL", "Pulped Natural", "과육을 제거하고 점액질을 남겨 건조하는 방식을 표현함"],
    ["WET_HULLED", "Wet Hulled", "높은 수분 상태에서 파치먼트를 제거하는 인도네시아식 방식을 표현함"],
    ["ANAEROBIC", "Anaerobic", "밀폐 환경에서 발효해 선명한 발효 향과 과일 인상을 표현함"],
    ["CARBONIC_MACERATION", "Carbonic Maceration", "이산화탄소 환경의 발효로 복합적인 과일 인상을 표현함"],
    ["SWISS_WATER", "Swiss Water", "물 기반 공정으로 카페인을 제거하는 방식을 표현함"],
    ["SUGARCANE", "Sugarcane Decaf", "사탕수수 유래 성분을 활용한 디카페인 방식을 표현함"]
].map(([code, name, description]) => ({ code, name, description }));

const flavorNotes = [
    ["FLORAL", "플로럴", "꽃을 떠올리는 향긋한 인상"],
    ["JASMINE", "자스민", "흰 꽃을 떠올리는 맑은 향"],
    ["CITRUS", "시트러스", "감귤류를 떠올리는 밝은 인상"],
    ["LEMON", "레몬", "레몬을 떠올리는 산뜻한 인상"],
    ["ORANGE", "오렌지", "오렌지를 떠올리는 단 산미"],
    ["BERRY", "베리", "붉거나 검은 베리류를 떠올리는 향미"],
    ["BLACK_CURRANT", "블랙커런트", "검은 과실을 떠올리는 진한 향미"],
    ["RED_FRUIT", "붉은 과일", "딸기와 체리를 떠올리는 과일 인상"],
    ["STONE_FRUIT", "핵과류", "복숭아와 살구를 떠올리는 향미"],
    ["TROPICAL_FRUIT", "열대과일", "망고와 파인애플을 떠올리는 향미"],
    ["RED_APPLE", "붉은 사과", "사과를 떠올리는 산미와 단맛"],
    ["DRIED_FRUIT", "건과일", "건포도와 말린 자두를 떠올리는 단맛"],
    ["CHOCOLATE", "초콜릿", "초콜릿을 떠올리는 부드러운 단맛"],
    ["DARK_CHOCOLATE", "다크 초콜릿", "카카오 함량이 높은 초콜릿 같은 쌉쌀한 단맛"],
    ["MILK_CHOCOLATE", "밀크 초콜릿", "우유가 섞인 초콜릿 같은 부드러운 단맛"],
    ["COCOA", "코코아", "코코아 가루를 떠올리는 고소하고 쌉쌀한 인상"],
    ["NUTTY", "견과류", "구운 견과를 떠올리는 고소한 향미"],
    ["HAZELNUT", "헤이즐넛", "헤이즐넛을 떠올리는 고소한 단맛"],
    ["CARAMEL", "카라멜", "카라멜과 토피를 떠올리는 단맛"],
    ["BROWN_SUGAR", "흑설탕", "흑설탕을 떠올리는 깊은 단맛"],
    ["MOLASSES", "당밀", "당밀을 떠올리는 묵직한 단맛"],
    ["HONEY", "꿀", "꿀을 떠올리는 매끄러운 단맛"],
    ["VANILLA", "바닐라", "바닐라를 떠올리는 부드러운 향"],
    ["TEA_LIKE", "티 라이크", "차를 떠올리는 맑고 긴 여운"],
    ["SPICE", "스파이스", "계피와 정향을 떠올리는 향신료 인상"],
    ["EARTHY", "어시", "젖은 흙과 삼나무를 떠올리는 묵직한 인상"]
].map(([code, name, description]) => ({ code, name, description }));

const brewMethods = [
    ["POUR_OVER", "푸어오버", "종이 필터로 향미의 명료도를 표현함"],
    ["ESPRESSO", "에스프레소", "고압 추출로 농도와 질감을 표현함"],
    ["FRENCH_PRESS", "프렌치프레스", "침지와 금속 필터로 오일감과 바디를 표현함"],
    ["AEROPRESS", "에어로프레스", "침지와 압력을 조합해 균형 있는 추출을 표현함"],
    ["COLD_BREW", "콜드브루", "저온 장시간 추출로 부드러운 단맛을 표현함"],
    ["MOKA_POT", "모카포트", "증기압으로 진하고 묵직한 질감을 표현함"],
    ["DRIP_MACHINE", "드립 머신", "가정용 자동 드립의 일관된 추출을 표현함"],
    ["SIPHON", "사이폰", "진공 압력 변화를 활용해 향과 질감을 표현함"]
].map(([code, name, description]) => ({ code, name, description }));

const coffeeVarieties = [
    ["ETHIOPIAN_HEIRLOOM", "에티오피아 토착종", "에티오피아 지역의 다양한 토착 품종군"],
    ["BOURBON", "버번", "다양한 지역에서 재배되는 전통 아라비카 품종"],
    ["TYPICA", "티피카", "여러 현대 품종의 기반이 된 전통 아라비카 품종"],
    ["CATURRA", "카투라", "버번 계열의 왜성 품종"],
    ["CASTILLO", "카스티요", "콜롬비아에서 널리 재배되는 품종"],
    ["COLOMBIA", "콜롬비아", "콜롬비아의 생산 환경에 맞춰 개발된 품종"],
    ["MUNDO_NOVO", "문도 노보", "브라질에서 널리 재배되는 교배 계열 품종"],
    ["CATUAI", "카투아이", "문도 노보와 카투라 계열의 교배 품종"],
    ["YELLOW_BOURBON", "옐로 버번", "노란색 열매 특성을 가진 버번 계열"],
    ["SL28", "SL28", "케냐에서 널리 알려진 고품질 품종"],
    ["SL34", "SL34", "케냐 생산지에서 널리 재배되는 품종"],
    ["RUIRU11", "Ruiru 11", "케냐의 재배 환경을 고려해 개발된 품종"],
    ["BATIAN", "Batian", "케냐에서 개발된 아라비카 품종"],
    ["SL14", "SL14", "동아프리카 일부 생산지에서 재배되는 품종"],
    ["NYASALAND", "Nyasaland", "동아프리카에 전해진 전통 아라비카 계열"],
    ["VILLA_SARCHI", "비야 사르치", "코스타리카에서 발견된 버번 계열 왜성 품종"],
    ["PACAS", "파카스", "엘살바도르에서 발견된 버번 계열 왜성 품종"],
    ["PACAMARA", "파카마라", "파카스와 마라고지페 계열의 교배 품종"],
    ["IHCAFE90", "IHCAFE 90", "온두라스 생산 환경에 맞춰 보급된 품종"],
    ["LEMPIRA", "렘피라", "온두라스에서 널리 재배되는 품종"],
    ["GEISHA", "게이샤", "파나마 스페셜티 커피로 널리 알려진 품종"],
    ["CATIMOR", "카티모르", "카투라와 하이브리드 계열을 바탕으로 한 품종군"],
    ["ARUSHA", "아루샤", "파푸아뉴기니와 동아프리카 일부에서 재배되는 품종"],
    ["S795", "S795", "인도와 동남아시아 일부에서 재배되는 품종"]
].map(([code, name, description]) => ({ code, name, description }));

const originLots = [
    ["ET", "Yirgacheffe", "WASHED", ["ETHIOPIAN_HEIRLOOM"], [["JASMINE", 5], ["CITRUS", 4], ["TEA_LIKE", 4]], 1800, 2200, [5, 2, 4, 5], 19500],
    ["ET", "Guji", "NATURAL", ["ETHIOPIAN_HEIRLOOM"], [["BERRY", 5], ["STONE_FRUIT", 4], ["FLORAL", 4]], 1900, 2300, [4, 3, 5, 5], 20500],
    ["ET", "Sidama", "HONEY", ["ETHIOPIAN_HEIRLOOM"], [["BROWN_SUGAR", 4], ["STONE_FRUIT", 4], ["CITRUS", 3]], 1750, 2150, [4, 3, 5, 4], 19000],
    ["ET", "Limu", "WASHED", ["ETHIOPIAN_HEIRLOOM"], [["CITRUS", 4], ["FLORAL", 4], ["TEA_LIKE", 4]], 1650, 2000, [4, 2, 4, 4], 18500],
    ["KE", "Nyeri", "WASHED", ["SL28", "SL34"], [["BLACK_CURRANT", 5], ["CITRUS", 5], ["BROWN_SUGAR", 3]], 1700, 2100, [5, 3, 4, 5], 22000],
    ["KE", "Kirinyaga", "WASHED", ["SL28", "RUIRU11"], [["BERRY", 5], ["CITRUS", 4], ["CARAMEL", 3]], 1600, 1950, [5, 3, 4, 4], 21500],
    ["KE", "Embu", "WASHED", ["SL34", "BATIAN"], [["STONE_FRUIT", 4], ["BERRY", 4], ["TEA_LIKE", 3]], 1500, 1900, [4, 3, 4, 4], 20500],
    ["RW", "Nyamasheke", "WASHED", ["BOURBON"], [["CITRUS", 4], ["TEA_LIKE", 4], ["CARAMEL", 3]], 1700, 2100, [4, 3, 4, 4], 19000],
    ["RW", "Huye", "NATURAL", ["BOURBON"], [["BERRY", 4], ["STONE_FRUIT", 4], ["BROWN_SUGAR", 4]], 1650, 2000, [4, 3, 5, 4], 19500],
    ["BI", "Kayanza", "WASHED", ["BOURBON"], [["CITRUS", 4], ["RED_APPLE", 4], ["TEA_LIKE", 3]], 1750, 2050, [4, 3, 4, 4], 19000],
    ["UG", "Bugisu", "WASHED", ["SL14", "NYASALAND"], [["SPICE", 4], ["DARK_CHOCOLATE", 4], ["CITRUS", 3]], 1500, 1900, [3, 4, 4, 4], 17500],
    ["UG", "Rwenzori", "NATURAL", ["NYASALAND"], [["BERRY", 4], ["DRIED_FRUIT", 4], ["CHOCOLATE", 3]], 1450, 1900, [3, 4, 5, 4], 18000],
    ["CO", "Huila", "HONEY", ["CATURRA", "CASTILLO"], [["CARAMEL", 4], ["BROWN_SUGAR", 4], ["CITRUS", 3]], 1500, 1900, [4, 3, 4, 4], 18000],
    ["CO", "Narino", "WASHED", ["CATURRA", "CASTILLO"], [["RED_APPLE", 4], ["CITRUS", 4], ["CARAMEL", 3]], 1800, 2200, [5, 3, 4, 4], 19500],
    ["CO", "Cauca", "WASHED", ["CASTILLO", "COLOMBIA"], [["STONE_FRUIT", 4], ["BROWN_SUGAR", 4], ["NUTTY", 3]], 1600, 2050, [4, 3, 4, 4], 18500],
    ["CO", "Sierra Nevada", "NATURAL", ["CASTILLO", "TYPICA"], [["CHOCOLATE", 4], ["RED_APPLE", 3], ["CARAMEL", 4]], 1200, 1750, [3, 4, 5, 3], 17500],
    ["BR", "Cerrado", "NATURAL", ["MUNDO_NOVO", "CATUAI"], [["CHOCOLATE", 5], ["NUTTY", 5], ["CARAMEL", 4]], 900, 1200, [2, 4, 5, 3], 15000],
    ["BR", "Sul de Minas", "PULPED_NATURAL", ["YELLOW_BOURBON", "CATUAI"], [["MILK_CHOCOLATE", 5], ["NUTTY", 4], ["BROWN_SUGAR", 4]], 950, 1300, [2, 4, 5, 4], 16000],
    ["BR", "Mantiqueira", "NATURAL", ["YELLOW_BOURBON"], [["RED_FRUIT", 4], ["HONEY", 4], ["NUTTY", 3]], 1100, 1450, [3, 4, 5, 4], 17500],
    ["BR", "Mogiana", "HONEY", ["CATUAI", "MUNDO_NOVO"], [["CARAMEL", 5], ["HAZELNUT", 4], ["CHOCOLATE", 4]], 900, 1250, [2, 4, 5, 3], 15500],
    ["CR", "Tarrazu", "HONEY", ["CATURRA", "CATUAI"], [["CITRUS", 4], ["CARAMEL", 4], ["RED_APPLE", 4]], 1400, 1900, [4, 3, 5, 4], 19500],
    ["CR", "West Valley", "NATURAL", ["VILLA_SARCHI", "CATURRA"], [["STONE_FRUIT", 4], ["TROPICAL_FRUIT", 4], ["HONEY", 4]], 1200, 1700, [4, 3, 5, 4], 20500],
    ["CR", "Tres Rios", "WASHED", ["CATURRA", "CATUAI"], [["CITRUS", 4], ["COCOA", 3], ["SPICE", 3]], 1200, 1650, [4, 3, 4, 4], 18500],
    ["GT", "Huehuetenango", "WASHED", ["BOURBON", "CATURRA"], [["RED_APPLE", 4], ["CHOCOLATE", 4], ["CITRUS", 3]], 1500, 2000, [4, 4, 4, 4], 19000],
    ["GT", "Antigua", "WASHED", ["BOURBON", "CATURRA"], [["DARK_CHOCOLATE", 4], ["SPICE", 4], ["CARAMEL", 4]], 1400, 1800, [3, 4, 5, 4], 18500],
    ["GT", "Atitlan", "NATURAL", ["TYPICA", "BOURBON"], [["STONE_FRUIT", 4], ["COCOA", 4], ["BROWN_SUGAR", 4]], 1500, 1900, [3, 4, 5, 4], 19000],
    ["SV", "Apaneca-Ilamatepec", "HONEY", ["PACAS", "PACAMARA"], [["RED_FRUIT", 4], ["CARAMEL", 4], ["COCOA", 3]], 1300, 1800, [4, 3, 5, 4], 20000],
    ["SV", "Chalatenango", "WASHED", ["PACAMARA", "BOURBON"], [["FLORAL", 4], ["STONE_FRUIT", 5], ["CITRUS", 4]], 1450, 1950, [5, 3, 4, 5], 22000],
    ["HN", "Marcala", "WASHED", ["CATUAI", "IHCAFE90"], [["CARAMEL", 4], ["RED_APPLE", 4], ["NUTTY", 3]], 1300, 1750, [4, 3, 4, 4], 17500],
    ["HN", "Copan", "NATURAL", ["LEMPIRA", "CATUAI"], [["CHOCOLATE", 4], ["DRIED_FRUIT", 4], ["SPICE", 3]], 1100, 1550, [3, 4, 5, 3], 17000],
    ["PA", "Boquete", "WASHED", ["GEISHA"], [["JASMINE", 5], ["CITRUS", 5], ["TEA_LIKE", 5]], 1600, 2000, [5, 2, 4, 5], 28500],
    ["PA", "Volcan", "NATURAL", ["GEISHA", "CATUAI"], [["TROPICAL_FRUIT", 5], ["BERRY", 4], ["FLORAL", 5]], 1500, 1900, [4, 3, 5, 5], 27500],
    ["ID", "Gayo", "WET_HULLED", ["TYPICA", "CATIMOR"], [["EARTHY", 4], ["SPICE", 4], ["DARK_CHOCOLATE", 4]], 1200, 1600, [2, 5, 3, 4], 17500],
    ["ID", "Java", "WASHED", ["TYPICA", "CATIMOR"], [["BROWN_SUGAR", 4], ["SPICE", 3], ["COCOA", 4]], 1100, 1550, [3, 4, 4, 3], 17000],
    ["PG", "Eastern Highlands", "WASHED", ["ARUSHA", "TYPICA"], [["TROPICAL_FRUIT", 4], ["RED_APPLE", 3], ["CHOCOLATE", 3]], 1400, 1900, [4, 3, 4, 4], 18500],
    ["IN", "Chikmagalur", "NATURAL", ["S795", "CATIMOR"], [["SPICE", 5], ["DARK_CHOCOLATE", 4], ["NUTTY", 4]], 1000, 1450, [2, 5, 4, 4], 16500]
].map((lot, index) => ({
    lotNo: index + 1,
    country: lot[0], region: lot[1], process: lot[2], varieties: lot[3],
    flavors: lot[4], altitudeMin: lot[5], altitudeMax: lot[6],
    scores: lot[7], basePrice: lot[8]
}));

const blendRecipes = [
    ["Morning Balance", [["BR", "Cerrado", "NATURAL", 50], ["CO", "Huila", "HONEY", 30], ["ET", "Sidama", "WASHED", 20]], [["CARAMEL", 5], ["CHOCOLATE", 4], ["CITRUS", 3]], ["CATUAI", "CASTILLO", "ETHIOPIAN_HEIRLOOM"], [3, 4, 5, 4], 16500, false, ""],
    ["Espresso House", [["BR", "Sul de Minas", "PULPED_NATURAL", 45], ["CO", "Huila", "WASHED", 35], ["IN", "Chikmagalur", "NATURAL", 20]], [["DARK_CHOCOLATE", 5], ["NUTTY", 4], ["CARAMEL", 4]], ["YELLOW_BOURBON", "CASTILLO", "S795"], [2, 5, 4, 4], 16000, false, ""],
    ["East Africa Floral", [["ET", "Yirgacheffe", "WASHED", 45], ["KE", "Nyeri", "WASHED", 35], ["RW", "Nyamasheke", "WASHED", 20]], [["JASMINE", 5], ["CITRUS", 5], ["TEA_LIKE", 4]], ["ETHIOPIAN_HEIRLOOM", "SL28", "BOURBON"], [5, 2, 4, 5], 21500, false, ""],
    ["Chocolate Nut", [["BR", "Cerrado", "NATURAL", 60], ["GT", "Antigua", "WASHED", 25], ["IN", "Chikmagalur", "NATURAL", 15]], [["CHOCOLATE", 5], ["HAZELNUT", 5], ["BROWN_SUGAR", 4]], ["MUNDO_NOVO", "BOURBON", "S795"], [2, 5, 5, 3], 15500, false, ""],
    ["Latin Citrus", [["CO", "Narino", "WASHED", 45], ["CR", "Tarrazu", "HONEY", 35], ["SV", "Chalatenango", "WASHED", 20]], [["CITRUS", 5], ["RED_APPLE", 4], ["CARAMEL", 4]], ["CATURRA", "PACAMARA", "CATUAI"], [5, 3, 4, 4], 19500, false, ""],
    ["Tropical Natural", [["ET", "Guji", "NATURAL", 40], ["PA", "Volcan", "NATURAL", 35], ["CR", "West Valley", "NATURAL", 25]], [["TROPICAL_FRUIT", 5], ["BERRY", 5], ["FLORAL", 4]], ["ETHIOPIAN_HEIRLOOM", "GEISHA", "VILLA_SARCHI"], [4, 3, 5, 5], 23000, false, ""],
    ["Cold Brew Cocoa", [["BR", "Mogiana", "HONEY", 50], ["HN", "Copan", "NATURAL", 30], ["ID", "Java", "WASHED", 20]], [["COCOA", 5], ["CARAMEL", 4], ["DRIED_FRUIT", 3]], ["CATUAI", "LEMPIRA", "TYPICA"], [2, 5, 5, 3], 16000, false, ""],
    ["Highland Sweet", [["GT", "Huehuetenango", "WASHED", 40], ["PG", "Eastern Highlands", "WASHED", 35], ["BI", "Kayanza", "WASHED", 25]], [["RED_APPLE", 4], ["BROWN_SUGAR", 5], ["CHOCOLATE", 3]], ["BOURBON", "ARUSHA", "CATURRA"], [4, 4, 5, 4], 18500, false, ""],
    ["Deep Roast Foundation", [["ID", "Gayo", "WET_HULLED", 40], ["BR", "Cerrado", "NATURAL", 40], ["UG", "Bugisu", "WASHED", 20]], [["DARK_CHOCOLATE", 5], ["SPICE", 5], ["MOLASSES", 4]], ["CATIMOR", "MUNDO_NOVO", "SL14"], [1, 5, 4, 4], 15500, false, ""],
    ["Seasonal Red Fruit", [["RW", "Huye", "NATURAL", 40], ["SV", "Apaneca-Ilamatepec", "HONEY", 35], ["CO", "Cauca", "WASHED", 25]], [["RED_FRUIT", 5], ["STONE_FRUIT", 4], ["HONEY", 4]], ["BOURBON", "PACAS", "CASTILLO"], [4, 3, 5, 5], 20500, false, ""],
    ["Decaf Caramel", [["CO", "Huila", "WASHED", 60], ["BR", "Sul de Minas", "PULPED_NATURAL", 40]], [["CARAMEL", 5], ["MILK_CHOCOLATE", 4], ["BROWN_SUGAR", 4]], ["CASTILLO", "YELLOW_BOURBON"], [2, 4, 5, 3], 18500, true, "SUGARCANE"],
    ["Decaf Floral", [["ET", "Sidama", "WASHED", 55], ["RW", "Nyamasheke", "WASHED", 45]], [["FLORAL", 4], ["CITRUS", 4], ["TEA_LIKE", 4]], ["ETHIOPIAN_HEIRLOOM", "BOURBON"], [4, 2, 4, 4], 20000, true, "SWISS_WATER"]
].map((recipe, index) => ({
    recipeNo: index + 1,
    name: recipe[0], components: recipe[1], flavors: recipe[2],
    varieties: recipe[3], scores: recipe[4], basePrice: recipe[5],
    decaf: recipe[6], decafMethod: recipe[7]
}));

const profiles = [];
const components = [];
const profileFlavorNotes = [];
const profileBrewMethods = [];
const profileVarieties = [];
const products = [];

const roastName = { LIGHT: "라이트", MEDIUM: "미디엄", DARK: "다크" };
const roastSuffix = { LIGHT: "L", MEDIUM: "M", DARK: "D" };
const deepRoastCountries = new Set(["BR", "UG", "ID", "IN"]);

function clampScore(value) {
    return Math.max(1, Math.min(5, value));
}

function scoresForRoast(scores, roast) {
    const [acidity, body, sweetness, aroma] = scores;
    if (roast === "LIGHT") {
        return [clampScore(acidity + 1), clampScore(body - 1), sweetness, clampScore(aroma + 1)];
    }
    if (roast === "DARK") {
        return [clampScore(acidity - 1), clampScore(body + 1), clampScore(sweetness - 1), aroma];
    }
    return scores;
}

function addRelations(profileKey, flavors, brews, varieties) {
    flavors.forEach(([code, intensity], index) => profileFlavorNotes.push({
        profile_key: profileKey,
        flavor_note_code: code,
        display_order: index + 1,
        intensity
    }));
    brews.forEach(([code, note], index) => profileBrewMethods.push({
        profile_key: profileKey,
        brew_method_code: code,
        display_order: index + 1,
        recommendation_note: note
    }));
    varieties.forEach((code, index) => profileVarieties.push({
        profile_key: profileKey,
        variety_code: code,
        display_order: index + 1
    }));
}

function addProducts(profile, basePrice) {
    [200, 500].forEach((weight, index) => {
        const sequence = products.length + 1;
        const status = sequence % 29 === 0
            ? "HIDDEN"
            : sequence % 17 === 0 ? "SOLD_OUT" : "ON_SALE";
        const price = weight === 200
            ? basePrice
            : Math.round(basePrice * 2.25 / 500) * 500;
        const stock = status === "SOLD_OUT" ? 0 : 18 + sequence % 83;

        products.push({
            sku: `CP-${profile.profile_key.replaceAll("_", "-")}-${weight}`,
            profile_key: profile.profile_key,
            category_code: profile.decaf ? "DECAF" : profile.bean_type,
            weight_grams: weight,
            name: `${profile.profile_name} ${weight}g`,
            price,
            stock_quantity: stock,
            roast_level: profile.roast_level,
            description: `${profile.summary} ${weight}g 포장으로 로스팅 단계와 원산지 메타데이터를 확인할 수 있음`,
            image_url: `/images/catalog/${profile.profile_key.toLowerCase()}.webp`,
            status,
            display_order: index + 1
        });
    });
}

originLots.forEach((lot) => {
    const roasts = deepRoastCountries.has(lot.country)
        ? ["MEDIUM", "DARK"]
        : ["LIGHT", "MEDIUM"];

    roasts.forEach((roast) => {
        const profileKey = `SO${String(lot.lotNo).padStart(3, "0")}_${roastSuffix[roast]}`;
        const [acidity, body, sweetness, aroma] = scoresForRoast(lot.scores, roast);
        const profile = {
            profile_key: profileKey,
            profile_name: `${lot.country} ${lot.region} ${lot.process} ${roastName[roast]}`,
            bean_type: "SINGLE_ORIGIN",
            processing_method_code: lot.process,
            origin_country_code: lot.country,
            origin_region: lot.region,
            farm_or_cooperative: `Sample ${lot.region} Coffee Group`,
            producer: `CoffeeProd Origin Partner ${String(lot.lotNo).padStart(2, "0")}`,
            altitude_min: lot.altitudeMin,
            altitude_max: lot.altitudeMax,
            roast_level: roast,
            decaf: false,
            decaf_method: "",
            acidity, body, sweetness, aroma,
            summary: `${lot.region} 산지의 ${lot.process} 커피를 ${roastName[roast]} 로스트로 구성한 합성 싱글 오리진 프로필`
        };
        profiles.push(profile);
        addRelations(
            profileKey,
            lot.flavors,
            roast === "DARK"
                ? [["ESPRESSO", "짧은 비율로 추출해 질감과 단맛을 표현함"], ["MOKA_POT", "중약불로 추출해 과한 쓴맛을 줄임"]]
                : [["POUR_OVER", "중간 분쇄도와 90~94C 물로 향미를 선명하게 표현함"], ["AEROPRESS", "짧은 침지로 단맛과 산미의 균형을 맞춤"]],
            lot.varieties
        );
        addProducts(profile, lot.basePrice + (roast === "DARK" ? 500 : roast === "LIGHT" ? 1000 : 0));
    });
});

blendRecipes.forEach((recipe) => {
    const roasts = recipe.name.includes("Floral") || recipe.name.includes("Citrus") || recipe.name.includes("Tropical")
        ? ["LIGHT", "MEDIUM"]
        : ["MEDIUM", "DARK"];

    roasts.forEach((roast) => {
        const profileKey = `BL${String(recipe.recipeNo).padStart(3, "0")}_${roastSuffix[roast]}`;
        const [acidity, body, sweetness, aroma] = scoresForRoast(recipe.scores, roast);
        const profile = {
            profile_key: profileKey,
            profile_name: `${recipe.name} ${roastName[roast]}`,
            bean_type: "BLEND",
            processing_method_code: recipe.decaf ? recipe.decafMethod : "",
            origin_country_code: "",
            origin_region: "",
            farm_or_cooperative: "",
            producer: "",
            altitude_min: "",
            altitude_max: "",
            roast_level: roast,
            decaf: recipe.decaf,
            decaf_method: recipe.decaf ? recipe.decafMethod : "",
            acidity, body, sweetness, aroma,
            summary: `${recipe.components.length}개 산지의 균형을 ${roastName[roast]} 로스트로 설계한 합성 블렌드 프로필`
        };
        profiles.push(profile);

        recipe.components.forEach(([country, region, process, ratio], index) => components.push({
            profile_key: profileKey,
            display_order: index + 1,
            origin_country_code: country,
            origin_region: region,
            processing_method_code: process,
            component_ratio: ratio.toFixed(2)
        }));

        addRelations(
            profileKey,
            recipe.flavors,
            roast === "DARK"
                ? [["ESPRESSO", "도징과 수율을 고정해 블렌드의 질감을 안정적으로 표현함"], ["COLD_BREW", "저온 침지로 묵직한 단맛을 표현함"]]
                : [["POUR_OVER", "충분한 블루밍으로 산지별 향을 고르게 표현함"], ["ESPRESSO", "중간 수율로 단맛과 산미의 균형을 맞춤"]],
            recipe.varieties
        );
        addProducts(profile, recipe.basePrice + (roast === "LIGHT" ? 1000 : roast === "DARK" ? 500 : 0));
    });
});

const specs = {
    "categories.csv": [categories, ["category_code", "name"]],
    "processing_methods.csv": [processingMethods, ["code", "name", "description"]],
    "flavor_notes.csv": [flavorNotes, ["code", "name", "description"]],
    "brew_methods.csv": [brewMethods, ["code", "name", "description"]],
    "coffee_varieties.csv": [coffeeVarieties, ["code", "name", "description"]],
    "coffee_profiles.csv": [profiles, ["profile_key", "profile_name", "bean_type", "processing_method_code", "origin_country_code", "origin_region", "farm_or_cooperative", "producer", "altitude_min", "altitude_max", "roast_level", "decaf", "decaf_method", "acidity", "body", "sweetness", "aroma", "summary"]],
    "coffee_profile_components.csv": [components, ["profile_key", "display_order", "origin_country_code", "origin_region", "processing_method_code", "component_ratio"]],
    "profile_flavor_notes.csv": [profileFlavorNotes, ["profile_key", "flavor_note_code", "display_order", "intensity"]],
    "profile_brew_methods.csv": [profileBrewMethods, ["profile_key", "brew_method_code", "display_order", "recommendation_note"]],
    "profile_varieties.csv": [profileVarieties, ["profile_key", "variety_code", "display_order"]],
    "products.csv": [products, ["sku", "profile_key", "category_code", "weight_grams", "name", "price", "stock_quantity", "roast_level", "description", "image_url", "status", "display_order"]]
};

function csvEscape(value) {
    const text = value === null || value === undefined ? "" : String(value);
    return /[",\r\n]/.test(text) ? `"${text.replaceAll('"', '""')}"` : text;
}

function serialize(rows, columns) {
    return [
        columns.join(","),
        ...rows.map((row) => columns.map((column) => csvEscape(row[column])).join(","))
    ].join("\n") + "\n";
}

function parseCsv(content) {
    const records = [];
    let record = [];
    let field = "";
    let quoted = false;

    for (let index = 0; index < content.length; index++) {
        const char = content[index];
        if (quoted && char === '"' && content[index + 1] === '"') {
            field += '"';
            index++;
        } else if (char === '"') {
            quoted = !quoted;
        } else if (char === "," && !quoted) {
            record.push(field);
            field = "";
        } else if ((char === "\n" || char === "\r") && !quoted) {
            if (char === "\r" && content[index + 1] === "\n") index++;
            record.push(field);
            if (record.some((value) => value !== "")) records.push(record);
            record = [];
            field = "";
        } else {
            field += char;
        }
    }

    const [header, ...rows] = records;
    return rows.map((values) => Object.fromEntries(
        header.map((column, index) => [column, values[index] ?? ""])
    ));
}

function assertCondition(condition, message) {
    if (!condition) throw new Error(message);
}

function assertUnique(rows, field, label) {
    const values = new Set();
    rows.forEach((row) => {
        assertCondition(row[field] !== "", `${label}의 ${field} 값이 비어 있음`);
        assertCondition(!values.has(row[field]), `${label}의 ${field} 중복: ${row[field]}`);
        values.add(row[field]);
    });
    return values;
}

function groupBy(rows, field) {
    const grouped = new Map();
    rows.forEach((row) => {
        if (!grouped.has(row[field])) grouped.set(row[field], []);
        grouped.get(row[field]).push(row);
    });
    return grouped;
}

function validateDisplayOrder(rows, profileKeys, label, minimum, maximum) {
    const grouped = groupBy(rows, "profile_key");
    profileKeys.forEach((profileKey) => {
        const related = grouped.get(profileKey) ?? [];
        assertCondition(related.length >= minimum && related.length <= maximum,
            `${label} 개수 오류: ${profileKey}`);
        const orders = related.map((row) => Number(row.display_order)).sort((a, b) => a - b);
        assertCondition(orders.every((value, index) => value === index + 1),
            `${label} display_order 오류: ${profileKey}`);
    });
}

function validateCatalog(data) {
    const categoryCodes = assertUnique(data.categories, "category_code", "카테고리");
    const processCodes = assertUnique(data.processingMethods, "code", "가공 방식");
    const flavorCodes = assertUnique(data.flavorNotes, "code", "향미 노트");
    const brewCodes = assertUnique(data.brewMethods, "code", "추출법");
    const varietyCodes = assertUnique(data.coffeeVarieties, "code", "품종");
    const profileKeys = assertUnique(data.profiles, "profile_key", "프로필");
    assertUnique(data.profiles, "profile_name", "프로필");
    assertUnique(data.products, "sku", "상품");

    assertCondition(data.profiles.length === 96, `프로필은 96개여야 함: ${data.profiles.length}`);
    assertCondition(data.products.length === 192, `상품은 192개여야 함: ${data.products.length}`);

    const componentGroups = groupBy(data.components, "profile_key");
    data.profiles.forEach((profile) => {
        assertCondition(["SINGLE_ORIGIN", "BLEND"].includes(profile.bean_type), `bean_type 오류: ${profile.profile_key}`);
        assertCondition(["LIGHT", "MEDIUM", "DARK"].includes(profile.roast_level), `roast_level 오류: ${profile.profile_key}`);
        if (profile.processing_method_code !== "") {
            assertCondition(processCodes.has(profile.processing_method_code), `가공 방식 참조 오류: ${profile.profile_key}`);
        }

        const profileComponents = componentGroups.get(profile.profile_key) ?? [];
        if (profile.bean_type === "SINGLE_ORIGIN") {
            assertCondition(/^[A-Z]{2}$/.test(profile.origin_country_code), `싱글 원산지 오류: ${profile.profile_key}`);
            assertCondition(profileComponents.length === 0, `싱글 구성요소 존재: ${profile.profile_key}`);
        } else {
            assertCondition(profile.origin_country_code === "" && profile.origin_region === "", `블렌드 원산지 필드 오류: ${profile.profile_key}`);
            assertCondition(profileComponents.length >= 2 && profileComponents.length <= 5, `블렌드 구성 개수 오류: ${profile.profile_key}`);
            const total = profileComponents.reduce((sum, component) => sum + Number(component.component_ratio), 0);
            assertCondition(Math.abs(total - 100) < 0.001, `블렌드 비율 합계 오류: ${profile.profile_key}=${total}`);
        }

        [profile.acidity, profile.body, profile.sweetness, profile.aroma].forEach((score) => {
            assertCondition(Number(score) >= 1 && Number(score) <= 5, `감각 점수 오류: ${profile.profile_key}`);
        });
    });

    data.components.forEach((component) => {
        assertCondition(profileKeys.has(component.profile_key), `구성요소 프로필 참조 오류: ${component.profile_key}`);
        assertCondition(/^[A-Z]{2}$/.test(component.origin_country_code), `구성요소 국가 코드 오류: ${component.profile_key}`);
        assertCondition(processCodes.has(component.processing_method_code), `구성요소 가공 방식 참조 오류: ${component.profile_key}`);
        assertCondition(Number(component.component_ratio) > 0 && Number(component.component_ratio) <= 100, `구성요소 비율 오류: ${component.profile_key}`);
    });

    data.profileFlavorNotes.forEach((row) => {
        assertCondition(profileKeys.has(row.profile_key) && flavorCodes.has(row.flavor_note_code), `향미 참조 오류: ${row.profile_key}`);
        assertCondition(Number(row.intensity) >= 1 && Number(row.intensity) <= 5, `향미 강도 오류: ${row.profile_key}`);
    });
    data.profileBrewMethods.forEach((row) => {
        assertCondition(profileKeys.has(row.profile_key) && brewCodes.has(row.brew_method_code), `추출법 참조 오류: ${row.profile_key}`);
    });
    data.profileVarieties.forEach((row) => {
        assertCondition(profileKeys.has(row.profile_key) && varietyCodes.has(row.variety_code), `품종 참조 오류: ${row.profile_key}`);
    });

    validateDisplayOrder(data.profileFlavorNotes, profileKeys, "향미", 3, 5);
    validateDisplayOrder(data.profileBrewMethods, profileKeys, "추출법", 2, 3);
    validateDisplayOrder(data.profileVarieties, profileKeys, "품종", 1, 3);
    const blendKeys = new Set(data.profiles.filter((profile) => profile.bean_type === "BLEND").map((profile) => profile.profile_key));
    validateDisplayOrder(data.components, blendKeys, "블렌드 구성", 2, 5);

    const productGroups = groupBy(data.products, "profile_key");
    profileKeys.forEach((profileKey) => assertCondition((productGroups.get(profileKey) ?? []).length === 2, `프로필별 SKU 개수 오류: ${profileKey}`));
    data.products.forEach((product) => {
        assertCondition(profileKeys.has(product.profile_key), `상품 프로필 참조 오류: ${product.sku}`);
        assertCondition(categoryCodes.has(product.category_code), `상품 카테고리 참조 오류: ${product.sku}`);
        assertCondition([200, 500].includes(Number(product.weight_grams)), `상품 중량 오류: ${product.sku}`);
        assertCondition(Number(product.price) > 0 && Number(product.stock_quantity) >= 0, `상품 가격/재고 오류: ${product.sku}`);
        assertCondition(["ON_SALE", "SOLD_OUT", "HIDDEN"].includes(product.status), `상품 상태 오류: ${product.sku}`);
        if (product.status === "SOLD_OUT") assertCondition(Number(product.stock_quantity) === 0, `품절 재고 오류: ${product.sku}`);
    });
}

function currentData() {
    return {
        categories,
        processingMethods,
        flavorNotes,
        brewMethods,
        coffeeVarieties,
        profiles,
        components,
        profileFlavorNotes,
        profileBrewMethods,
        profileVarieties,
        products
    };
}

function loadedData() {
    return {
        categories: parseCsv(readFileSync(join(csvDir, "categories.csv"), "utf8")),
        processingMethods: parseCsv(readFileSync(join(csvDir, "processing_methods.csv"), "utf8")),
        flavorNotes: parseCsv(readFileSync(join(csvDir, "flavor_notes.csv"), "utf8")),
        brewMethods: parseCsv(readFileSync(join(csvDir, "brew_methods.csv"), "utf8")),
        coffeeVarieties: parseCsv(readFileSync(join(csvDir, "coffee_varieties.csv"), "utf8")),
        profiles: parseCsv(readFileSync(join(csvDir, "coffee_profiles.csv"), "utf8")),
        components: parseCsv(readFileSync(join(csvDir, "coffee_profile_components.csv"), "utf8")),
        profileFlavorNotes: parseCsv(readFileSync(join(csvDir, "profile_flavor_notes.csv"), "utf8")),
        profileBrewMethods: parseCsv(readFileSync(join(csvDir, "profile_brew_methods.csv"), "utf8")),
        profileVarieties: parseCsv(readFileSync(join(csvDir, "profile_varieties.csv"), "utf8")),
        products: parseCsv(readFileSync(join(csvDir, "products.csv"), "utf8"))
    };
}

if (process.argv.includes("--check")) {
    const data = loadedData();
    validateCatalog(data);
    console.log(`CSV 검증 완료: 프로필 ${data.profiles.length}개, 상품 ${data.products.length}개`);
} else {
    const data = currentData();
    validateCatalog(data);
    mkdirSync(csvDir, { recursive: true });
    Object.entries(specs).forEach(([fileName, [rows, columns]]) => {
        writeFileSync(join(csvDir, fileName), serialize(rows, columns), "utf8");
    });
    console.log(`CSV 생성 완료: 프로필 ${profiles.length}개, 상품 ${products.length}개`);
}
