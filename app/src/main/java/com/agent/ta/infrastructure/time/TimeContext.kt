package com.agent.ta.infrastructure.time

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * 统一时间上下文（L0 基础设施层）
 *
 * 职责：
 * 1. 统一时区为 Asia/Shanghai（项目硬约束）
 * 2. 提供时间格式化工具，避免散落的时区处理代码
 * 3. 支持时段比较、跨天判断等常用操作
 *
 * 设计动机：
 * - 原有代码中 ZoneId.of("Asia/Shanghai") 散落在 PromptBuilder、ActivityAnchorManager、
 *   StateMachine 等多处，违反 DRY 原则
 * - 统一通过 TimeContext 注入，便于测试和未来扩展（如支持多时区）
 */
class TimeContext {

    val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    /** 当前时间（北京时间，截断到分钟） */
    fun now(): LocalDateTime {
        return LocalDateTime.now(zone).truncatedTo(ChronoUnit.MINUTES)
    }

    /** 当前时间戳（epochMilli） */
    fun nowMillis(): Long {
        return ZonedDateTime.now(zone).toInstant().toEpochMilli()
    }

    /** 格式化日期：yyyy年MM月dd日 */
    fun formatDate(time: Long): String {
        val zoned = ZonedDateTime.ofInstant(
            java.time.Instant.ofEpochMilli(time),
            zone
        )
        return zoned.format(DateTimeFormatter.ofPattern("yyyy年MM月dd日"))
    }

    /** 格式化时间：HH:mm */
    fun formatTime(time: Long): String {
        val zoned = ZonedDateTime.ofInstant(
            java.time.Instant.ofEpochMilli(time),
            zone
        )
        return zoned.format(DateTimeFormatter.ofPattern("HH:mm"))
    }

    /** 格式化日期时间：yyyy年MM月dd日 HH:mm */
    fun formatDateTime(time: Long): String {
        val zoned = ZonedDateTime.ofInstant(
            java.time.Instant.ofEpochMilli(time),
            zone
        )
        return zoned.format(DateTimeFormatter.ofPattern("yyyy年MM月dd日 HH:mm"))
    }

    /** 格式化为 ISO 日期：yyyy-MM-dd */
    fun formatIsoDate(time: Long): String {
        val zoned = ZonedDateTime.ofInstant(
            java.time.Instant.ofEpochMilli(time),
            zone
        )
        return zoned.format(DateTimeFormatter.ISO_LOCAL_DATE)
    }

    /** 判断两个时间戳是否同一天（北京时区） */
    fun isSameDay(t1: Long, t2: Long): Boolean {
        return formatIsoDate(t1) == formatIsoDate(t2)
    }

    /** 距离目标时间还有多少分钟（负数表示已过） */
    fun minutesUntil(target: LocalDateTime): Long {
        return ChronoUnit.MINUTES.between(now(), target)
    }

    /** 获取今日日期字符串（yyyy-MM-dd） */
    fun todayDateString(): String {
        return now().format(DateTimeFormatter.ISO_LOCAL_DATE)
    }

    companion object {
        @Volatile
        private var instance: TimeContext? = null

        fun getInstance(): TimeContext {
            return instance ?: synchronized(this) {
                instance ?: TimeContext().also { instance = it }
            }
        }
    }
}
