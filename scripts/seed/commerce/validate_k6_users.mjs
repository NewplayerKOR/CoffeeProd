import { readFileSync } from "node:fs";

const [filePath, expectedCountText] = process.argv.slice(2);

if (!filePath || !expectedCountText) {
    throw new Error("사용법: node validate_k6_users.mjs <users.json> <expected-count>");
}

const expectedCount = Number(expectedCountText);
const users = JSON.parse(readFileSync(filePath, "utf8"));

if (!Number.isInteger(expectedCount) || expectedCount < 1) {
    throw new Error("expected-count는 1 이상의 정수여야 함");
}

if (!Array.isArray(users) || users.length !== expectedCount) {
    throw new Error(`k6 계정 수 불일치: expected=${expectedCount}, actual=${users.length}`);
}

const emails = new Set();
const addressIds = new Set();

users.forEach((user, index) => {
    if (!/^loadtest-user-\d{6}@coffeeprod[.]local$/.test(user.email)) {
        throw new Error(`이메일 형식 오류: index=${index}`);
    }

    if (user.password !== "password") {
        throw new Error(`공통 테스트 비밀번호 오류: email=${user.email}`);
    }

    if (!Number.isInteger(user.addressId) || user.addressId < 1) {
        throw new Error(`배송지 ID 오류: email=${user.email}`);
    }

    if (emails.has(user.email)) {
        throw new Error(`이메일 중복: ${user.email}`);
    }

    if (addressIds.has(user.addressId)) {
        throw new Error(`배송지 ID 중복: ${user.addressId}`);
    }

    emails.add(user.email);
    addressIds.add(user.addressId);
});

console.log(`k6 계정 검증 완료: ${users.length}개`);
