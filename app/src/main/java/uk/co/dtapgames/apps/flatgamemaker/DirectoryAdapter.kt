package uk.co.dtapgames.apps.flatgamemaker

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.database.DataSetObservable
import android.database.DataSetObserver
import android.os.FileObserver
import android.os.Handler
import android.util.Log
import android.widget.Adapter
import android.widget.BaseAdapter
import android.widget.EditText
import java.io.File

/**
 * Created by adam on 31/01/18.
 *
 * Only supports one kind of view by default, but whatever override what you want this is just
 * useless boilerplate removal
 */
abstract class DirectoryAdapter<T : Any> (private val handler: Handler,
                                          protected val directory: File,
                                          private val mapFn: (File) -> List<T>)
    : BaseAdapter() {

    protected val items = mutableListOf<T>()

    private val dirobs : FileObserver = object : FileObserver(directory.path, FileObserver.ALL_EVENTS) {
        override fun onEvent(event: Int, path: String?) {
            if (
                    event == 0x40000080 ||
                    event == 0x40000100 ||
                    event == 0x40000200 ||
                    event == 0x00000080 ||
                    event == 0x00000100 ||
                    event == 0x00000200
            ) {
                refresh()
            }
        }
    }

    init {
        refresh()
    }

    fun refresh() {
        items.clear()

        items.addAll(mapFn(directory))

        handler.post(::notifyDataSetChanged)
    }

    fun startWatching() = dirobs.startWatching()
    fun stopWatching() = dirobs.stopWatching()

    fun renameOrDeleteDialog(activity: Activity, position: Int) {
        val builder = AlertDialog.Builder(activity)

        builder.setItems(R.array.renameordelete, { _, id ->
            when (id) {
                0 -> renameItem(activity, position, null)
                1 -> deleteItem(activity, position)
            }
        }).create().show()
    }

    fun renameItem(activity: Activity, position: Int, message: Int?) =
            nameDialog(activity, message, R.string.rename, R.string.cancel, {_, name -> actuallyRenameItem(position, name)})

    fun deleteItem(activity: Activity, position: Int) {
        val builder = AlertDialog.Builder(activity)

        builder.setTitle(R.string.areyousure_delete)
               .setPositiveButton(R.string.delete, { _, _ -> actuallyDeleteItem(position) })
               .setNegativeButton(R.string.cancel, { dialog, _ -> dialog.cancel() })

        builder.create().show()
    }

    open fun actuallyRenameItem(position: Int, name: String) {} // Up to inheriting class whether they want to or not
    open fun actuallyDeleteItem(position: Int) {}

    override fun isEmpty(): Boolean = items.isEmpty()

    override fun getItemViewType(position: Int): Int = 0

    override fun getItem(position: Int): Any = items[position]

    override fun getViewTypeCount(): Int = 1

    override fun getItemId(position: Int): Long = position.toLong()

    override fun hasStableIds(): Boolean = false

    override fun getCount(): Int = items.size
}