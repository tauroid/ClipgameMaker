package uk.co.dtapgames.apps.flatgamemaker

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View

/**
 * Created by adam on 28/01/18.
 */
class TitleScreen : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.titlescreen)

        if (!Flatgame.internalGamesDir(this).exists()) {
            Flatgame.internalGamesDir(this).mkdirs()
        }

        if (!Flatgame.externalGamesDir(this).exists()) {
            Flatgame.externalGamesDir(this).mkdirs()
        }
    }

    fun openPlayMenu (view: View) = startActivity(Intent(this, PlayMenu::class.java))

    fun openCreateMenu (view: View) = startActivity(Intent(this, CreateMenu::class.java))
}