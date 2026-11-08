package com.example.locationtracker

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.telephony.CellInfo
import android.telephony.CellInfoCdma
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoWcdma
import android.telephony.CellInfoNr
import android.telephony.SignalStrength
import android.telephony.TelephonyManager
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

class CellTowerCollector(private val context: Context) {
    companion object {
        private const val TAG = "CellTowerCollector"
        private const val CELL_INFO_TIMEOUT_MS = 10_000L
    }

    @SuppressLint("MissingPermission")
    suspend fun getCellTowerData(): List<CellTowerData> {
        if (!hasLocationPermission()) {
            Log.d(TAG, "No location permission, skipping cell tower scan")
            return emptyList()
        }

        val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            ?: return emptyList()

        // First try allCellInfo (cached, may be empty)
        val cachedInfos = runCatching { telephonyManager.allCellInfo }.getOrNull()
        if (!cachedInfos.isNullOrEmpty()) {
            Log.d(TAG, "Got ${cachedInfos.size} cached cell infos")
            logRawCellInfos(cachedInfos)
            return cachedInfos.mapNotNull { parseCellInfo(it) }
        }

        // Cached was empty — actively request a cell info update
        Log.d(TAG, "Cached cellInfo empty, requesting fresh update...")
        val freshInfos = requestCellInfoUpdate(telephonyManager)
        if (!freshInfos.isNullOrEmpty()) {
            Log.d(TAG, "Got ${freshInfos.size} fresh cell infos")
            logRawCellInfos(freshInfos)
            val parsed = freshInfos.mapNotNull { parseCellInfo(it) }
            parsed.forEach { Log.d(TAG, "Parsed: $it") }
            return parsed
        }

        // Cell info still empty — fall back to signal strength + operator
        // We can at least extract radio technology and signal from SignalStrength
        Log.d(TAG, "Cell info update returned empty, extracting from SignalStrength")
        val fromSignal = extractFromSignalStrength(telephonyManager)
        if (fromSignal != null) {
            Log.d(TAG, "Extracted data from SignalStrength: $fromSignal")
            return listOf(fromSignal)
        }

        Log.d(TAG, "No cell tower data available")
        return emptyList()
    }

    @SuppressLint("MissingPermission")
    private fun logRawCellInfos(cellInfos: List<CellInfo>) {
        cellInfos.forEachIndexed { i, info ->
            val registered = info.isRegistered
            val type = when (info) {
                is CellInfoLte -> "LTE(ci=${info.cellIdentity.ci}, tac=${info.cellIdentity.tac}, pci=${info.cellIdentity.pci}, mcc=${info.cellIdentity.mccString}, mnc=${info.cellIdentity.mncString}, dbm=${info.cellSignalStrength.dbm})"
                is CellInfoGsm -> "GSM(cid=${info.cellIdentity.cid}, lac=${info.cellIdentity.lac}, mcc=${info.cellIdentity.mccString}, mnc=${info.cellIdentity.mncString}, dbm=${info.cellSignalStrength.dbm})"
                is CellInfoWcdma -> "UMTS(cid=${info.cellIdentity.cid}, lac=${info.cellIdentity.lac}, psc=${info.cellIdentity.psc}, mcc=${info.cellIdentity.mccString}, mnc=${info.cellIdentity.mncString}, dbm=${info.cellSignalStrength.dbm})"
                is CellInfoNr -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) "NR(nci=${(info.cellIdentity as android.telephony.CellIdentityNr).nci}, tac=${(info.cellIdentity as android.telephony.CellIdentityNr).tac}, pci=${(info.cellIdentity as android.telephony.CellIdentityNr).pci}, mcc=${(info.cellIdentity as android.telephony.CellIdentityNr).mccString}, mnc=${(info.cellIdentity as android.telephony.CellIdentityNr).mncString}, dbm=${(info.cellSignalStrength as android.telephony.CellSignalStrengthNr).dbm})" else "NR"
                else -> info.javaClass.simpleName
            }
            Log.d(TAG, "Cell[$i] registered=$registered $type")
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun requestCellInfoUpdate(telephonyManager: TelephonyManager): List<CellInfo>? {
        return withTimeoutOrNull(CELL_INFO_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                telephonyManager.requestCellInfoUpdate(
                    context.mainExecutor,
                    object : TelephonyManager.CellInfoCallback() {
                        override fun onCellInfo(cellInfoList: MutableList<CellInfo>) {
                            Log.d(TAG, "requestCellInfoUpdate returned ${cellInfoList.size} cells")
                            if (cont.isActive) cont.resume(cellInfoList)
                        }

                        override fun onError(errorCode: Int, detail: Throwable?) {
                            Log.w(TAG, "requestCellInfoUpdate error: $errorCode ${detail?.message}")
                            if (cont.isActive) cont.resume(emptyList())
                        }
                    },
                )
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun extractFromSignalStrength(telephonyManager: TelephonyManager): CellTowerData? {
        val signal = runCatching { telephonyManager.signalStrength }.getOrNull() ?: return null
        val operator = telephonyManager.networkOperator ?: return null
        val mcc = if (operator.length >= 3) operator.substring(0, 3) else null
        val mnc = if (operator.length >= 5) operator.substring(3) else null

        // Determine radio technology from network type
        val networkType = runCatching { telephonyManager.dataNetworkType }.getOrElse { TelephonyManager.NETWORK_TYPE_UNKNOWN }
        val radioTech = when (networkType) {
            TelephonyManager.NETWORK_TYPE_NR -> "NR"
            TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
            TelephonyManager.NETWORK_TYPE_UMTS, TelephonyManager.NETWORK_TYPE_HSDPA,
            TelephonyManager.NETWORK_TYPE_HSUPA, TelephonyManager.NETWORK_TYPE_HSPA,
            TelephonyManager.NETWORK_TYPE_HSPAP -> "UMTS"
            TelephonyManager.NETWORK_TYPE_GPRS, TelephonyManager.NETWORK_TYPE_EDGE -> "GSM"
            else -> "UNKNOWN"
        }

        // Try to get LTE-specific signal data
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val lteList = runCatching {
                signal.getCellSignalStrengths(android.telephony.CellSignalStrengthLte::class.java)
            }.getOrNull()
            if (!lteList.isNullOrEmpty()) {
                val lte = lteList[0]
                return CellTowerData(
                    radioTechnology = if (radioTech == "UNKNOWN") "LTE" else radioTech,
                    mcc = mcc,
                    mnc = mnc,
                    cellId = null,
                    lac = null,
                    tac = null,
                    pci = null,
                    signalStrength = lte.dbm,
                    level = lte.level,
                    rsrp = lte.rsrp.takeIf { it != Int.MAX_VALUE },
                    rsrq = lte.rsrq.takeIf { it != Int.MAX_VALUE },
                    snr = lte.rssnr.takeIf { it != Int.MAX_VALUE },
                    ta = lte.timingAdvance.takeIf { it != Int.MAX_VALUE },
                    bands = null,
                    bandwidth = null,
                )
            }
        }

        // Generic fallback — just signal level + operator
        return CellTowerData(
            radioTechnology = radioTech,
            mcc = mcc,
            mnc = mnc,
            cellId = null,
            lac = null,
            tac = null,
            pci = null,
            signalStrength = null,
            level = signal.level,
            rsrp = null,
            rsrq = null,
            snr = null,
            ta = null,
            bands = null,
            bandwidth = null,
        )
    }

    @SuppressLint("MissingPermission")
    private fun parseCellInfo(cellInfo: CellInfo): CellTowerData? {
        if (!cellInfo.isRegistered) return null

        return when (cellInfo) {
            is CellInfoLte -> {
                val identity = cellInfo.cellIdentity
                val signal = cellInfo.cellSignalStrength
                CellTowerData(
                    radioTechnology = "LTE",
                    mcc = identity.mccString,
                    mnc = identity.mncString,
                    cellId = identity.ci,
                    lac = null,
                    tac = identity.tac,
                    pci = identity.pci,
                    signalStrength = signal.dbm,
                    level = signal.level,
                    rsrp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) signal.rsrp.takeIf { it != Int.MAX_VALUE } else null,
                    rsrq = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) signal.rsrq.takeIf { it != Int.MAX_VALUE } else null,
                    snr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) signal.rssnr.takeIf { it != Int.MAX_VALUE } else null,
                    ta = signal.timingAdvance.takeIf { it != Int.MAX_VALUE },
                    bands = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) identity.bands?.toList() else null,
                    bandwidth = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) identity.bandwidth else null,
                )
            }
            is CellInfoGsm -> {
                val identity = cellInfo.cellIdentity
                val signal = cellInfo.cellSignalStrength
                CellTowerData(
                    radioTechnology = "GSM",
                    mcc = identity.mccString,
                    mnc = identity.mncString,
                    cellId = identity.cid,
                    lac = identity.lac,
                    tac = null,
                    pci = null,
                    signalStrength = signal.dbm,
                    level = signal.level,
                    rsrp = null,
                    rsrq = null,
                    snr = null,
                    ta = null,
                    bands = null,
                    bandwidth = null,
                )
            }
            is CellInfoWcdma -> {
                val identity = cellInfo.cellIdentity
                val signal = cellInfo.cellSignalStrength
                CellTowerData(
                    radioTechnology = "UMTS",
                    mcc = identity.mccString,
                    mnc = identity.mncString,
                    cellId = identity.cid,
                    lac = identity.lac,
                    tac = null,
                    pci = identity.psc,
                    signalStrength = signal.dbm,
                    level = signal.level,
                    rsrp = null,
                    rsrq = null,
                    snr = null,
                    ta = null,
                    bands = null,
                    bandwidth = null,
                )
            }
            is CellInfoNr -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val identity = cellInfo.cellIdentity as android.telephony.CellIdentityNr
                    val signal = cellInfo.cellSignalStrength as android.telephony.CellSignalStrengthNr
                    CellTowerData(
                        radioTechnology = "NR",
                        mcc = identity.mccString,
                        mnc = identity.mncString,
                        cellId = identity.nci.toInt(),
                        lac = null,
                        tac = identity.tac,
                        pci = identity.pci,
                        signalStrength = signal.dbm,
                        level = signal.level,
                        rsrp = signal.ssRsrp.takeIf { it != Int.MAX_VALUE },
                        rsrq = signal.ssRsrq.takeIf { it != Int.MAX_VALUE },
                        snr = signal.ssSinr.takeIf { it != Int.MAX_VALUE },
                        ta = null,
                        bands = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) identity.bands?.toList() else null,
                        bandwidth = null,
                    )
                } else null
            }
            is CellInfoCdma -> {
                val identity = cellInfo.cellIdentity
                val signal = cellInfo.cellSignalStrength
                // CDMA towers broadcast their own coordinates
                val towerLat = identity.latitude.takeIf { it != Int.MAX_VALUE }
                val towerLng = identity.longitude.takeIf { it != Int.MAX_VALUE }
                CellTowerData(
                    radioTechnology = "CDMA",
                    mcc = null,
                    mnc = null,
                    cellId = identity.basestationId,
                    lac = null,
                    tac = null,
                    pci = null,
                    signalStrength = signal.dbm,
                    level = signal.level,
                    rsrp = null,
                    rsrq = null,
                    snr = null,
                    ta = null,
                    bands = null,
                    bandwidth = null,
                    towerLatitude = towerLat?.let { it / 14400.0 },  // quarter-seconds to degrees
                    towerLongitude = towerLng?.let { it / 14400.0 },
                )
            }
            else -> null
        }
    }

    private fun hasLocationPermission(): Boolean {
        return androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.ACCESS_FINE_LOCATION,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    fun getOperatorInfo(): OperatorInfo? {
        val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            ?: return null
        return runCatching {
            val operator = telephonyManager.networkOperator ?: return null
            OperatorInfo(
                mcc = if (operator.length >= 3) operator.substring(0, 3) else null,
                mnc = if (operator.length >= 5) operator.substring(3) else null,
                operatorName = telephonyManager.networkOperatorName,
                simOperator = telephonyManager.simOperator,
                simOperatorName = telephonyManager.simOperatorName,
                simCountryIso = telephonyManager.simCountryIso,
                networkCountryIso = telephonyManager.networkCountryIso,
            )
        }.getOrNull()
    }
}

data class CellTowerData(
    val radioTechnology: String,
    val mcc: String?,
    val mnc: String?,
    val cellId: Int?,
    val lac: Int?,
    val tac: Int?,
    val pci: Int?,
    val signalStrength: Int?,
    val level: Int?,
    val rsrp: Int?,
    val rsrq: Int?,
    val snr: Int?,
    val ta: Int?,
    val bands: List<Int>?,
    val bandwidth: Int?,
    val towerLatitude: Double? = null,
    val towerLongitude: Double? = null,
)

data class OperatorInfo(
    val mcc: String?,
    val mnc: String?,
    val operatorName: String?,
    val simOperator: String?,
    val simOperatorName: String?,
    val simCountryIso: String?,
    val networkCountryIso: String?,
)
