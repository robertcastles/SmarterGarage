package com.lusion.sgauto

import androidx.annotation.Keep
import com.google.gson.annotations.SerializedName

@Keep
data class GarageStatusResponse(
    val components: Components
) {
    @Keep
    data class Components(
        val main: Main
    ) {
        @Keep
        data class Main(
            val doorControl: DoorControl
        ) {
            @Keep
            data class DoorControl(
                val door: Door
            ) {
                @Keep
                data class Door(
                    val value: String
                )
            }
        }
    }
}

@Keep
data class PingRequest(
    val timestamp: String,
    val device: String,
    val lastGarageStatus: String,
    val additionalInfo: String
)

@Keep
data class GarageCommandRequest(
    val commands: List<Command>
) {
    @Keep
    data class Command(
        val component: String = "main",
        val capability: String = "doorControl",
        val command: String
    )
}

@Keep
data class GarageCommandResponse(
    val results: List<CommandResult>
) {
    @Keep
    data class CommandResult(
        val status: String
    )
}

@Keep
data class DevicesResponse(
    @SerializedName("items") val items: List<Device>
)

@Keep
data class Device(
    @SerializedName("deviceId") val deviceId: String,
    @SerializedName("name") val name: String,
    @SerializedName("label") val label: String,
    @SerializedName("components") val components: List<Component>
)

@Keep
data class Component(
    @SerializedName("id") val id: String,
    @SerializedName("label") val label: String,
    @SerializedName("capabilities") val capabilities: List<Capability>,
    @SerializedName("categories") val categories: List<Category>
)

@Keep
data class Capability(
    @SerializedName("id") val id: String,
    @SerializedName("version") val version: Int
)

@Keep
data class Category(
    @SerializedName("name") val name: String,
    @SerializedName("categoryType") val categoryType: String
)


// Data class for ping response (assuming simple string response)
@Keep
data class PingResponse(
    val message: String
)