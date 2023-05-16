package uk.co.dtapgames.apps.flatgamemaker

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.database.DataSetObserver
import android.net.Uri
import android.os.Bundle
import android.os.FileObserver
import android.os.Handler
import android.support.v4.app.DialogFragment
import android.support.v4.content.FileProvider.getUriForFile
import android.support.v7.app.AlertDialog
import android.support.v7.app.AppCompatActivity
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.widget.EditText
import android.widget.ListAdapter
import android.widget.TextView
import kotlinx.android.synthetic.main.createmenu.*
import java.io.File

/**
 * Created by adam on 28/01/18.
 */
class CreateMenu : AppCompatActivity() {
    var cmadapter : CreateMenuAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.createmenu)

        cmadapter = CreateMenuAdapter(this, Handler())

        createlist.adapter = cmadapter

        setSupportActionBar(create_menu_toolbar)

        create_menu_toolbar.setNavigationOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()

        cmadapter?.startWatching()
    }

    override fun onPause() {
        cmadapter?.stopWatching()

        super.onPause()
    }

    fun createFlatgame(view: View) = createFlatgameDialog(null)

    fun createFlatgameDialog(message: Int?) : Unit =
            nameDialog(this, message, R.string.create, R.string.cancel,
                    {dialog, name ->
                        if (!Flatgame.internalGamesList(this).contains(name)) {
                            try {
                                Flatgame.writeInternal(this, Flatgame(name, "draft", true, null, mutableListOf(), mutableMapOf()))
                            } catch (e: FileSystemException) {
                                dialog.cancel()
                                createFlatgameDialog(R.string.invalid_name)
                            }
                        } else {
                            dialog.cancel()
                            createFlatgameDialog(R.string.flatgame_already_exists)
                        }
                    })
}

class CreateMenuAdapter (private val activity: Activity, handler: Handler)
    : DirectoryAdapter<String>(handler, Flatgame.internalGamesDir(activity), {dir -> dir.list().toList()}) {

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val view = convertView as? TextView ?: TextView(activity)
        view.text = items[position]
        view.textSize = 28F
        view.setPadding(30, 30, 30, 30)
        view.setOnClickListener {
            val intent = Intent(activity, FlatgameEditor::class.java)
            intent.putExtra("flatgame-internal", items[position])

            activity.startActivity(intent)
        }
        view.setOnLongClickListener{
            renameOrDeleteDialog(activity, position)
            true
        }
        return view
    }

    override fun isEnabled(position: Int) : Boolean = true
    override fun areAllItemsEnabled(): Boolean = true

    override fun actuallyDeleteItem(position: Int) {
        File(directory, items[position]).deleteRecursively()
    }

    override fun actuallyRenameItem(position: Int, name: String) {
        val newfolder = File(directory, name)
        File(directory, items[position]).renameTo(newfolder)

        val snapshots = Flatgame.getConfigs(newfolder)

        snapshots.map{flatgame -> flatgame.copy(name = name)}
                 .forEach{flatgame -> Flatgame.writeInternal(activity, flatgame)}
    }
}