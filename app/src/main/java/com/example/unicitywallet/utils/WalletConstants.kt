package com.example.unicitywallet.utils

object WalletConstants {
    const val UNICITY_AGGREGATOR_URL = "https://goggregator-test.unicity.network"

    object Chain {
        /**
         * Universal TokenType for the Unicity testnet chain
         * This is the standard token type used for all tokens on the testnet.
         * Each chain has its own unique token type identifier.
         */
        const val TESTNET_TOKEN_TYPE = "f8aa13834268d29355ff12183066f0cb902003629bbc5eb9ef0efbe397867509"

        // Future: Add mainnet and other chain token types here
        // const val MAINNET_TOKEN_TYPE = "..."
    }
    /**
     * Currently active token type for the Unicity network.
     * This determines which chain the wallet is operating on.
     *
     * To switch chains:
     * 1. Change this to point to the desired chain's token type (e.g., Chain.MAINNET_TOKEN_TYPE)
     * 2. Update UNICITY_AGGREGATOR_URL to the corresponding aggregator endpoint
     * 3. Clear app data or regenerate identity as addresses are chain-specific
     */
    const val UNICITY_TOKEN_TYPE = Chain.TESTNET_TOKEN_TYPE
}