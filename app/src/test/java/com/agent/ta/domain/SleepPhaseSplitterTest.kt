package com.agent.ta.domain

import com.agent.ta.data.model.DailySlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

/**
 * SleepPhaseSplitter 单元测试
 *
 * 验证 90 分钟周期建模 + 情境扰动 + fallback 逻辑
 */
class SleepPhaseSplitterTest {

    private val splitter = SleepPhaseSplitter()

    @Test
    fun `标准睡眠 8_5 小时 5 周期 无扰动 拆为 3 段`() {
        // 23:00 - 07:30 = 8.5h = 510min = 5.67 周期（5 个完整周期）
        val start = LocalTime.of(23, 0)
        val end = LocalTime.of(7, 30)
        val perturbation = SleepPhaseSplitter.SleepContextPerturbation()

        val slots = splitter.split(start, end, perturbation)

        assertNotNull("标准睡眠应成功拆分", slots)
        slots!!.let {
            assertEquals("应拆为 3 段", 3, it.size)

            // 第 1 段：入睡浅睡（10% = 51min）
            assertEquals("入睡浅睡", it[0].activity)
            assertEquals("light", it[0].sleepDepth)
            assertEquals("23:00", it[0].start)
            assertEquals("23:51", it[0].end)  // 23:00 + 51min

            // 第 2 段：深睡（60% = 306min，跨午夜）
            assertEquals("深睡", it[1].activity)
            assertEquals("deep", it[1].sleepDepth)
            assertEquals("23:51", it[1].start)
            assertEquals("04:57", it[1].end)  // 23:51 + 306min = 04:57（跨午夜）

            // 第 3 段：将醒浅睡（30% = 153min）
            assertEquals("将醒浅睡", it[2].activity)
            assertEquals("light", it[2].sleepDepth)
            assertEquals("04:57", it[2].start)
            assertEquals("07:30", it[2].end)  // 04:57 + 153min = 07:30

            // 验证时间连续性：前一段 end = 后一段 start
            assertEquals(it[0].end, it[1].start)
            assertEquals(it[1].end, it[2].start)

            // 深睡段应跨午夜（start > end 按字符串比较，因为 23:51 > 05:00）
            assertTrue("深睡段应跨午夜", it[1].start > it[1].end)
        }
    }

    @Test
    fun `熬夜 5_5 小时 3 周期 压力大 深睡比例降低`() {
        // 02:00 - 07:30 = 5.5h = 330min = 3.67 周期（3 个完整周期）
        val start = LocalTime.of(2, 0)
        val end = LocalTime.of(7, 30)

        // 无扰动基准
        val baselineSlots = splitter.split(start, end, SleepPhaseSplitter.SleepContextPerturbation())
        // 高压力
        val stressedSlots = splitter.split(start, end, SleepPhaseSplitter.SleepContextPerturbation(stress = 0.9f))

        assertNotNull(baselineSlots)
        assertNotNull(stressedSlots)

        // 计算深睡时长占比
        fun deepRatio(slots: List<DailySlot>): Float {
            val totalMin = computeMinutes(slots.first().start, slots.last().end)
            val deepMin = slots.filter { it.sleepDepth == "deep" }.sumOf { computeMinutes(it.start, it.end) }
            return deepMin.toFloat() / totalMin
        }

        val baselineRatio = deepRatio(baselineSlots!!)
        val stressedRatio = deepRatio(stressedSlots!!)

        assertTrue("高压力下深睡比例应降低或相等（baseline=$baselineRatio, stressed=$stressedRatio）",
            stressedRatio <= baselineRatio)
    }

    @Test
    fun `短睡 6 小时 4 周期 拆为 3 段且深睡补偿`() {
        // 23:00 - 05:00 = 6h = 360min = 4 周期
        val start = LocalTime.of(23, 0)
        val end = LocalTime.of(5, 0)
        val perturbation = SleepPhaseSplitter.SleepContextPerturbation()

        val slots = splitter.split(start, end, perturbation)

        assertNotNull("短睡 6h 应成功拆分", slots)
        slots!!.let {
            assertEquals(3, it.size)

            // 第 1 段：入睡浅睡（10% = 36min）
            assertEquals("入睡浅睡", it[0].activity)
            assertEquals("light", it[0].sleepDepth)
            assertEquals("23:00", it[0].start)
            assertEquals("23:36", it[0].end)  // 23:00 + 36min

            // 第 2 段：深睡（70% = 252min，短睡眠深睡补偿）
            assertEquals("深睡", it[1].activity)
            assertEquals("deep", it[1].sleepDepth)
            assertEquals("23:36", it[1].start)
            assertEquals("03:48", it[1].end)  // 23:36 + 252min

            // 第 3 段：将醒浅睡（20% = 72min）
            assertEquals("将醒浅睡", it[2].activity)
            assertEquals("light", it[2].sleepDepth)
            assertEquals("03:48", it[2].start)
            assertEquals("05:00", it[2].end)  // 03:48 + 72min = 05:00

            // 验证时间连续性
            assertEquals(it[0].end, it[1].start)
            assertEquals(it[1].end, it[2].start)
        }
    }

    @Test
    fun `异常输入 总睡眠小于 3 小时 返回 null`() {
        // 23:00 - 23:30 = 30min < 3h
        val start = LocalTime.of(23, 0)
        val end = LocalTime.of(23, 30)

        val slots = splitter.split(start, end, SleepPhaseSplitter.SleepContextPerturbation())

        assertNull("总睡眠 < 3 小时应返回 null", slots)
    }

    /**
     * 计算两个时间点之间的分钟数（跨午夜）
     */
    private fun computeMinutes(start: String, end: String): Int {
        val s = start.split(":").let { it[0].toInt() * 60 + it[1].toInt() }
        val e = end.split(":").let { it[0].toInt() * 60 + it[1].toInt() }
        return if (e > s) e - s else (24 * 60 - s) + e
    }
}
