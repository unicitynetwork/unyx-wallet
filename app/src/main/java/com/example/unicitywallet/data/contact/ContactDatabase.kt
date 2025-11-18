package com.example.unicitywallet.data.contact

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.example.unicitywallet.data.chat.Converters

@Database(
    entities = [ContactEntity::class],
    version = 1,
    exportSchema = false
)

@TypeConverters(Converters::class)
abstract class ContactDatabase : RoomDatabase() {
    abstract fun contactDao(): ContactDao

    companion object {
        @Volatile
        private var INSTANCE: ContactDatabase? = null

        fun getInstance(context: Context): ContactDatabase {
            synchronized(this) {
                var instance = INSTANCE

                if (instance == null) {
                    instance = Room.databaseBuilder(
                        context.applicationContext,
                        ContactDatabase::class.java,
                        "contacts_database"
                    )
                        // .fallbackToDestructiveMigration() // Destroys old database on version change
                        .build()

                    INSTANCE = instance
                }
                return instance
            }
        }
    }
}