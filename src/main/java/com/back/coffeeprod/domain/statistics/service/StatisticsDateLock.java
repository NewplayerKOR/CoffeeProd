package com.back.coffeeprod.domain.statistics.service;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.time.LocalDate;

@Component @RequiredArgsConstructor
public class StatisticsDateLock {
    private final EntityManager em;
    public void acquire(LocalDate date) {
        String database = em.unwrap(org.hibernate.Session.class)
                .doReturningWork(c -> c.getMetaData().getDatabaseProductName());
        if ("PostgreSQL".equals(database)) {
            em.createNativeQuery("select pg_advisory_xact_lock(731904, cast(:day as integer))")
                    .setParameter("day", Math.toIntExact(date.toEpochDay())).getSingleResult();
        } else if (!"H2".equals(database)) {
            throw new IllegalStateException("Unsupported statistics locking database");
        }
        // H2 is only a functional fixture. Concurrency is verified against PostgreSQL.
    }
}
