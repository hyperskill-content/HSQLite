package com.example.hsqlite

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Paint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.CalendarView
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import java.time.LocalDate
import java.time.Month
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit


class MainActivity : Activity() {

    private val personAdapter = object : ListAdapter<Person, RecyclerView.ViewHolder>(
        object : DiffUtil.ItemCallback<Person>() {
            override fun areItemsTheSame(oldItem: Person, newItem: Person): Boolean = oldItem.id == newItem.id
            override fun areContentsTheSame(oldItem: Person, newItem: Person): Boolean = oldItem.name == newItem.name
        }
    ) {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
            object : RecyclerView.ViewHolder(
                LayoutInflater.from(parent.context).inflate(R.layout.item_person, parent, false)
            ) {}.also { holder ->
                holder.itemView.setOnClickListener { _ ->
                    val pos = holder.bindingAdapterPosition
                    if (pos >= 0) {
                        val clicked = currentList[pos]
                        personDialog(clicked.id, clicked.name, clicked.birth)
                    }
                }
            }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            (holder.itemView as TextView).text = currentList[position].name
        }
    }

    private lateinit var personStore: Future<PersonStore>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val fileIo = fileIoExecutor ?: Executors.newSingleThreadExecutor().also { fileIoExecutor = it }
        personStore = fileIo.submit(Callable {
            PersonStore(DbHelper(this).writableDatabase).also {
                personAdapter.submitList(it.all())
            }
        })

        findViewById<RecyclerView>(R.id.list).apply {
            adapter = personAdapter
            ItemTouchHelper(object : SwipeToRemoveCallback() {

                override fun onSwiped(at: Int) {
                    fileIoExecutor!!.execute {
                        val store = personStore.get()
                        store.delete(personAdapter.currentList[at])
                        personAdapter.submitList(store.all())
                    }
                }

                private val redPaint = Paint().also { it.color = 0x7FFF0000 }
                private val deleteIcon = getDrawable(R.drawable.ic_delete_forever)!!
                override fun onChildDraw(
                    c: Canvas, recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder,
                    dX: Float, dY: Float, actionState: Int, isCurrentlyActive: Boolean,
                ) {
                    val v = viewHolder.itemView
                    c.drawRect(v.right + dX, v.top.toFloat(), v.right.toFloat(), v.bottom.toFloat(), redPaint)
                    val iconPad = (v.height - deleteIcon.intrinsicHeight) / 2
                    deleteIcon.setBounds(
                        (v.right + dX / 2).toInt(), v.top + iconPad,
                        (v.right + dX / 2 + deleteIcon.intrinsicWidth).toInt(), v.bottom - iconPad,
                    )
                    deleteIcon.draw(c)
                    super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
                }

            }).attachToRecyclerView(this)
        }

        findViewById<ImageButton>(R.id.add).apply {
            outlineProvider = OvalOutline // make it look like FAB without actual FAB
            setOnClickListener {
                personDialog(null, "", LocalDate.now())
            }
        }
    }

    private fun personDialog(id: Long?, name: String, birth: LocalDate) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_person, null)

        val nameView = view.findViewById<EditText>(R.id.name)
            .also { it.setText(name) }

        var birth = birth
        view.findViewById<CalendarView>(R.id.birth)
            .also { cal ->
                cal.date = TimeUnit.DAYS.toMillis(birth.toEpochDay())
                // if we read `date` later, it would remain unchanged. We must set a listener instead
                cal.setOnDateChangeListener { _, year, month, dayOfMonth ->
                    birth = LocalDate.of(year, Month.values()[month], dayOfMonth)
                }
            }

        AlertDialog.Builder(this)
            .setTitle("Person")
            .setView(view)
            .setPositiveButton("Save") { _, _ ->
                fileIoExecutor!!.execute {
                    val store = personStore.get()
                    val name = nameView.text.toString()
                    if (id == null) store.insert(name, birth)
                    else store.update(Person(id, name, birth))
                    personAdapter.submitList(store.all())
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        personStore.get().close()
        if (isFinishing) {
            fileIoExecutor?.let {
                it.shutdown()
                fileIoExecutor = null
            }
        }
    }

    companion object {
        private var fileIoExecutor: ExecutorService? = null
    }
}

private object OvalOutline : ViewOutlineProvider() {
    override fun getOutline(view: View, outline: Outline) {
        outline.setOval(0, 0, view.width, view.height)
    }
}

private abstract class SwipeToRemoveCallback : ItemTouchHelper.Callback() {

    final override fun getMovementFlags(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int =
        makeMovementFlags(0, ItemTouchHelper.LEFT)

    final override fun onMove(
        recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder,
    ): Boolean =
        false

    final override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
        val pos = viewHolder.bindingAdapterPosition
        if (pos >= 0)
            onSwiped(pos)
    }

    protected abstract fun onSwiped(at: Int)
}
