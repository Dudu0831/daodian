package com.abc.daodian.reminder.data

import androidx.room.TypeConverter
import java.time.LocalDate

class Converters {
    @TypeConverter fun statusToString(v: ReminderStatus): String = v.name
    @TypeConverter fun stringToStatus(v: String): ReminderStatus = ReminderStatus.valueOf(v)

    @TypeConverter fun sourceToString(v: FireSource): String = v.name
    @TypeConverter fun stringToSource(v: String): FireSource = FireSource.valueOf(v)

    /** 日子存成 `2026-12-23` */
    @TypeConverter fun dayToString(v: LocalDate): String = v.toString()
    @TypeConverter fun stringToDay(v: String): LocalDate = LocalDate.parse(v)
}
