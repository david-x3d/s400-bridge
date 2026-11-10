package dev.s400bridge

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.units.Mass
import androidx.health.connect.client.units.Percentage
import java.time.Instant

interface RecordSink { suspend fun ready(): Boolean; suspend fun write(m: Measurement) }
class HealthSink(private val context: Context): RecordSink {
    companion object {
        val permissions = setOf(HealthPermission.getWritePermission(WeightRecord::class), HealthPermission.getWritePermission(BodyFatRecord::class))
    }
    private val client get() = HealthConnectClient.getOrCreate(context)
    override suspend fun ready() = HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE &&
        client.permissionController.getGrantedPermissions().containsAll(permissions)
    override suspend fun write(m: Measurement) {
        val time = Instant.ofEpochMilli(m.time)
        fun metadata(suffix: String) = Metadata.activelyRecorded(
            clientRecordId = "s400:${m.id}:$suffix", clientRecordVersion = m.revision,
            device = Device(manufacturer = "Xiaomi", model = "S400 EU", type = Device.TYPE_SCALE))
        val records = mutableListOf<Record>(WeightRecord(time = time, zoneOffset = null, weight = Mass.kilograms(m.kg), metadata = metadata("weight")))
        m.fat?.let { records += BodyFatRecord(time = time, zoneOffset = null, percentage = Percentage(it), metadata = metadata("fat")) }
        client.insertRecords(records)
    }
}
