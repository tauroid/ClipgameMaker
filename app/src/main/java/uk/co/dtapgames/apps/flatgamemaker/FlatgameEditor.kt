package uk.co.dtapgames.apps.flatgamemaker

import android.Manifest
import android.app.Activity
import android.app.Dialog
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.DataSetObserver
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.FileObserver
import android.os.Handler
import android.provider.MediaStore
import android.support.v4.app.ActivityCompat
import android.support.v4.app.DialogFragment
import android.support.v4.content.ContextCompat
import android.support.v4.content.FileProvider
import android.support.v7.app.AlertDialog
import android.support.v7.app.AppCompatActivity
import android.support.v7.widget.AppCompatSpinner
import android.support.v7.widget.Toolbar
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.widget.*
import com.google.common.io.ByteStreams
import kotlinx.android.synthetic.main.editortoolbar.*
import kotlinx.android.synthetic.main.flatgameeditor.*
import org.zeroturnaround.zip.ZipUtil
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipOutputStream

/**
 * Created by adam on 30/01/18.
 */
class FlatgameEditor : AppCompatActivity() {
    private var snapshotAdapter : SnapshotSpinnerAdapter? = null
    private var clipsadapter : ClipAdapter? = null
    private var flatgamedir : File = File("")
    private var clipsdir : File = File("")
    var flatGame : Flatgame? = null

    private var haveExternalWritePermission = false
    private var haveExternalReadPermission = false

    companion object {
        const val REQUEST_WRITE_PERMISSION_FOR_PUBLISH = 0
        const val REQUEST_READ_PERMISSION_FOR_GET_CLIP = 1
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Make sure we can operate

        val internalflatgame : String? = intent.getStringExtra("flatgame-internal")

        if (internalflatgame == null) {
            finish()
            return
        }

        flatgamedir = File(Flatgame.internalGamesDir(this), internalflatgame)

        if (!flatgamedir.exists()) {
            finish()
            return
        }

        haveExternalWritePermission = ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        haveExternalReadPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

        // UI

        setContentView(R.layout.flatgameeditor)

        setSupportActionBar(flatgame_editor_toolbar)

        supportActionBar?.setDisplayShowTitleEnabled(false)

        val editortoolbar = layoutInflater.inflate(R.layout.editortoolbar, null)

        flatgame_editor_toolbar.setNavigationOnClickListener { finish() }
        flatgame_editor_toolbar.addView(
                editortoolbar,
                Toolbar.LayoutParams(
                        Toolbar.LayoutParams.MATCH_PARENT,
                        Toolbar.LayoutParams.MATCH_PARENT
                )
        )

        // Adapters

        val snapshotadapter = SnapshotSpinnerAdapter(this, Handler(), flatgamedir)
        snapshotAdapter = snapshotadapter

        clipsdir = File(flatgamedir, Flatgame.clipsdirname)

        if (!clipsdir.exists()) clipsdir.mkdir()

        clipsadapter = ClipAdapter(this, Handler(), clipsdir, snapshot_list, snapshotadapter)

        snapshot_list.adapter = snapshotadapter

        clip_list.adapter = clipsadapter

        snapshot_list.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) {
                return
            }

            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val snapshot = snapshotadapter.getSnapshot(position)

                flatGame = snapshot

                Log.w("flatgame", "received item click")

                clipsadapter?.notifyDataSetChanged()
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            if (requestCode == REQUEST_WRITE_PERMISSION_FOR_PUBLISH) {
                haveExternalWritePermission = true
                publish(publish_btn)
            } else if (requestCode == REQUEST_READ_PERMISSION_FOR_GET_CLIP) {
                haveExternalReadPermission = true
                getExistingClip()
            }
        } else {
            Toast.makeText(this, R.string.couldnt_publish, Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()

        val flatgame = flatGame
        if (flatgame != null) flatGame = Flatgame.read(Flatgame.configFile(this, flatgame))

        clipsadapter?.notifyDataSetChanged()
        snapshotAdapter?.startWatching()
        clipsadapter?.startWatching()
    }

    override fun onPause() {
        super.onPause()

        snapshotAdapter?.stopWatching()
        clipsadapter?.stopWatching()
    }

    fun addSnapshot(view: View) {
        val snapshotpos = snapshot_list.selectedItemPosition

        val snapshot = snapshotAdapter?.getSnapshot(snapshotpos)

        if (snapshot != null) {
            nameDialog(this, R.string.make_snapshot, R.string.snapshot, R.string.cancel,
                    { _, name -> Flatgame.writeInternal(this, snapshot.copy(version = name, draft = false)) })
        }
    }

    fun removeSnapshot(view: View) {
        val sa = snapshotAdapter
        if (sa != null && sa.count > 1) {
            sa.deleteItem(this, snapshot_list.selectedItemPosition)
        }
    }

    fun playSnapshot(view: View) {
        val sa = snapshotAdapter ?: return

        val intent = Intent(this, PlayScreen::class.java)
        intent.putExtra("cfg", Flatgame.configFile(this, sa.getSnapshot(snapshot_list.selectedItemPosition)).canonicalPath)
        startActivity(intent)
    }

    fun addClip(view: View) {
        val builder = AlertDialog.Builder(this)

        builder.setItems(R.array.recordoruseexistingclip, { _, id ->
            when (id) {
                0 -> startActivityForResult(Intent(MediaStore.ACTION_VIDEO_CAPTURE), FlatgameEditor.Result.NEW_CLIP.ordinal)
                1 -> getExistingClip()
            }
        })

        builder.create().show()
    }

    fun getExistingClip() {
        if (!haveExternalReadPermission) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), REQUEST_READ_PERMISSION_FOR_GET_CLIP)
            return
        }

        startActivityForResult(Intent(Intent.ACTION_PICK).setType("video/*"), FlatgameEditor.Result.NEW_CLIP.ordinal)
    }

    fun renameClip(oldname: String, newname: String) {
        val flatgame = flatGame ?: return

        Flatgame.getConfigs(flatgamedir).forEach{ flatgame ->
            val newflatgame = Flatgame.renameClip(flatgame, oldname, newname)
            Flatgame.writeInternal(this, newflatgame)
        }

        flatGame = Flatgame.read(Flatgame.configFile(this, flatgame))

        clipsadapter?.notifyDataSetChanged()
    }

    fun deleteClip(name: String) {
        val flatgame = flatGame ?: return

        Flatgame.getConfigs(flatgamedir).forEach{ flatgame ->
            val newflatgame = Flatgame.deleteClip(flatgame, name)
            Flatgame.writeInternal(this, newflatgame)
        }

        flatGame = Flatgame.read(Flatgame.configFile(this, flatgame))

        clipsadapter?.notifyDataSetChanged()
    }

    fun publish(view: View) {
        if (!haveExternalWritePermission) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), REQUEST_WRITE_PERMISSION_FOR_PUBLISH)
            return
        }

        val sa = snapshotAdapter ?: return
        val flatgame = sa.getSnapshot(snapshot_list.selectedItemPosition)

        val localdir = File(Flatgame.externalGamesDir(this), flatgame.name)

        if (localdir.exists()) localdir.deleteRecursively() // NO RETREAT, NO SURRENDER

        localdir.mkdirs()

        val configfile = Flatgame.configFile(this, flatgame)
        configfile.copyTo(File(localdir, "${flatgame.name}${Flatgame.confsuffix}"))

        Flatgame.internalClipsDir(this, flatgame).copyRecursively(File(localdir, Flatgame.clipsdirname))

        val extdir = File(Environment.getExternalStorageDirectory(), Flatgame.publicdirname)

        if (!extdir.exists()) extdir.mkdirs()

        val zipfile = File(filesDir, "${flatgame.name}${Flatgame.clipgamesuffix}")
        ZipUtil.pack(localdir, zipfile)

        Toast.makeText(this, "${resources.getString(R.string.published)} ${Flatgame.publicdirname}/${zipfile.name}", Toast.LENGTH_LONG).show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        when {
            requestCode == Result.NEW_CLIP.ordinal && resultCode == Activity.RESULT_OK && data != null -> {
                addReceivedClipDialog(data.data, null)
            }
        }
    }

    private fun addReceivedClipDialog(uri: Uri, message: Int?) : Unit = nameDialog(this, message, R.string.add_clip, R.string.cancel,
            {dialog, name ->
                try {
                    val instream = contentResolver.openInputStream(uri)
                    val outstream = FileOutputStream(File(clipsdir, name))

                    ByteStreams.copy(instream, outstream)

                    outstream.close()
                    instream.close()

                    if (clipsdir.list().size == 1) {
                        Flatgame.getConfigs(flatgamedir).forEach { flatgame ->
                            val newflatgame = flatgame.copy(startFrame = StartFrame(0L, name))
                            Flatgame.writeInternal(this, newflatgame)
                        }
                    }
                } catch (e: FileSystemException) {
                    dialog.cancel()
                    addReceivedClipDialog(uri, R.string.invalid_name)
                }
            })

    enum class Result {
        NEW_CLIP
    }
}

class SnapshotSpinnerAdapter (private val activity: Activity, handler: Handler, snapshotsdir: File)
    : SpinnerAdapter, DirectoryAdapter<Flatgame>(handler, snapshotsdir, {dir -> Flatgame.getConfigs(dir)}) {

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View? {
        val cText1 : TextView? = convertView?.findViewById(android.R.id.text1)
        val cText2 : TextView? = convertView?.findViewById(android.R.id.text2)

        val convertViewIsOK = cText1 != null && cText2 != null

        val view = if (convertViewIsOK) convertView
                   else activity.layoutInflater.inflate(android.R.layout.simple_list_item_2, parent, false)

        val text1 : TextView? = if (convertViewIsOK) cText1 else view?.findViewById(android.R.id.text1)
        val text2 : TextView? = if (convertViewIsOK) cText1 else view?.findViewById(android.R.id.text2)

        val snapshot = items[position]
        text1?.text = snapshot.name
        text1?.setTextColor(Color.WHITE)
        text2?.text = if (snapshot.draft) "<${snapshot.version}>" else snapshot.version
        text2?.setTextColor(Color.WHITE)
        return view
    }

    fun getSnapshot(position: Int) : Flatgame {
        return items[position]
    }

    override fun actuallyDeleteItem(position: Int) {
        Flatgame.configFile(activity, items[position]).delete()
    }

    override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val view = convertView as? TextView ?: TextView(activity)
        val snapshot = items[position]
        view.text = if (snapshot.draft) "<${snapshot.version}>" else snapshot.version
        view.textSize = 20F
        return view
    }
}

class ClipAdapter (private val activity: Activity, handler: Handler, clipsdir: File, private val sl: AppCompatSpinner, private val sa: SnapshotSpinnerAdapter)
    : DirectoryAdapter<File>(handler, clipsdir, {dir -> dir.listFiles().toList().sorted()}) {

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val layout = LinearLayout(activity)
        layout.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT)
        val view = TextView(activity)
        val strip = items[position].name.dropLastWhile { it != '.' }
        if (strip.length > 1) {
            view.text = strip.dropLast(1)
        } else {
            view.text = items[position].name
        }
        view.textSize = 28F
        if (!isEnabled(position)) view.setTextColor(ContextCompat.getColor(activity, R.color.greyListItem))
        view.setPadding(30, 30, 30, 30)
        view.setOnClickListener {
            val intent = Intent(activity, ClipEditor::class.java)
            intent.data = Uri.fromFile(items[position])
            intent.putExtra("cfg", Flatgame.configFile(activity, sa.getSnapshot(sl.selectedItemPosition)).path)
            activity.startActivity(intent)
        }
        view.setOnLongClickListener {_ ->
            renameOrDeleteDialog(activity, position)
            true
        }
        val viewLayout = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        viewLayout.weight = 1F
        layout.addView(view, viewLayout)

        val flatgame = (activity as FlatgameEditor).flatGame
        if (flatgame != null) {
            if (flatgame.startFrame != null && flatgame.startFrame.clip == items[position].name) {
                val startView = ImageView(activity)
                startView.setImageDrawable(ContextCompat.getDrawable(activity, R.drawable.start_frame_symbol))
                val startViewLayout = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                startViewLayout.gravity = Gravity.CENTER_VERTICAL or Gravity.END
                startView.setPadding(0,0,50,0)
                layout.addView(startView, startViewLayout)
            }
        }
        return layout
    }

    override fun isEnabled(position: Int): Boolean {
        val flatgame = (activity as FlatgameEditor).flatGame ?: return false

        return Flatgame.clipIsUsed(flatgame, items[position].name)
    }

    override fun actuallyDeleteItem(position: Int) {
        (activity as FlatgameEditor).deleteClip(items[position].name)
        items[position].delete()
    }

    override fun actuallyRenameItem(position: Int, name: String) {
        (activity as FlatgameEditor).renameClip(items[position].name, name)
        items[position].renameTo(File(directory, name))
    }
}