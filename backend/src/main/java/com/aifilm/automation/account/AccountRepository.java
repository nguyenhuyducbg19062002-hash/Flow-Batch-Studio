package com.aifilm.automation.account;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface AccountRepository extends JpaRepository<AccountEntity, Long> {

    Optional<AccountEntity> findByEmail(String email);

    boolean existsByProvider(com.aifilm.automation.project.Provider provider);

    List<AccountEntity> findByStatus(AccountStatus status);

    List<AccountEntity> findAllByOrderByPriorityAscIdAsc();

    /** Tài khoản rảnh: READY, hoặc COOLDOWN đã hết hạn. Ưu tiên priority, rồi tài khoản lâu chưa dùng nhất. */
    @Query(value = """
            SELECT * FROM accounts
            WHERE provider = :provider
              AND (status = 'READY' OR (status = 'COOLDOWN' AND cooldown_until <= :now))
            ORDER BY priority ASC, (last_used_at IS NULL) DESC, last_used_at ASC
            LIMIT 1""", nativeQuery = true)
    Optional<AccountEntity> findNextAvailable(@Param("provider") String provider, @Param("now") LocalDateTime now);
}
