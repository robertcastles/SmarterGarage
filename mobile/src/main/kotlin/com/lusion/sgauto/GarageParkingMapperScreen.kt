package com.lusion.sgauto

import android.content.Intent
import android.graphics.Bitmap
import android.location.Location
import android.text.SpannableString
import android.text.Spanned
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.HostException
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.model.CarLocation
import androidx.car.app.model.Distance
import androidx.car.app.model.DistanceSpan
import androidx.car.app.model.ForegroundCarColorSpan
import androidx.car.app.model.ItemList
import androidx.car.app.model.Metadata
import androidx.car.app.model.Place
import androidx.car.app.model.PlaceListMapTemplate
import androidx.car.app.model.PlaceMarker
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.core.graphics.drawable.IconCompat
import androidx.core.net.toUri
import com.lusion.sgauto.LusionUtility.getBitmapFromVectorDrawable
import com.lusion.sgauto.PreferenceUtility.getPreference

/**
 * Smarter Garage default Auto launch screen
 *
 *
 * See [SGService] for the app's entry point to the car host.
 */
class GarageParkingMapperScreen private constructor(carContext: CarContext) : Screen(carContext) {
    private var homeLoc: Location? = getPreference(KEY_HOME, Location::class.java, carContext) as Location?
    private val homeName: String? = getPreference(KEY_GEOFENCE_CUSTOM_NAME, String::class.java, carContext) as String?
    private val garageStatusBitmap: Bitmap = getBitmapFromVectorDrawable(carContext, R.drawable.smarter_garage_svg)!!
    private val garagePlaceMarker: PlaceMarker = createPlaceMarker(garageStatusBitmap)
    private val description: SpannableString = createDescription(homeName)
    private val locationMetadata: Metadata = createLocationMetadata(homeLoc, garagePlaceMarker)
    // PlaceListMapTemplate is for PARKING
    // PlaceListNavigationTemplate is for NAVIGATION

    override fun onGetTemplate(): Template {
        val listBuilder = ItemList.Builder()
            .addItem(
                Row.Builder()
                    .setTitle(homeName!!)
                    .addText(description)
                    .setOnClickListener { onClickNavigate() }
                    .setBrowsable(true)
                    .setMetadata(locationMetadata)
                    .build()
            )

        return PlaceListMapTemplate.Builder()
            .setTitle(TITLE_YOUR_GARAGES)
            .setHeaderAction(Action.BACK)
            .setCurrentLocationEnabled(true)
            .setItemList(listBuilder.build())
            .build()
    }

    private fun createPlaceMarker(bitmap: Bitmap): PlaceMarker {
        return PlaceMarker.Builder()
            .setIcon(CarIcon.Builder(IconCompat.createWithBitmap(bitmap)).build(), PlaceMarker.TYPE_IMAGE)
            .build()
    }

    private fun createDescription(homeName: String?): SpannableString {
        val description = SpannableString("   \u00b7 $homeName")
        description.setSpan(
            DistanceSpan.create(Distance.create(DISTANCE_KM.toDouble(), Distance.UNIT_MILES)),
            0,
            1,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        description.setSpan(
            ForegroundCarColorSpan.create(CarColor.BLUE),
            0,
            1,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        return description
    }

    private fun createLocationMetadata(location: Location?, placeMarker: PlaceMarker): Metadata {
        return Metadata.Builder()
            .setPlace(
                Place.Builder(CarLocation.create(location!!))
                    .setMarker(placeMarker)
                    .build()
            )
            .build()
    }

    private fun onClickNavigate() {
        val uri = "geo:${homeLoc!!.latitude},${homeLoc!!.longitude}".toUri()
        val intent = Intent(CarContext.ACTION_NAVIGATE, uri)
        try {
            carContext.startCarApp(intent)
        } catch (_: HostException) {
            CarToast.makeText(carContext, TOAST_NAVIGATION_FAILURE, CarToast.LENGTH_LONG).show()
        }
    }

    companion object {
        private const val KEY_HOME = "home"
        private const val KEY_GEOFENCE_CUSTOM_NAME = "geofence_custom_name"
        private const val TITLE_YOUR_GARAGES = "Your garages"
        private const val DISTANCE_KM = 5
        private const val TOAST_NAVIGATION_FAILURE = "Failure starting navigation"

        @JvmStatic
        fun create(carContext: CarContext): GarageParkingMapperScreen {
            return GarageParkingMapperScreen(carContext)
        }
    }
}