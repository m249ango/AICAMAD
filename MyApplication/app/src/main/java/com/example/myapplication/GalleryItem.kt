package com.example.myapplication

import java.io.File

data class GalleryItem(
    val file:          File,
    val score:         Int,
    val category:      String,
    val categoryScore: Int,
    val mode:          String,
    val timestamp:     String
)
