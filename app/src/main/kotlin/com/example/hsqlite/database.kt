package com.example.hsqlite

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.database.sqlite.SQLiteStatement
import org.intellij.lang.annotations.Language
import java.io.Closeable
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicReference


class Person(
    val id: Long,
    val name: String,
    val birth: LocalDate,
)

class DbHelper(
    context: Context,
) : SQLiteOpenHelper(context, "app.db", null, 1) {

    override fun onConfigure(db: SQLiteDatabase) {
        db.execSQL("PRAGMA foreign_keys = 1")
        db.execSQL("PRAGMA trusted_schema = 0")
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE people(
            |    _id INTEGER PRIMARY KEY
            |,   name TEXT NOT NULL
            |,   birth INTEGER NOT NULL
            |)""".trimMargin()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        throw UnsupportedOperationException() // we have single schema version, no migrations yet
    }
}

class PersonStore(val db: SQLiteDatabase) : Closeable {

    private companion object {
        private val idCol = arrayOf("_id")
        private val personCols = arrayOf("_id", "name", "birth")
    }

    fun all(): List<Person> {
        val cursor = db.query("people", idCol, null, null, null, null, null)
        val ids = try {
            LongArray(cursor.count) { _ ->
                check(cursor.moveToNext())
                cursor.getLong(0)
            }
        } finally {
            cursor.close()
        }
        return object : AbstractList<Person>() {

            override val size: Int
                get() = ids.size

            private val memo = arrayOfNulls<Person>(size)
            override fun get(index: Int): Person =
                memo[index] ?: singleOrNull(ids[index])!!.also { memo[index] = it }
        }
    }
    fun singleOrNull(id: Long): Person? {
        val cursor = db.query("people", personCols, "_id = ?", arrayOf(id.toString()), null, null, null)
        return try {
            if (cursor.moveToFirst()) Person(cursor)
            else null // not found
        } finally {
            cursor.close()
        }
    }
    private fun Person(cur: Cursor) = Person(
        cur.getLong(0),
        cur.getString(1),
        LocalDate.ofEpochDay(cur.getLong(2)),
    )

    private val insertRef = AtomicReference<SQLiteStatement>()
    fun insert(name: String, birth: LocalDate): Long =
        withStatement(insertRef, "INSERT INTO people (name, birth) VALUES (?, ?)") {
            bindString(1, name)
            bindLong(2, birth.toEpochDay())
            executeInsert()
        }

    private val updateRef = AtomicReference<SQLiteStatement>()
    fun update(person: Person): Unit =
        withStatement(updateRef, "UPDATE people SET name = ?, birth = ? WHERE _id = ?") {
            bindString(1, person.name)
            bindLong(2, person.birth.toEpochDay())
            bindLong(3, person.id)
            check(executeUpdateDelete() == 1)
        }

    private val deleteRef = AtomicReference<SQLiteStatement>()
    fun delete(person: Person): Unit =
        withStatement(deleteRef, "DELETE FROM people WHERE _id = ?") {
            bindLong(1, person.id)
            check(executeUpdateDelete() == 1)
        }

    override fun close() {
        insertRef.getAndSet(null)?.close()
        updateRef.getAndSet(null)?.close()
        deleteRef.getAndSet(null)?.close()
    }

    /**
     * Poll for an existing prepared statement or create a new one.
     * This ensures thread-safe statement reuse:
     * any thread can take or prepare a statement,
     * but two threads couldn't use one statement at the same time.
     *
     * The [ref] must be used with the same [query].
     * The statement provided to the [block] must never leak outside.
     */
    private inline fun <R> withStatement(
        ref: AtomicReference<SQLiteStatement>,
        @Language("SQL") query: String,
        block: SQLiteStatement.() -> R,
    ): R {
        val statement = ref.getAndSet(null) ?: db.compileStatement(query)
        try {
            return statement.block()
        } finally {
            ref.getAndSet(statement)?.close()
        }
    }
}
