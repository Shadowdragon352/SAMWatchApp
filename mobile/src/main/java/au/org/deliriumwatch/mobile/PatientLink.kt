package au.org.deliriumwatch.mobile

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object PatientLink {
    data class Result(val linked: Boolean, val name: String, val studyId: String)
    fun fetch(base: String, token: String, deviceId: String): Result {
        require(base.isNotBlank()) { "Save the dashboard URL first" }
        require(deviceId.isNotBlank()) { "Enter the watch device ID or receive a watch batch first" }
        val id = java.net.URLEncoder.encode(deviceId, "UTF-8")
        val connection = URL("${base.trimEnd('/')}/api/v1/device-assignment?device_id=$id").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json")
            if (token.isNotBlank()) connection.setRequestProperty("Authorization", "Bearer $token")
            check(connection.responseCode == 200) { "Patient lookup HTTP ${connection.responseCode}; check URL, device ID and token" }
            val result = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            check(result.getString("device_id") == deviceId) { "Device mismatch in server response" }
            return Result(result.getBoolean("linked"), if(result.isNull("patient_name")) "" else result.getString("patient_name"), if(result.isNull("study_id")) "" else result.getString("study_id"))
        } finally { connection.disconnect() }
    }
}
