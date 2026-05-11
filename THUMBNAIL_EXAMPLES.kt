/**
 * 缩略图模式使用示例
 * 
 * 在图片列表中显示缩略图,只读取前256KB,大幅提升加载速度
 */

// ==================== 示例1: 在文件列表中使用缩略图 ====================

@Composable
fun ImageListWithThumbnails(
    imageFiles: List<MediaFile>,
    mediaController: MediaController,
    onImageClick: (MediaFile) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(8.dp)
    ) {
        items(imageFiles) { file ->
            ImageThumbnailItem(
                file = file,
                mediaController = mediaController,
                onClick = { onImageClick(file) }
            )
        }
    }
}

@Composable
fun ImageThumbnailItem(
    file: MediaFile,
    mediaController: MediaController,
    onClick: () -> Unit
) {
    var thumbnailUrl by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    
    // ✅ 获取缩略图 URL (只读取前 256KB)
    LaunchedEffect(file.path) {
        isLoading = true
        val url = mediaController.getThumbnailUrl(file, object : MediaController.MediaCallback {
            override fun onSuccess(message: String) {}
            override fun onError(message: String) {
                isLoading = false
            }
        })
        thumbnailUrl = url
        isLoading = false
    }
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // ✅ 显示缩略图
            Box(
                modifier = Modifier.size(80.dp),
                contentAlignment = Alignment.Center
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp
                    )
                } else if (thumbnailUrl != null) {
                    AsyncImage(
                        model = thumbnailUrl,
                        contentDescription = file.name,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Image,
                        contentDescription = null,
                        modifier = Modifier.size(40.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            
            Spacer(modifier = Modifier.width(12.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = file.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = formatFileSize(file.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}


// ==================== 示例2: 智能选择缩略图或全图 ====================

@Composable
fun SmartImageLoader(
    file: MediaFile,
    mediaController: MediaController,
    isThumbnailMode: Boolean = true,  // true=缩略图, false=全图
    modifier: Modifier = Modifier
) {
    var imageUrl by remember { mutableStateOf<String?>(null) }
    
    LaunchedEffect(file.path, isThumbnailMode) {
        imageUrl = if (isThumbnailMode) {
            // ✅ 大图片使用缩略图模式
            if (file.size > 500 * 1024) {  // > 500KB
                mediaController.getThumbnailUrl(file, object : MediaController.MediaCallback {
                    override fun onSuccess(message: String) {}
                    override fun onError(message: String) {}
                })
            } else {
                // 小图片直接加载全图
                mediaController.getImageUrl(file, object : MediaController.MediaCallback {
                    override fun onSuccess(message: String) {}
                    override fun onError(message: String) {}
                })
            }
        } else {
            // 全图模式
            mediaController.getImageUrl(file, object : MediaController.MediaCallback {
                override fun onSuccess(message: String) {}
                override fun onError(message: String) {}
            })
        }
    }
    
    if (imageUrl != null) {
        AsyncImage(
            model = imageUrl,
            contentDescription = file.name,
            modifier = modifier,
            contentScale = ContentScale.Fit
        )
    }
}


// ==================== 示例3: 带缓存的缩略图加载 ====================

class ThumbnailCache(private val maxSize: Int = 100) {
    private val cache = LruCache<String, Bitmap>(maxSize)
    
    fun get(path: String): Bitmap? {
        return cache.get("${path}_thumb")
    }
    
    fun put(path: String, bitmap: Bitmap) {
        cache.put("${path}_thumb", bitmap)
    }
    
    fun clear() {
        cache.evictAll()
    }
}

@Composable
fun CachedImageThumbnail(
    file: MediaFile,
    mediaController: MediaController,
    thumbnailCache: ThumbnailCache,
    modifier: Modifier = Modifier.size(80.dp)
) {
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    
    LaunchedEffect(file.path) {
        // ✅ 先查缓存
        val cached = thumbnailCache.get(file.path)
        if (cached != null) {
            bitmap = cached
            return@LaunchedEffect
        }
        
        // ✅ 缓存未命中,加载缩略图
        isLoading = true
        try {
            val url = mediaController.getThumbnailUrl(file, object : MediaController.MediaCallback {
                override fun onSuccess(message: String) {}
                override fun onError(message: String) {}
            })
            
            if (url != null) {
                // 异步加载图片
                val loadedBitmap = loadImageBitmap(url)
                bitmap = loadedBitmap
                
                // ✅ 存入缓存
                thumbnailCache.put(file.path, loadedBitmap)
            }
        } finally {
            isLoading = false
        }
    }
    
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                strokeWidth = 2.dp
            )
        } else if (bitmap != null) {
            Image(
                bitmap = bitmap!!.asImageBitmap(),
                contentDescription = file.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Icon(
                imageVector = Icons.Default.Image,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// 辅助函数: 从URL加载Bitmap
suspend fun loadImageBitmap(url: String): Bitmap {
    return withContext(Dispatchers.IO) {
        val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        connection.inputStream.use { input ->
            android.graphics.BitmapFactory.decodeStream(input)
        }
    }
}


// ==================== 示例4: 网格布局缩略图 ====================

@Composable
fun ImageThumbnailGrid(
    imageFiles: List<MediaFile>,
    mediaController: MediaController,
    onImageClick: (MediaFile) -> Unit,
    columns: Int = 3
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items(imageFiles) { file ->
            ImageThumbnailCard(
                file = file,
                mediaController = mediaController,
                onClick = { onImageClick(file) }
            )
        }
    }
}

@Composable
fun ImageThumbnailCard(
    file: MediaFile,
    mediaController: MediaController,
    onClick: () -> Unit
) {
    var thumbnailUrl by remember { mutableStateOf<String?>(null) }
    
    LaunchedEffect(file.path) {
        thumbnailUrl = mediaController.getThumbnailUrl(file, object : MediaController.MediaCallback {
            override fun onSuccess(message: String) {}
            override fun onError(message: String) {}
        })
    }
    
    Card(
        modifier = Modifier
            .aspectRatio(1f)
            .clickable(onClick = onClick)
    ) {
        if (thumbnailUrl != null) {
            AsyncImage(
                model = thumbnailUrl,
                contentDescription = file.name,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Image,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}


// ==================== 使用示例 ====================

/*
在 MainActivity.kt 中使用:

@Composable
fun MainScreen() {
    val mediaController = remember { MediaController(context, callback) }
    val imageFiles = remember { listOf(/* ... */) }
    
    when (currentView) {
        ViewMode.LIST -> {
            // ✅ 列表模式: 使用缩略图
            ImageListWithThumbnails(
                imageFiles = imageFiles,
                mediaController = mediaController,
                onImageClick = { file ->
                    // 点击后切换到全图预览
                    showImagePreview(file)
                }
            )
        }
        ViewMode.GRID -> {
            // ✅ 网格模式: 使用缩略图
            ImageThumbnailGrid(
                imageFiles = imageFiles,
                mediaController = mediaController,
                onImageClick = { file ->
                    showImagePreview(file)
                }
            )
        }
        ViewMode.PREVIEW -> {
            // ✅ 全图预览模式: 使用完整图片
            ImagePreviewScreen(
                file = currentFile,
                mediaController = mediaController
            )
        }
    }
}
*/
