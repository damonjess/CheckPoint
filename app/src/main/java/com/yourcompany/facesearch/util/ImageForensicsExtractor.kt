package com.yourcompany.facesearch.util

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import java.io.InputStream

data class ExifForensicsData(
    val cameraMake: String = "Unknown",
    val cameraModel: String = "Unknown",
    val dateTimeOriginal: String = "Unknown",
    val software: String = "Unknown",
    val latitude: Double? = null,
    val longitude: Double? = null,
    val altitude: Double? = null,
    val exposureTime: String = "Unknown",
    val fNumber: String = "Unknown",
    val iso: String = "Unknown",
    val focalLength: String = "Unknown",
    val orientation: String = "Normal",
    val rawAttributes: Map<String, String> = emptyMap()
)

object ImageForensicsExtractor {

    fun extract(inputStream: InputStream): ExifForensicsData {
        return try {
            val exif = ExifInterface(inputStream)
            
            val make = exif.getAttribute(ExifInterface.TAG_MAKE) ?: "Unknown"
            val model = exif.getAttribute(ExifInterface.TAG_MODEL) ?: "Unknown"
            val dateTime = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) ?: "Unknown"
            val software = exif.getAttribute(ExifInterface.TAG_SOFTWARE) ?: "Unknown"
            
            val latLong = exif.latLong
            val lat = latLong?.takeIf { it.size >= 2 }?.get(0)
            val lon = latLong?.takeIf { it.size >= 2 }?.get(1)
            val altitude = exif.getAltitude(0.0).takeIf { exif.getAttribute(ExifInterface.TAG_GPS_ALTITUDE) != null }

            val exposure = exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME) ?: "Unknown"
            val fNum = exif.getAttribute(ExifInterface.TAG_F_NUMBER) ?: "Unknown"
            val iso = exif.getAttribute(ExifInterface.TAG_ISO_SPEED) ?: exif.getAttribute("ISOSpeedRatings") ?: "Unknown"
            val focal = exif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH) ?: "Unknown"

            val orientationCode = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            val orientation = when (orientationCode) {
                ExifInterface.ORIENTATION_ROTATE_90 -> "Rotate 90"
                ExifInterface.ORIENTATION_ROTATE_180 -> "Rotate 180"
                ExifInterface.ORIENTATION_ROTATE_270 -> "Rotate 270"
                else -> "Normal"
            }

            val attributes = mutableMapOf<String, String>()
            val tags = listOf(
                ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_DATETIME_ORIGINAL,
                ExifInterface.TAG_SOFTWARE, ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LONGITUDE,
                ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_F_NUMBER,
                ExifInterface.TAG_FOCAL_LENGTH, ExifInterface.TAG_WHITE_BALANCE
            )
            tags.forEach { tag ->
                exif.getAttribute(tag)?.let { attributes[tag] = it }
            }

            ExifForensicsData(
                cameraMake = make,
                cameraModel = model,
                dateTimeOriginal = dateTime,
                software = software,
                latitude = lat,
                longitude = lon,
                altitude = altitude,
                exposureTime = exposure,
                fNumber = fNum,
                iso = iso,
                focalLength = focal,
                orientation = orientation,
                rawAttributes = attributes
            )
        } catch (e: Exception) {
            ExifForensicsData()
        }
    }

    fun extract(context: Context, uri: Uri): ExifForensicsData {
        return try {
            val targetUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    MediaStore.setRequireOriginal(uri)
                } catch (e: Exception) {
                    uri
                }
            } else {
                uri
            }
            context.contentResolver.openInputStream(targetUri)?.use { extract(it) } ?: ExifForensicsData()
        } catch (e: Exception) {
            ExifForensicsData()
        }
    }
}
