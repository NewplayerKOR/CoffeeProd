package com.back.coffeeprod.domain.payment.repository;

import com.back.coffeeprod.domain.payment.entity.PaymentOperation;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.*;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.*;

public interface PaymentOperationRepository extends JpaRepository<PaymentOperation, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PaymentOperation p where p.orders.id = :id and p.type = :type")
    Optional<PaymentOperation> findLocked(@Param("id") Long id, @Param("type") PaymentOperation.Type type);
    @Query("select p.orders.id from PaymentOperation p where p.id = :id")
    Optional<Long> findOrderId(@Param("id") Long id);
    @Query("""
            select p.id from PaymentOperation p
            where ((p.status in ('READY','UNKNOWN') and (p.nextAttemptAt is null or p.nextAttemptAt <= :now))
                or (p.status = 'IN_FLIGHT' and p.leaseUntil <= :now))
              and (p.type = 'CONFIRM' or not exists
                (select c.id from PaymentOperation c where c.orders = p.orders and c.type = 'CONFIRM'
                 and c.status not in ('SUCCEEDED','REJECTED')))
            order by p.updatedAt, p.id
            """)
    List<Long> findDueIds(@Param("now") Instant now, Pageable pageable);
    @Query("select p from PaymentOperation p where (:orderId is null or p.orders.id = :orderId) and (:status is null or p.status = :status)")
    Page<PaymentOperation> search(@Param("orderId") Long orderId, @Param("status") PaymentOperation.Status status, Pageable pageable);
}
