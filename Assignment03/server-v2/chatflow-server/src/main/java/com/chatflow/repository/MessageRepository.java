package com.chatflow.repository;

import com.chatflow.model.MessageEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/**
 * JPA Repository for querying the messages table.
 * Used exclusively by MetricsController for Metrics API queries.
 *
 * Query strategy:
 *   - Simple range queries: Spring Data method name derivation
 *   - Complex aggregations: @Query with nativeQuery = true
 *
 * All 8 queries (4 Core + 4 Analytics) read from PostgreSQL.
 * No in-memory data sources are used.
 */
@Repository
public interface MessageRepository extends JpaRepository<MessageEntity, Long> {

    // ── Core Query 1 ──────────────────────────────────────────────────────────
    // Get messages for a room in time range
    // Performance target: < 100ms for 1000 messages
    // Index used: idx_room_event_time (room_id, event_time)
    List<MessageEntity> findByRoomIdAndEventTimeBetweenOrderByEventTime(
            String roomId, Instant start, Instant end);

    // ── Core Query 2 ──────────────────────────────────────────────────────────
    // Get user message history with optional time range
    // Performance target: < 200ms
    // Index used: idx_user_event_time (user_id, event_time)
    List<MessageEntity> findByUserIdAndEventTimeBetweenOrderByEventTime(
            String userId, Instant start, Instant end);

    // ── Core Query 3 ──────────────────────────────────────────────────────────
    // Count active users in time window
    // Performance target: < 500ms
    // Index used: idx_event_time (event_time)
    @Query(value = """
            SELECT COUNT(DISTINCT user_id)
            FROM messages
            WHERE event_time BETWEEN :start AND :end
            """,
            nativeQuery = true)
    Long countActiveUsersInWindow(
            @Param("start") Instant start,
            @Param("end") Instant end);

    // ── Core Query 4 ──────────────────────────────────────────────────────────
    // Get rooms a user has participated in with last activity time
    // Performance target: < 50ms
    // Index used: idx_user_event_time (user_id, event_time)
    @Query(value = """
            SELECT room_id, MAX(event_time) AS last_activity
            FROM messages
            WHERE user_id = :userId
            GROUP BY room_id
            ORDER BY last_activity DESC
            """,
            nativeQuery = true)
    List<Object[]> findRoomsForUser(@Param("userId") String userId);

    // ── Analytics Query 1 ─────────────────────────────────────────────────────
    // Messages per second statistics in time window
    // Index used: idx_event_time (event_time)
    @Query(value = """
            SELECT DATE_TRUNC('second', event_time) AS second,
                   COUNT(*) AS count
            FROM messages
            WHERE event_time BETWEEN :start AND :end
            GROUP BY second
            ORDER BY second
            """,
            nativeQuery = true)
    List<Object[]> findMessagesPerSecond(
            @Param("start") Instant start,
            @Param("end") Instant end);

    // ── Analytics Query 2 ─────────────────────────────────────────────────────
    // Top N most active users by message count
    @Query(value = """
            SELECT user_id, COUNT(*) AS message_count
            FROM messages
            GROUP BY user_id
            ORDER BY message_count DESC
            LIMIT :limit
            """,
            nativeQuery = true)
    List<Object[]> findTopActiveUsers(@Param("limit") int limit);

    // ── Analytics Query 3 ─────────────────────────────────────────────────────
    // Top N most active rooms by message count
    @Query(value = """
            SELECT room_id, COUNT(*) AS message_count
            FROM messages
            GROUP BY room_id
            ORDER BY message_count DESC
            LIMIT :limit
            """,
            nativeQuery = true)
    List<Object[]> findTopActiveRooms(@Param("limit") int limit);

    // ── Analytics Query 4 ─────────────────────────────────────────────────────
    // User participation patterns by hour of day
    @Query(value = """
            SELECT user_id,
                   EXTRACT(HOUR FROM event_time) AS hour,
                   COUNT(*) AS message_count
            FROM messages
            GROUP BY user_id, hour
            ORDER BY user_id, hour
            LIMIT :limit
            """,
            nativeQuery = true)
    List<Object[]> findUserParticipationPatterns(@Param("limit") int limit);

    // ── Utility ───────────────────────────────────────────────────────────────
    // Total message count, used in Metrics API response header
    @Query(value = "SELECT COUNT(*) FROM messages", nativeQuery = true)
    Long countTotalMessages();
}