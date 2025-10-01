package com.example.unicitywallet.services

import android.content.Context
import com.example.unicitywallet.utils.WalletConstants
import org.unicitylabs.sdk.StateTransitionClient
import org.unicitylabs.sdk.api.AggregatorClient
import org.unicitylabs.sdk.bft.RootTrustBase
import org.unicitylabs.sdk.serializer.UnicityObjectMapper
import org.unicitylabs.sdk.signing.SigningService
import java.io.InputStream

object ServiceProvider {
    val aggregatorClient: AggregatorClient by lazy {
        AggregatorClient(WalletConstants.UNICITY_AGGREGATOR_URL)
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

        val testSigningService = SigningService(SigningService.generatePrivateKey())
        val testTrustBase = RootTrustBase(
            0,
            0,
            0,
            0,
            setOf(
                RootTrustBase.NodeInfo(
                    "NODE",
                    testSigningService.publicKey,
                    1
                )
            ),
            1,
            ByteArray(0),
            ByteArray(0),
            null,
            emptyMap()
        )

        cachedTrustBase = testTrustBase
        return testTrustBase
    }
}