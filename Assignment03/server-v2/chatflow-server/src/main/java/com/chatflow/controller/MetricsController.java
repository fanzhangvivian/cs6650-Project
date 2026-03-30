package com.chatflow.controller;

import com.chatflow.repository.MessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Metrics API endpoint for Assignment 3.
 *
 * GET /metrics
 *
 * Returns results of all 8 required queries:
 *   Core Queries 1-4: room time range, user history,
 *                     active users count, rooms for user
 *   Analytics 1-4:    msg/sec, top users, top rooms,
 *                     user participation patterns
 *
 * All data comes exclusively from PostgreSQL.
 * No in-memory counters are used as data sources.
 *
 * Called by client after load test completes.
 * Response is logged by client for screenshot submission.
 */
@RestController
@RequestMapping("/metrics")
public class MetricsController {

    private static final Logger logger =
            LoggerFactory.getLogger(MetricsController.class);

    private final MessageRepository messageRepository;

    public MetricsController(MessageRepository messageRepository) {
        this.messageRepository = messageRepository;
    }

    /**
     * Returns all metrics as a single JSON response.
     *
     * @param roomId    room to query for Core Query 1 (default: "1")
     * @param userId    user to query for Core Query 2 and 4
     *                  (auto-selected from top active users if not provided)
     * @param startTime start of query window (default: 24 hours ago)
     * @param endTime   end of query window (default: now)
     */
    @GetMapping
    public Map<String, Object> getMetrics(
            @RequestParam(defaultValue = "1") String roomId,
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) Instant startTime,
            @RequestParam(required = false) Instant endTime) {

        // ── Time window ───────────────────────────────────────────────────────
        Instant start = startTime != null
                ? startTime
                : Instant.now().minus(24, ChronoUnit.HOURS);
        Instant end = endTime != null
                ? endTime
                : Instant.now();

        // ── Auto-select active userId ─────────────────────────────────────────
        // Avoids empty results if a hardcoded userId has no messages.
        // Picks the most active user from the database automatically.
        if (userId == null) {
            List<Object[]> topUsers = messageRepository.findTopActiveUsers(1);
            userId = topUsers.isEmpty()
                    ? "1"
                    : topUsers.get(0)[0].toString();
            logger.info("Auto-selected userId: {}", userId);
        }

        logger.info("Metrics API called | roomId={} | userId={} | window=[{}, {}]",
                roomId, userId, start, end);

        // ── Build response ────────────────────────────────────────────────────
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("generatedAt", Instant.now().toString());
        result.put("queryWindow", Map.of(
                "start", start.toString(),
                "end", end.toString()
        ));
        result.put("autoSelectedUserId", userId);
        result.put("totalMessages", messageRepository.countTotalMessages());

        // ── Core Queries ──────────────────────────────────────────────────────
        Map<String, Object> coreQueries = new LinkedHashMap<>();

        // Core Query 1: room messages in time range (target: < 100ms)
        long t1 = System.currentTimeMillis();
        List<?> roomMessages = messageRepository
                .findByRoomIdAndEventTimeBetweenOrderByEventTime(roomId, start, end);
        long ms1 = System.currentTimeMillis() - t1;
        coreQueries.put("roomMessagesInRange", Map.of(
                "roomId", roomId,
                "count", roomMessages.size(),
                "executionMs", ms1,
                "targetMs", 100,
                "targetMet", ms1 < 100
        ));
        logger.info("Core Q1 (room time range): {}ms, {} messages", ms1, roomMessages.size());

        // Core Query 2: user message history (target: < 200ms)
        long t2 = System.currentTimeMillis();
        List<?> userHistory = messageRepository
                .findByUserIdAndEventTimeBetweenOrderByEventTime(userId, start, end);
        long ms2 = System.currentTimeMillis() - t2;
        coreQueries.put("userMessageHistory", Map.of(
                "userId", userId,
                "count", userHistory.size(),
                "executionMs", ms2,
                "targetMs", 200,
                "targetMet", ms2 < 200
        ));
        logger.info("Core Q2 (user history): {}ms, {} messages", ms2, userHistory.size());

        // Core Query 3: active users in time window (target: < 500ms)
        long t3 = System.currentTimeMillis();
        Long activeUsers = messageRepository.countActiveUsersInWindow(start, end);
        long ms3 = System.currentTimeMillis() - t3;
        coreQueries.put("activeUsersInWindow", Map.of(
                "uniqueUsers", activeUsers != null ? activeUsers : 0,
                "executionMs", ms3,
                "targetMs", 500,
                "targetMet", ms3 < 500
        ));
        logger.info("Core Q3 (active users): {}ms, {} users", ms3, activeUsers);

        // Core Query 4: rooms user participated in (target: < 50ms)
        long t4 = System.currentTimeMillis();
        List<Object[]> roomsRaw = messageRepository.findRoomsForUser(userId);
        long ms4 = System.currentTimeMillis() - t4;
        List<Map<String, Object>> rooms = new ArrayList<>();
        for (Object[] row : roomsRaw) {
            rooms.add(Map.of(
                    "roomId", row[0].toString(),
                    "lastActivity", row[1].toString()
            ));
        }
        coreQueries.put("roomsForUser", Map.of(
                "userId", userId,
                "rooms", rooms,
                "executionMs", ms4,
                "targetMs", 50,
                "targetMet", ms4 < 50
        ));
        logger.info("Core Q4 (rooms for user): {}ms, {} rooms", ms4, rooms.size());

        result.put("coreQueries", coreQueries);

        // ── Analytics Queries ─────────────────────────────────────────────────
        Map<String, Object> analytics = new LinkedHashMap<>();

        // Analytics Query 1: messages per second
        List<Object[]> perSecRaw =
                messageRepository.findMessagesPerSecond(start, end);
        List<Map<String, Object>> perSec = new ArrayList<>();
        for (Object[] row : perSecRaw) {
            perSec.add(Map.of(
                    "second", row[0].toString(),
                    "count", row[1]
            ));
        }
        analytics.put("messagesPerSecond", perSec);

        // Analytics Query 2: top 10 active users
        List<Object[]> topUsersRaw =
                messageRepository.findTopActiveUsers(10);
        List<Map<String, Object>> topUsers = new ArrayList<>();
        for (Object[] row : topUsersRaw) {
            topUsers.add(Map.of(
                    "userId", row[0].toString(),
                    "messageCount", row[1]
            ));
        }
        analytics.put("topActiveUsers", topUsers);

        // Analytics Query 3: top 10 active rooms
        List<Object[]> topRoomsRaw =
                messageRepository.findTopActiveRooms(10);
        List<Map<String, Object>> topRooms = new ArrayList<>();
        for (Object[] row : topRoomsRaw) {
            topRooms.add(Map.of(
                    "roomId", row[0].toString(),
                    "messageCount", row[1]
            ));
        }
        analytics.put("topActiveRooms", topRooms);

        // Analytics Query 4: user participation patterns (top 100 rows)
        List<Object[]> patternsRaw =
                messageRepository.findUserParticipationPatterns(100);
        List<Map<String, Object>> patterns = new ArrayList<>();
        for (Object[] row : patternsRaw) {
            patterns.add(Map.of(
                    "userId", row[0].toString(),
                    "hour", row[1].toString(),
                    "messageCount", row[2]
            ));
        }
        analytics.put("userParticipationPatterns", patterns);

        result.put("analyticsQueries", analytics);

        logger.info("Metrics API response built successfully");
        return result;
    }
}