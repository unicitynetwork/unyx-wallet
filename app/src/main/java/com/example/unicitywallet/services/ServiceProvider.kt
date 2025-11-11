package com.example.unicitywallet.services

import android.content.Context
import com.example.unicitywallet.utils.WalletConstants
import org.unicitylabs.sdk.StateTransitionClient
import org.unicitylabs.sdk.api.AggregatorClient
import org.unicitylabs.sdk.api.JsonRpcAggregatorClient
import org.unicitylabs.sdk.bft.RootTrustBase
import org.unicitylabs.sdk.serializer.UnicityObjectMapper
import org.unicitylabs.sdk.signing.SigningService
import com.example.unicitywallet.utils.HexUtils
import java.io.InputStream

object ServiceProvider {
    val aggregatorClient: AggregatorClient by lazy {
        JsonRpcAggregatorClient(WalletConstants.UNICITY_AGGREGATOR_URL)
    }

    val stateTransitionClient: StateTransitionClient by lazy {
        StateTransitionClient(aggregatorClient)
    }

    private var cachedTrustBase: RootTrustBase? = null
    private var applicationContext: Context? = null

    fun init(context: Context) {
        applicationContext = context.applicationContext
    }

    fun getRootTrustBase(): RootTrustBase{
        cachedTrustBase?.let { return it }

        applicationContext?.let { context ->
            try {
                val inputStream: InputStream = context.assets.open("trustbase-testnet.json")
                val json =inputStream.bufferedReader().use { it.readText() }
                val trustBase = UnicityObjectMapper.JSON.readValue(json, RootTrustBase::class.java)
                cachedTrustBase = trustBase
                return trustBase
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        try {
            val inputStream = javaClass.classLoader?.getResourceAsStream("trustbase-testnet.json")
            if (inputStream != null) {
                val json = inputStream.bufferedReader().use { it.readText() }
                val trustBase = UnicityObjectMapper.JSON.readValue(json, RootTrustBase::class.java)
                cachedTrustBase = trustBase
                return trustBase
            }
        } catch (e: Exception) {
            // Continue to fallback
            e.printStackTrace()
        }

        val testSigningService = SigningService(SigningService.generatePrivateKey())
        val testSigKeyHex = HexUtils.encodeHexString(testSigningService.publicKey)

        val testTrustBaseJson = """{
            "version": 1,
            "networkId": 0,
            "epoch": 1,
            "epochStartRound": 1,
            "rootNodes": [{
                "nodeId": "TEST_NODE",
                "sigKey": "0x${testSigKeyHex}",
                "stake": 1
            }],
            "quorumThreshold": 1,
            "stateHash": "",
            "changeRecordHash": "",
            "previousEntryHash": "",
            "signatures": {}
        }""".trimMargin()

        val testTrustBase = RootTrustBase.fromJson(testTrustBaseJson)
        cachedTrustBase = testTrustBase
        return testTrustBase
    }
}