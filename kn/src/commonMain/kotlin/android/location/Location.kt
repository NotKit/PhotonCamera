package android.location

/**
 * The GPS fix a JPEG carries (CaptureRequest.JPEG_GPS_LOCATION).  Nothing in
 * this port produces one - there is no location provider behind it - but the
 * key's type has to exist for the request tables to compile, and an app that
 * builds a Location and sets it gets it written out.
 */
open class Location(private val provider: String?) {
	var latitude: Double = 0.0
	var longitude: Double = 0.0
	var altitude: Double = 0.0
	var time: Long = 0L
	var bearing: Float = 0f

	fun getProvider(): String? = provider
	fun getLatitude(): Double = latitude
	fun getLongitude(): Double = longitude
	fun getAltitude(): Double = altitude
	fun getTime(): Long = time
	fun getBearing(): Float = bearing
}
