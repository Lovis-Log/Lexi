package io.github.lovislog.lexi

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val PhotoCropSaver = listSaver<Rect, Float>(
    save = { listOf(it.left, it.top, it.right, it.bottom) },
    restore = { Rect(it[0], it[1], it[2], it[3]) },
)

@Composable
internal fun PhotoLookup(
    sending: Boolean,
    completed: Boolean,
    failure: LookupState.Failed?,
    onDismiss: () -> Unit,
    onComplete: () -> Unit,
    onClearRequest: () -> Unit,
    onSettings: () -> Unit,
    onVision: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    var pendingPhoto by rememberSaveable { mutableStateOf<String?>(null) }
    var cameraFile by rememberSaveable { mutableStateOf<String?>(null) }
    var photo by remember(selectedUri) { mutableStateOf<Bitmap?>(null) }
    var preparing by remember { mutableStateOf(false) }
    var error by remember(selectedUri) { mutableStateOf<LocalizedText?>(null) }
    var crop by rememberSaveable(selectedUri, stateSaver = PhotoCropSaver) {
        mutableStateOf(Rect(0.12f, 0.22f, 0.88f, 0.78f))
    }

    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val path = pendingPhoto
        pendingPhoto = null
        if (saved && path != null) {
            cameraFile = path
            selectedUri = photoUri(context, File(path))
        } else {
            path?.let { File(it).delete() }
        }
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) selectedUri = uri
    }

    fun back() {
        if (preparing) return
        if (sending) {
            onClearRequest()
            return
        }
        cameraFile?.let { File(it).delete() }
        cameraFile = null
        if (selectedUri != null) {
            selectedUri = null
            onClearRequest()
        } else {
            pendingPhoto?.let { File(it).delete() }
            onDismiss()
        }
    }

    LaunchedEffect(selectedUri) {
        val uri = selectedUri ?: return@LaunchedEffect
        try {
            photo = withContext(Dispatchers.IO) { loadPhoto(context.contentResolver, uri) }
            if (photo == null) error = LocalizedText("无法打开图片，请返回并重新选择。", "Could not open the photo. Go back and choose again.")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            error = LocalizedText("无法打开图片，请返回并重新选择。", "Could not open the photo. Go back and choose again.")
        }
    }

    Dialog(onDismissRequest = ::back, properties = DialogProperties(
        usePlatformDefaultWidth = false, decorFitsSystemWindows = false, dismissOnClickOutside = false,
    )) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 16.dp)) {
                Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = ::back, enabled = !preparing) { Text(tr("‹ 返回", "‹ Back")) }
                    Text(
                        if (selectedUri == null) tr("照片查词", "Photo lookup") else tr("框选要查的文字", "Select text"),
                        style = MaterialTheme.typography.titleLarge,
                    )
                }

                val bitmap = photo
                when {
                    bitmap != null -> {
                        CropPhoto(remember(bitmap) { bitmap.asImageBitmap() }, crop, { crop = it },
                            Modifier.weight(1f).fillMaxWidth(), enabled = !sending && !preparing)
                        Column(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            Text(tr("拖动选框移动，拖动四角调整大小。框内图片将发送给当前 AI 接口。",
                                "Drag the selection or its corners to resize it. The cropped area goes to your AI endpoint."),
                                style = MaterialTheme.typography.bodySmall)
                            failure?.let { Text(it.message.display(), color = MaterialTheme.colorScheme.error) }
                            error?.let { Text(it.display(), color = MaterialTheme.colorScheme.error) }
                            if (sending || preparing) LinearProgressIndicator(Modifier.fillMaxWidth())
                            if (failure?.opensSettings == true) {
                                TextButton(onClick = {
                                    cameraFile?.let { File(it).delete() }
                                    onSettings()
                                }) { Text(tr("打开 AI 设置", "Open AI settings")) }
                            }
                            Button(
                                enabled = !sending && !preparing,
                                modifier = Modifier.fillMaxWidth(),
                                onClick = {
                                    val selection = crop
                                    scope.launch {
                                        preparing = true
                                        error = null
                                        try {
                                            val image = withContext(Dispatchers.IO) {
                                                val region = cropBitmap(bitmap, selection)
                                                val out = ByteArrayOutputStream()
                                                check(region.compress(Bitmap.CompressFormat.JPEG, 90, out))
                                                Base64.getEncoder().encodeToString(out.toByteArray())
                                            }
                                            onVision(image)
                                        } catch (cancelled: CancellationException) {
                                            throw cancelled
                                        } catch (_: Exception) {
                                            error = LocalizedText("无法裁剪图片，请重新框选。", "Could not crop the image. Adjust the selection.")
                                        } finally {
                                            preparing = false
                                        }
                                    }
                                },
                            ) { Text(if (sending) tr("正在识图…", "Analyzing…") else tr("用 AI 识图", "Analyze with AI")) }
                        }
                    }
                    selectedUri != null -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        if (error == null) CircularProgressIndicator()
                        else error?.let { Text(it.display(), color = MaterialTheme.colorScheme.error) }
                    }
                    else -> Column(Modifier.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        error?.let { Text(it.display(), color = MaterialTheme.colorScheme.error) }
                        OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = {
                            try {
                                val folder = File(context.cacheDir, "lookup-camera").apply { mkdirs() }
                                val file = File.createTempFile("capture-", ".jpg", folder)
                                pendingPhoto = file.path
                                camera.launch(photoUri(context, file))
                            } catch (_: Exception) {
                                pendingPhoto?.let { File(it).delete() }
                                pendingPhoto = null
                                error = LocalizedText("无法打开相机，可以改用相册。", "Could not open the camera. You can use your photo library.")
                            }
                        }) { Text(tr("拍照", "Take photo")) }
                        OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = {
                            gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        }) { Text(tr("从相册选择", "Choose photo")) }
                    }
                }
            }
        }
    }
    LaunchedEffect(completed) {
        if (completed) {
            cameraFile?.let { File(it).delete() }
            onComplete()
        }
    }
}

@Composable
internal fun CameraMark() {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(Modifier.size(22.dp)) {
        val line = 1.7.dp.toPx()
        drawRoundRect(color, Offset(size.width * 0.09f, size.height * 0.26f),
            Size(size.width * 0.82f, size.height * 0.58f), CornerRadius(3.dp.toPx()), style = Stroke(line))
        drawCircle(color, size.width * 0.16f, Offset(size.width * 0.5f, size.height * 0.55f), style = Stroke(line))
        drawLine(color, Offset(size.width * 0.36f, size.height * 0.15f),
            Offset(size.width * 0.64f, size.height * 0.15f), line)
    }
}

@Composable
private fun CropPhoto(bitmap: ImageBitmap, crop: Rect, onCrop: (Rect) -> Unit, modifier: Modifier, enabled: Boolean) {
    val selection = rememberUpdatedState(crop)
    val update = rememberUpdatedState(onCrop)
    val accent = MaterialTheme.colorScheme.primary
    Canvas(modifier.pointerInput(bitmap, enabled) {
        if (!enabled) return@pointerInput
        var drag: CropHandle? = null
        detectDragGestures(
            onDragStart = { point ->
                val photo = fitPhoto(bitmap, Size(size.width.toFloat(), size.height.toFloat()))
                val frame = selection.value.inPhoto(photo)
                val hit = 28.dp.toPx()
                val nearest = CropHandle.entries.filter { it != CropHandle.MOVE }
                    .minByOrNull { (it.corner(frame) - point).getDistanceSquared() }
                drag = nearest?.takeIf { (it.corner(frame) - point).getDistanceSquared() <= hit * hit }
                    ?: CropHandle.MOVE.takeIf { frame.contains(point) }
            },
            onDrag = { change, amount ->
                val handle = drag ?: return@detectDragGestures
                val photo = fitPhoto(bitmap, Size(size.width.toFloat(), size.height.toFloat()))
                change.consume()
                update.value(selection.value.dragged(handle, amount.x / photo.width, amount.y / photo.height))
            },
        )
    }) {
        val photo = fitPhoto(bitmap, size)
        drawImage(bitmap, dstOffset = IntOffset(photo.left.roundToInt(), photo.top.roundToInt()),
            dstSize = IntSize(photo.width.roundToInt().coerceAtLeast(1), photo.height.roundToInt().coerceAtLeast(1)))
        val frame = crop.inPhoto(photo)
        val shade = Color.Black.copy(alpha = 0.58f)
        drawRect(shade, photo.topLeft, Size(photo.width, frame.top - photo.top))
        drawRect(shade, Offset(photo.left, frame.bottom), Size(photo.width, photo.bottom - frame.bottom))
        drawRect(shade, Offset(photo.left, frame.top), Size(frame.left - photo.left, frame.height))
        drawRect(shade, Offset(frame.right, frame.top), Size(photo.right - frame.right, frame.height))
        drawRect(Color.White, frame.topLeft, frame.size, style = Stroke(2.dp.toPx()))
        CropHandle.entries.filter { it != CropHandle.MOVE }.forEach {
            drawCircle(Color.White, 6.dp.toPx(), it.corner(frame))
            drawCircle(accent, 4.dp.toPx(), it.corner(frame))
        }
    }
}

private enum class CropHandle(val x: Int, val y: Int) {
    TOP_LEFT(-1, -1), TOP_RIGHT(1, -1), BOTTOM_LEFT(-1, 1), BOTTOM_RIGHT(1, 1), MOVE(0, 0);

    fun corner(rect: Rect) = Offset(if (x < 0) rect.left else rect.right, if (y < 0) rect.top else rect.bottom)
}

private fun Rect.dragged(handle: CropHandle, dx: Float, dy: Float): Rect {
    if (handle == CropHandle.MOVE) {
        return translate(Offset(dx.coerceIn(-left, 1f - right), dy.coerceIn(-top, 1f - bottom)))
    }
    val minimum = 0.06f
    return Rect(
        if (handle.x < 0) (left + dx).coerceIn(0f, right - minimum) else left,
        if (handle.y < 0) (top + dy).coerceIn(0f, bottom - minimum) else top,
        if (handle.x > 0) (right + dx).coerceIn(left + minimum, 1f) else right,
        if (handle.y > 0) (bottom + dy).coerceIn(top + minimum, 1f) else bottom,
    )
}

private fun Rect.inPhoto(photo: Rect) = Rect(
    photo.left + left * photo.width, photo.top + top * photo.height,
    photo.left + right * photo.width, photo.top + bottom * photo.height,
)

private fun fitPhoto(bitmap: ImageBitmap, viewport: Size): Rect {
    val scale = min(viewport.width / bitmap.width, viewport.height / bitmap.height)
    val width = bitmap.width * scale
    val height = bitmap.height * scale
    val start = Offset((viewport.width - width) / 2f, (viewport.height - height) / 2f)
    return Rect(start, Size(width, height))
}

private fun photoUri(context: Context, file: File): Uri =
    FileProvider.getUriForFile(context, "${context.packageName}.photo", file)

private fun loadPhoto(resolver: ContentResolver, uri: Uri): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    val edge = maxOf(bounds.outWidth, bounds.outHeight)
    while (edge / sample > 3072) sample *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    val bitmap = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) } ?: return null
    val orientation = try {
        resolver.openInputStream(uri)?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) } ?: 1
    } catch (_: java.io.IOException) {
        1
    }
    val matrix = Matrix().apply {
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(270f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(270f)
        }
    }
    return if (matrix.isIdentity) bitmap else Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}

private fun cropBitmap(bitmap: Bitmap, crop: Rect): Bitmap {
    val left = (crop.left * bitmap.width).roundToInt().coerceIn(0, bitmap.width - 1)
    val top = (crop.top * bitmap.height).roundToInt().coerceIn(0, bitmap.height - 1)
    val right = (crop.right * bitmap.width).roundToInt().coerceIn(left + 1, bitmap.width)
    val bottom = (crop.bottom * bitmap.height).roundToInt().coerceIn(top + 1, bitmap.height)
    return Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
}
