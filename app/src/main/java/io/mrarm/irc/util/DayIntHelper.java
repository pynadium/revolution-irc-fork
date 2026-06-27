package io.mrarm.irc.util;

import java.util.Calendar;
import java.util.Date;

public class DayIntHelper {

    private static final Calendar sCalendar = Calendar.getInstance();
    private static final int sDaysInYear = sCalendar.getMaximum(Calendar.DAY_OF_YEAR);

    public static int getDayInt(Date date) {
        sCalendar.setTime(date);
        return sCalendar.get(Calendar.YEAR) * (sDaysInYear + 1)
                + sCalendar.get(Calendar.DAY_OF_YEAR);
    }

    public static long getDateIntMs(int date) {
        sCalendar.setTimeInMillis(0);
        sCalendar.set(Calendar.YEAR, date / (sDaysInYear + 1));
        sCalendar.set(Calendar.DAY_OF_YEAR, date % (sDaysInYear + 1));
        return sCalendar.getTimeInMillis();
    }
}
