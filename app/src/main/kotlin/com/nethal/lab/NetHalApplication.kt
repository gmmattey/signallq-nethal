package com.nethal.lab

import android.app.Application
import com.nethal.core.catalog.DefaultDriverRegistry
import com.nethal.core.catalog.DriverFamilyRegistry
import com.nethal.core.catalog.DriverRegistry
import com.nethal.core.catalog.ManualIdentificationRepository
import com.nethal.core.catalog.loadEmbeddedCatalogResource
import com.nethal.core.consent.ConsentRepository
import com.nethal.core.consent.ConsentScope
import com.nethal.core.designsystem.theme.ThemeModeRepository
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
import com.nethal.core.telemetry.HttpTelemetryCollector
import com.nethal.core.telemetry.TelemetryCollector
import com.nethal.core.telemetry.TelemetryDeviceIdRepository
import com.nethal.core.telemetry.TelemetryEndpointConfig
import com.nethal.feature.pairingauth.PairingAuthDependencies
import com.nethal.feature.pairingdiscovery.PairingDiscoveryDependencies
import com.nethal.lab.data.catalog.ManualIdentificationDataStoreRepository
import com.nethal.lab.data.catalog.manualIdentificationDataStore
import com.nethal.lab.data.consent.ConsentDataStoreRepository
import com.nethal.lab.data.consent.consentDataStore
import com.nethal.lab.data.onboarding.OnboardingCompletionDataStoreRepository
import com.nethal.lab.data.onboarding.onboardingDataStore
import com.nethal.lab.data.discovery.AndroidNetworkEnvironmentReader
import com.nethal.lab.data.telemetry.TelemetryDeviceIdDataStoreRepository
import com.nethal.lab.data.telemetry.telemetryDataStore
import com.nethal.lab.data.theme.ThemeModeDataStoreRepository
import com.nethal.lab.data.theme.themeDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class NetHalApplication : Application() {

    lateinit var consentRepository: ConsentRepository
        private set

    /**
     * Preferência de tema (claro/escuro/sistema), issue #132. Observada no composition root
     * (`MainActivity`) e escrita pelo seletor em Configurações (`SettingsViewModel`).
     */
    lateinit var themeModeRepository: ThemeModeRepository
        private set

    /**
     * Marcador "onboarding já concluído" (issue #113). Lido no composition root (`MainActivity` →
     * `NetHalNavHost`) para decidir o `startDestination`: primeira instalação abre o onboarding;
     * launches seguintes entram direto no pareamento. Concreto em `:app`, sem contrato em `core` —
     * único consumidor é a navegação (ver KDoc de `OnboardingCompletionDataStoreRepository`).
     */
    lateinit var onboardingCompletionRepository: OnboardingCompletionDataStoreRepository
        private set

    lateinit var networkEnvironmentReader: NetworkEnvironmentReader
        private set

    lateinit var discoveryEngine: DiscoveryEngine
        private set

    lateinit var driverRegistry: DriverRegistry
        private set

    lateinit var driverFamilyRegistry: DriverFamilyRegistry
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

    lateinit var telemetryDeviceIdRepository: TelemetryDeviceIdRepository
        private set

    /**
     * Telemetria do NetHAL Lab para o SignallQ Console (issue #97, Lane A — sessão/capability, ver
     * `core/telemetry`). `endpoint` fica vazio de propósito: as rotas `/ingest/nethal/...` ainda não
     * existem do lado do SignallQ (`linka-android#886` em aberto) — enquanto isso,
     * `HttpTelemetryCollector` é no-op mesmo com consentimento concedido. `consentProvider` lê um
     * snapshot síncrono de `ConsentScope.TELEMETRY_BETA`, mantido atualizado por uma coleta em
     * segundo plano do `ConsentRepository` real (`applicationScope`, escopo de vida do processo).
     * Sem call site ainda (nenhuma tela chama `sendDiagnosticSession`/`sendCapabilityResult`) — só
     * exposto, mesmo espírito de `httpTransport` acima.
     */
    lateinit var telemetryCollector: TelemetryCollector
        private set

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Dependências injetadas em `pairingDiscoveryGraph()` (`:feature:pairing-discovery`, ADR
     * 0002) — composição manual a partir dos mesmos engines/registries já montados acima, sem
     * duplicar construção.
     */
    lateinit var pairingDiscoveryDependencies: PairingDiscoveryDependencies
        private set

    /**
     * Dependências injetadas em `pairingAuthGraph()` (`:feature:pairing-auth`, issues #76-#79,
     * ADR 0002) — o `HttpTransport` de autenticação **não** entra aqui: é construído internamente
     * pelo próprio módulo (ver KDoc de `buildPairingAuthHttpTransport` em `PairingAuthGraph.kt`).
     */
    lateinit var pairingAuthDependencies: PairingAuthDependencies
        private set

    override fun onCreate() {
        super.onCreate()
        consentRepository = ConsentDataStoreRepository(consentDataStore)
        themeModeRepository = ThemeModeDataStoreRepository(themeDataStore)
        onboardingCompletionRepository = OnboardingCompletionDataStoreRepository(onboardingDataStore)

        val telemetryConsentGranted = MutableStateFlow(false)
        applicationScope.launch {
            consentRepository.observeState().collect { state ->
                telemetryConsentGranted.value = state.isGranted(ConsentScope.TELEMETRY_BETA)
            }
        }
        telemetryDeviceIdRepository = TelemetryDeviceIdDataStoreRepository(telemetryDataStore)
        telemetryCollector = HttpTelemetryCollector(
            endpoint = TelemetryEndpointConfig(), // placeholder até linka-android#886 fechar
            deviceIdRepository = telemetryDeviceIdRepository,
            consentProvider = { telemetryConsentGranted.value },
        )

        networkEnvironmentReader = AndroidNetworkEnvironmentReader(this)
        discoveryEngine = DefaultDiscoveryEngine(
            networkEnvironmentReader = networkEnvironmentReader,
            ssdpDiscoverer = DefaultSsdpDiscoverer(),
            upnpIgdProbe = DefaultUpnpIgdProbe(),
        )

        driverRegistry = DefaultDriverRegistry(embeddedManifestLoader = ::loadEmbeddedCatalogResource)
        driverFamilyRegistry = defaultDriverFamilyRegistry()
        fingerprintEngine = DefaultFingerprintEngine(
            httpFingerprintProbe = DefaultHttpFingerprintProbe(),
            driverRegistry = driverRegistry,
        )
        manualIdentificationRepository = ManualIdentificationDataStoreRepository(manualIdentificationDataStore)

        pairingDiscoveryDependencies = PairingDiscoveryDependencies(
            discoveryEngine = discoveryEngine,
            networkEnvironmentReader = networkEnvironmentReader,
            fingerprintEngine = fingerprintEngine,
            manualIdentificationRepository = manualIdentificationRepository,
            driverRegistry = driverRegistry,
        )

        pairingAuthDependencies = PairingAuthDependencies(
            driverRegistry = driverRegistry,
            driverFamilyRegistry = driverFamilyRegistry,
        )

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
