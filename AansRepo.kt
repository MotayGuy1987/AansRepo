package com.aansrepo

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import com.aansrepo.providers.*

@CloudstreamPlugin
class AansRepoPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(MovieHaxProvider())
        registerMainAPI(HDHub4UProvider())
        registerMainAPI(HindiLinks4UProvider())
        registerMainAPI(FilmyzillaProvider())
        registerMainAPI(CinebyProvider())
    }
}
