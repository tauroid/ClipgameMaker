package uk.co.dtapgames.apps.flatgamemaker

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.support.v4.content.ContextCompat
import android.support.v7.app.AppCompatActivity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import kotlinx.android.synthetic.main.playmenu.*
import java.io.File
import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import android.support.v4.app.ActivityCompat
import android.widget.Toast
import org.zeroturnaround.zip.ZipUtil
import java.io.IOException

/**
 * Created by adam on 28/01/18.
 */
class PlayMenu : AppCompatActivity() {
    var pmadapter : PlayMenuAdapter? = null

    companion object {
        const val REQUEST_READ_PERMISSION = 0
        const val REQUEST_EXTERNAL_CLIPGAME = 1
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.playmenu)

        setPlayMenuAdapter()

        setSupportActionBar(play_menu_toolbar)

        play_menu_toolbar.setNavigationOnClickListener { finish() }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == REQUEST_READ_PERMISSION && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            importFlatgame(floatingActionButton)
        } else {
            finish()
        }
    }

    fun importFlatgame(view: View) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), REQUEST_READ_PERMISSION)
            return
        }

        val intent = Intent(Intent.ACTION_GET_CONTENT)
        intent.type = "*/*"
        startActivityForResult(intent, REQUEST_EXTERNAL_CLIPGAME)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (data == null) return

        if (requestCode == REQUEST_EXTERNAL_CLIPGAME && resultCode == RESULT_OK) {
            actuallyImportFlatgame(data.data)
        }
    }

    private fun actuallyImportFlatgame(uri: Uri) {
        try {
            if (!uri.lastPathSegment.endsWith(Flatgame.clipgamesuffix)) throw IOException()
            val uriIn = contentResolver.openInputStream(uri)
            ZipUtil.unpack(uriIn, File(Flatgame.externalGamesDir(this), uri.lastPathSegment.dropLast(Flatgame.clipgamesuffix.length)))
            uriIn.close()
        } catch (e: IOException) {
            Toast.makeText(this, R.string.problem_reading_clipgame, Toast.LENGTH_LONG).show()
        }
        pmadapter?.notifyDataSetChanged()
    }

    fun setPlayMenuAdapter() {
        pmadapter = PlayMenuAdapter(this, Handler())

        playlist.adapter = pmadapter
    }

    override fun onResume() {
        super.onResume()

        pmadapter?.refresh()
        pmadapter?.startWatching()
    }

    override fun onPause() {
        pmadapter?.stopWatching()

        super.onPause()
    }
}

class PlayMenuAdapter (private val activity: Activity, handler: Handler)
    : DirectoryAdapter<File>(handler, Flatgame.externalGamesDir(activity), { dir -> dir.listFiles().toList()}) {

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val view = convertView as? TextView ?: TextView(activity)
        val clipgamename = items[position].name
        view.text = clipgamename
        view.textSize = 28F
        view.setPadding(30, 30, 30, 30)
        view.setOnClickListener {
            val intent = Intent(activity, PlayScreen::class.java)
            intent.putExtra("cfg", File(items[position], "$clipgamename${Flatgame.confsuffix}").canonicalPath)

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
        items[position].deleteRecursively()
    }

    override fun actuallyRenameItem(position: Int, name: String) {
        val newfolder = File(directory, name)
        items[position].renameTo(newfolder)

        val snapshots = Flatgame.getConfigs(newfolder)

        snapshots.map{flatgame -> flatgame.copy(name = name)}
                .forEach{flatgame -> Flatgame.writeInternal(activity, flatgame)}
    }
}