package com.jilla

import android.app.Application
import com.jilla.service.MeshNode

class JillaApp : Application() {
    val node: MeshNode by lazy { MeshNode(this) }
}
