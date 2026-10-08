package com.city.service.time;

import com.city.model.TimeConstraint;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Java 规则时间解析器，仅作为 LLM temporal 失败后的兜底。 */
@Service
public class TimeExpressionParser {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Pattern MONTH_DAY_RANGE = Pattern.compile("(?:(\\d{4})年)?(\\d{1,2})月(\\d{1,2})[日号]?(?:到|至|[-～])(?:(\\d{1,2})月)?(\\d{1,2})[日号]?");
    private static final Pattern MONTH_DAY = Pattern.compile("(?:(\\d{4})年)?(\\d{1,2})月(\\d{1,2})[日号]?");
    private static final Pattern CLOCK_RANGE = Pattern.compile("(\\d{1,2})[:：点时](\\d{0,2})\\s*(?:到|至|[-～])\\s*(\\d{1,2})[:：点时](\\d{0,2})");
    private static final Pattern CLOCK_POINT = Pattern.compile("(\\d{1,2})[:：点时](\\d{0,2})");

    public TimeConstraint parse(String input) {
        String text = input == null ? "" : input.trim();
        if (text.isBlank()) return TimeConstraint.empty();
        LocalDate today = LocalDate.now(ZONE);
        LocalDate start = null;
        LocalDate end = null;
        Matcher rangeMatcher = MONTH_DAY_RANGE.matcher(text);
        Matcher dateMatcher = MONTH_DAY.matcher(text);
        if (rangeMatcher.find()) {
            int year = valueOrCurrentYear(rangeMatcher.group(1), today);
            int month = Integer.parseInt(rangeMatcher.group(2));
            int day = Integer.parseInt(rangeMatcher.group(3));
            start = LocalDate.of(year, month, day);
            int endMonth = rangeMatcher.group(4) == null ? month : Integer.parseInt(rangeMatcher.group(4));
            end = LocalDate.of(year, endMonth, Integer.parseInt(rangeMatcher.group(5)));
            if (end.isBefore(start)) end = end.plusYears(1);
        } else if (dateMatcher.find()) {
            start = end = LocalDate.of(valueOrCurrentYear(dateMatcher.group(1), today),
                    Integer.parseInt(dateMatcher.group(2)), Integer.parseInt(dateMatcher.group(3)));
            if (start.isBefore(today) && dateMatcher.group(1) == null) start = start.plusYears(1);
        } else if (text.contains("今天") || text.contains("今早") || text.contains("今晨")
                || text.contains("今下午") || text.contains("今晚")) { start = end = today; }
        else if (text.contains("明天")) { start = end = today.plusDays(1); }
        else if (text.contains("后天")) { start = end = today.plusDays(2); }
        else if (text.contains("下周末")) {
            start = today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SATURDAY)).plusWeeks(1);
            end = start.plusDays(1);
        } else if (text.contains("下周") && weekday(text) == null) {
            start = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).plusWeeks(1);
            end = start.plusDays(6);
        } else if ((text.contains("这周") || text.contains("本周")) && weekday(text) == null) {
            start = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            end = start.plusDays(6);
        } else if (text.contains("周末")) {
            start = today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SATURDAY));
            end = start.plusDays(1);
        } else {
            DayOfWeek weekday = weekday(text);
            if (weekday != null) {
                start = today.with(TemporalAdjusters.nextOrSame(weekday));
                if (text.contains("下周")) start = start.plusWeeks(1);
                end = start;
            }
        }
        LocalTime startTime = null;
        LocalTime endTime = null;
        Matcher clockRange = CLOCK_RANGE.matcher(text);
        if (clockRange.find()) {
            startTime = clockTime(clockRange.group(1), clockRange.group(2));
            endTime = clockTime(clockRange.group(3), clockRange.group(4));
        } else if (containsRangeExpression(text, "上午", "晚上")
                || containsRangeExpression(text, "早上", "晚上")
                || containsRangeExpression(text, "早晨", "晚上")) {
            startTime = LocalTime.of(8, 0); endTime = LocalTime.of(23, 0);
        } else if (containsRangeExpression(text, "上午", "下午")
                || containsRangeExpression(text, "早上", "下午")
                || containsRangeExpression(text, "早晨", "下午")) {
            startTime = LocalTime.of(8, 0); endTime = LocalTime.of(18, 0);
        } else if (containsRangeExpression(text, "下午", "晚上")
                || containsRangeExpression(text, "午后", "晚上")
                || containsRangeExpression(text, "中午", "晚上")) {
            startTime = LocalTime.of(12, 0); endTime = LocalTime.of(23, 0);
        } else if (containsRangeExpression(text, "傍晚", "晚上")
                || containsRangeExpression(text, "黄昏", "晚上")) {
            startTime = LocalTime.of(17, 0); endTime = LocalTime.of(23, 0);
        } else if (text.contains("上午") || text.contains("早上") || text.contains("早晨") || text.contains("今早")) {
            startTime = LocalTime.of(8, 0); endTime = LocalTime.of(12, 0);
        } else if (text.contains("下午") || text.contains("中午") || text.contains("午后")) {
            startTime = LocalTime.of(12, 0); endTime = LocalTime.of(18, 0);
        } else if (text.contains("傍晚") || text.contains("黄昏")) {
            startTime = LocalTime.of(17, 0); endTime = LocalTime.of(19, 0);
        } else if (text.contains("晚上") || text.contains("夜晚") || text.contains("今晚")) {
            startTime = LocalTime.of(18, 0); endTime = LocalTime.of(23, 0);
        } else if (text.contains("夜里")) {
            startTime = LocalTime.of(20, 0); endTime = LocalTime.of(23, 59);
        } else if (text.contains("凌晨")) {
            startTime = LocalTime.MIDNIGHT; endTime = LocalTime.of(5, 0);
        } else {
            Matcher clockPoint = CLOCK_POINT.matcher(text);
            if (clockPoint.find()) {
                startTime = clockTime(clockPoint.group(1), clockPoint.group(2));
                endTime = startTime == null ? null : startTime.plusHours(1);
            }
        }
        if (start == null && (startTime == null || endTime == null)) return TimeConstraint.empty();
        return new TimeConstraint(text, start, end, startTime, endTime, LocalDateTime.now(ZONE));
    }

    /** 用户明确表示完全不限制时间。 */
    public boolean clearRequested(String input) {
        String text = input == null ? "" : input.replaceAll("\\s+", "");
        return text.contains("不限时间") || text.contains("不限制时间") || text.contains("随时都行")
                || text.contains("什么时候都行") || text.contains("任何时候都可以")
                || text.contains("时间无所谓") || text.contains("不挑时间") || text.equals("随时");
    }

    /**
     * 判断用户是否明显在表达时间。用于 LLM 和规则解析均失败后的澄清门控，
     * 避免普通聊天因为没有 temporal 而被误判为时间解析失败。
     */
    public boolean mentionsTime(String input) {
        String text = input == null ? "" : input.replaceAll("\\s+", "");
        if (text.isBlank()) return false;
        if (MONTH_DAY_RANGE.matcher(text).find() || MONTH_DAY.matcher(text).find()
                || CLOCK_RANGE.matcher(text).find() || CLOCK_POINT.matcher(text).find()) {
            return true;
        }
        return containsAny(text,
                "今天", "明天", "后天", "本周", "这周", "下周", "周末", "星期", "周一", "周二", "周三", "周四", "周五", "周六", "周日", "周天",
                "下个月", "这个月", "月底", "月末", "日期", "哪天",
                "上午", "早上", "早晨", "中午", "下午", "午后", "傍晚", "黄昏", "晚上", "夜晚", "今晚", "夜里", "凌晨",
                "几点", "时间", "时段", "点半", "点左右", "左右", "随时");
    }

    private DayOfWeek weekday(String text) {
        if (text.contains("周一") || text.contains("星期一")) return DayOfWeek.MONDAY;
        if (text.contains("周二") || text.contains("星期二")) return DayOfWeek.TUESDAY;
        if (text.contains("周三") || text.contains("星期三")) return DayOfWeek.WEDNESDAY;
        if (text.contains("周四") || text.contains("星期四")) return DayOfWeek.THURSDAY;
        if (text.contains("周五") || text.contains("星期五")) return DayOfWeek.FRIDAY;
        if (text.contains("周六") || text.contains("星期六")) return DayOfWeek.SATURDAY;
        if (text.contains("周日") || text.contains("周天") || text.contains("星期日") || text.contains("星期天")) return DayOfWeek.SUNDAY;
        return null;
    }

    private int valueOrCurrentYear(String rawYear, LocalDate today) {
        return rawYear == null ? today.getYear() : Integer.parseInt(rawYear);
    }

    private LocalTime clockTime(String hour, String minute) {
        int h = Integer.parseInt(hour);
        int m = minute == null || minute.isBlank() ? 0 : Integer.parseInt(minute);
        if (h < 0 || h > 23 || m < 0 || m > 59) return null;
        return LocalTime.of(h, m);
    }

    private boolean containsRangeExpression(String text, String from, String to) {
        if (text == null || from == null || to == null) return false;
        int fromIndex = text.indexOf(from);
        int toIndex = text.indexOf(to);
        return fromIndex >= 0 && toIndex > fromIndex;
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) return true;
        }
        return false;
    }
}
