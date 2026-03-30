package com.chatflow.consumer.repository;

import com.chatflow.consumer.model.MessageEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * JPA Repository for writing messages to PostgreSQL.
 * Used exclusively by DatabaseWriterService.
 *
 * Write strategy:
 *   Normal path:    saveAll(batch) → JDBC batch INSERT
 *                   requires hibernate.jdbc.batch_size=1000 in application.yml
 *
 *   Duplicate path: upsert() → INSERT ... ON CONFLICT (message_id) DO NOTHING
 *                   returns 1 if row was inserted, 0 if duplicate was skipped
 *                   called only when saveAll() throws DataIntegrityViolationException
 */
@Repository
public interface MessageRepository extends JpaRepository<MessageEntity, Long> {

    /**
     * Upsert fallback for handling duplicate message_id.
     *
     * Returns:
     *   1 → row was actually inserted (new message)
     *   0 → row was skipped (duplicate message_id, ON CONFLICT DO NOTHING)
     *
     * This return value allows DatabaseWriterService to accurately track
     * totalWritten vs totalDupes without guessing.
     *
     * Design note:
     *   Duplicates are expected to be rare (only on consumer restart or
     *   nack-requeue scenarios). This path prioritizes correctness over
     *   throughput, which is acceptable given the low expected duplicate rate.
     */
    @Modifying
    @Transactional
    @Query(value = """
            INSERT INTO messages (
                message_id, room_id, user_id, username, message,
                event_time, message_type, server_id, client_ip,
                published_at, received_at
            ) VALUES (
                :#{#e.messageId}, :#{#e.roomId}, :#{#e.userId},
                :#{#e.username}, :#{#e.message}, :#{#e.eventTime},
                :#{#e.messageType}, :#{#e.serverId}, :#{#e.clientIp},
                :#{#e.publishedAt}, :#{#e.receivedAt}
            )
            ON CONFLICT (message_id) DO NOTHING
            """,
            nativeQuery = true)
    int upsert(@Param("e") MessageEntity entity);
}