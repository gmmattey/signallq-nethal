package com.nethal.lab

import android.app.Application
import com.nethal.core.catalog.DefaultDriverRegistry
import com.nethal.core.catalog.DriverRegistry
import com.nethal.core.catalog.ManualIdentificationRepository
import com.nethal.core.catalog.loadEmbeddedCatalogResource
import com.nethal.core.consent.ConsentRepository
import com.nethal.core.discovery.DefaultDiscoveryEngine
import com.nethal.core.discovery.DefaultSsdpDiscoverer
import com.nethal.core.discovery.DefaultUpnpIgdProbe
import com.nethal.core.discovery.DiscoveryEngine
import com.nethal.core.discovery.NetworkEnvironmentReader
import com.nethal.core.fingerprint.DefaultFingerprintEngine
import com.nethal.core.fingerprint.DefaultHttpFingerprintProbe
import com.nethal.core.fingerprint.FingerprintEngine
import com.nethal.core.protocol.http.DefaultHttpTransport
import com.nethal.core.protocol.http.HttpTransport
import com.nethal.core.protocol.http.HttpTransportConfig
import com.nethal.lab.data.catalog.ManualIdentificationDataStoreRepository
import com.nethal.lab.data.catalog.manualIdentificationDataStore
import com.nethal.lab.data.consent.ConsentDataStoreRepository
import com.nethal.lab.data.consent.consentDataStore
import com.nethal.lab.data.discovery.AndroidNetworkEnvironmentReader

class NetHalApplication : Application() {

    lateinit var consentRepository: ConsentRepository
        private set

    lateinit var networkEnvironmentReader: NetworkEnvironmentReader
        private set

    lateinit var discoveryEngine: DiscoveryEngine
        private set

    lateinit var driverRegistry: DriverRegistry
        private set

    lateinit var fingerprintEngine: FingerprintEngine
        private set

    lateinit var manualIdentificationRepository: ManualIdentificationRepository
        private set

    /**
     * Transporte HTTP compartilhado, sem nada fabricante-específico — config neutra (timeouts
     * conservadores de WebUI local, sem header/Content-Type/redirect particular de driver). Quem
     * precisa de comportamento específico de fabricante continua usando o transporte próprio do
     * driver (`DefaultTplinkHttpTransport`/`DefaultNokiaHttpTransport`); decisão por família é do
     * `DriverFamilyFactory`, não deste wiring. Sem consumidor nesta onda — só exposto, como pré-requisito
     * de onda futura (ver plano do Workstream 0).
     */
    lateinit var httpTransport: HttpTransport
        private set

    override fun onCreate() {
        super.onCreate()
        consentRepository = ConsentDataStoreRepository(consentDataStore)

        networkEnvironmentReader = AndroidNetworkEnvironmentReader(this)
        discoveryEngine = DefaultDiscoveryEngine(
            networkEnvironmentReader = networkEnvironmentReader,
            ssdpDiscoverer = DefaultSsdpDiscoverer(),
            upnpIgdProbe = DefaultUpnpIgdProbe(),
        )

        driverRegistry = DefaultDriverRegistry(embeddedManifestLoader = ::loadEmbeddedCatalogResource)
        fingerprintEngine = DefaultFingerprintEngine(
            httpFingerprintProbe = DefaultHttpFingerprintProbe(),
            driverRegistry = driverRegistry,
        )
        manualIdentificationRepository = ManualIdentificationDataStoreRepository(manualIdentificationDataStore)

        httpTransport = DefaultHttpTransport(
            HttpTransportConfig(
                connectTimeoutMillis = 10_000,
                getReadTimeoutMillis = 20_000,
                getAcceptHeader = "*/*",
                postAcceptHeader = "*/*",
                postContentType = "application/x-www-form-urlencoded",
            ),
        )
    }
}
