package com.back.coffeeprod.global.common.time;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;

public final class BusinessTime {

    public static final ZoneId SEOUL_ZONE = ZoneId.of("Asia/Seoul");

    private BusinessTime() {
    }

    // 한국 영업일 시작 시각을 UTC Instant로 변환함
    public static Instant startOfDay(LocalDate date) {
        return date.atStartOfDay(SEOUL_ZONE).toInstant();
    }

    // UTC Instant를 한국 시각으로 변환함

    public static OffsetDateTime toSeoul(Instant instant) {
        if (instant == null) {
            return null;
        }

        return instant.atZone(SEOUL_ZONE).toOffsetDateTime();
    }
}
