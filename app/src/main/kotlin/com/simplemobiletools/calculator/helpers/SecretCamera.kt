package com.simplemobiletools.calculator.helpers

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import android.view.Surface
import androidx.core.app.ActivityCompat
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class SecretCamera(activity: Activity) {

  private val context = activity.applicationContext
  private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

  fun takePhoto() {
    Log.i("SecretCamera", "takePhoto()")

    val backCameraId = getBackCameraId() ?: return
    openCamera(backCameraId)
  }

  private fun getBackCameraId(): String? {
    val cameraIds = manager.cameraIdList
    return cameraIds.find { manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraMetadata.LENS_FACING_BACK }
  }

  @SuppressLint("MissingPermission")
  private fun openCamera(cameraId: String) {
    if (hasPermission(Manifest.permission.CAMERA) && hasPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
      manager.openCamera(cameraId, this.stateCallback, null)
    }
  }

  private val stateCallback = object : CameraDevice.StateCallback() {

    override fun onOpened(camera: CameraDevice) {
      takePicture(camera)
    }

    override fun onDisconnected(camera: CameraDevice) {
      camera.close()
    }

    override fun onError(camera: CameraDevice, error: Int) {
      camera.close()
      Log.e("SecretCamera", "statecallBack#error: $error")
    }

  }

  private fun takePicture(camera: CameraDevice) {
    val characteristics = manager.getCameraCharacteristics(camera.id)
    val streamConfigurationMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
    val jpegSizes = streamConfigurationMap!!.getOutputSizes(ImageFormat.JPEG)
    val biggestSize = jpegSizes.maxBy { it.height * it.width }
    val reader = ImageReader.newInstance(biggestSize?.width ?: 640, biggestSize?.height ?: 480, ImageFormat.JPEG, 1)
    val outputSurfaces = listOf(reader.surface)
    val captureBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
      addTarget(reader.surface)
      set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
      set(CaptureRequest.JPEG_ORIENTATION, Surface.ROTATION_0)
    }

    reader.setOnImageAvailableListener(
      { imgReader: ImageReader ->
        val image = imgReader.acquireNextImage()
        val buffer = image.planes[0].buffer

        println(buffer.toString())
        saveImageToDisk(buffer.toBytes())
        image.close()
      },
      null
    )

    camera.createCaptureSession(
      outputSurfaces,
      object : CameraCaptureSession.StateCallback() {

        override fun onConfigured(session: CameraCaptureSession) {
          session.capture(
            captureBuilder.build(),
            object : CameraCaptureSession.CaptureCallback() {},
            null
          )
        }

        override fun onConfigureFailed(session: CameraCaptureSession) {}

      },
      null
    )
  }

  private fun ByteBuffer.toBytes()
      = byteArrayOf().also {
          this.get(it)
        }

  private fun saveImageToDisk(bytes: ByteArray) {
    writeToMediaStore { pfd ->
      val out = FileOutputStream(pfd.fileDescriptor)
      out.write(bytes)
    }
  }

  private fun writeToMediaStore(write: (ParcelFileDescriptor) -> Unit) {
    val resolver = context.contentResolver
    val imgCollection = getImageCollectionUri()

    val fileUri = ContentValues().apply {
      put(MediaStore.Images.Media.DISPLAY_NAME, generateName())
      if (Build.VERSION.SDK_INT >= 29) {
        put(MediaStore.Images.Media.IS_PENDING, 1)
      }
    }.run {
      resolver.insert(imgCollection, this)!!
    }

    resolver.openFileDescriptor(fileUri, "w").use { write(it!!) }

    if (Build.VERSION.SDK_INT >= 29) {
      ContentValues().apply {
        put(MediaStore.Images.Media.IS_PENDING, 0)
      }.run {
        resolver.update(fileUri, this, null, null)
      }
    }
  }

  private fun getImageCollectionUri()
    = MediaStore.Images.Media.getContentUri(
        if (Build.VERSION.SDK_INT >= 29)
          MediaStore.VOLUME_EXTERNAL_PRIMARY
        else
          MediaStore.VOLUME_EXTERNAL
    )

  private fun generateName() = LocalDateTime.now().format(DateTimeFormatter.ISO_DATE_TIME)

  private fun hasPermission(permissionName: String)
      = ActivityCompat.checkSelfPermission(context, permissionName) == PackageManager.PERMISSION_GRANTED
}