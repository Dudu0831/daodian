package com.abc.daodian.data

import androidx.room.TypeConverter

class Converters {
    @TypeConverter fun statusToString(v: ReminderStatus): String = v.name
    @TypeConverter fun stringToStatus(v: String): ReminderStatus = ReminderStatus.valueOf(v)

    @TypeConverter fun sourceToString(v: FireSource): String = v.name
    @TypeConverter fun stringToSource(v: String): FireSource = FireSource.valueOf(v)
}
