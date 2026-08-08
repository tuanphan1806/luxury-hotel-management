package com.hotel.backend.service;

import java.time.LocalTime;

/**
 * Keeps shift colours predictable across the calendar. Colours are derived
 * from the operational period, never from arbitrary client input.
 */
public final class WorkShiftColorPolicy {

    public static final String MORNING = "#B8944F";
    public static final String AFTERNOON = "#2F7D78";
    public static final String NIGHT = "#4E5D8C";

    private static final LocalTime MORNING_START = LocalTime.of(5, 0);
    private static final LocalTime AFTERNOON_START = LocalTime.of(13, 0);
    private static final LocalTime NIGHT_START = LocalTime.of(18, 0);

    private WorkShiftColorPolicy() {
    }

    public static String forStartTime(LocalTime startTime) {
        if (startTime == null) return NIGHT;
        if (!startTime.isBefore(MORNING_START) && startTime.isBefore(AFTERNOON_START)) {
            return MORNING;
        }
        if (!startTime.isBefore(AFTERNOON_START) && startTime.isBefore(NIGHT_START)) {
            return AFTERNOON;
        }
        return NIGHT;
    }

    public static int sortOrderForStartTime(LocalTime startTime) {
        if (startTime == null) return 30;
        if (!startTime.isBefore(MORNING_START) && startTime.isBefore(AFTERNOON_START)) {
            return 10;
        }
        if (!startTime.isBefore(AFTERNOON_START) && startTime.isBefore(NIGHT_START)) {
            return 20;
        }
        return 30;
    }
}
