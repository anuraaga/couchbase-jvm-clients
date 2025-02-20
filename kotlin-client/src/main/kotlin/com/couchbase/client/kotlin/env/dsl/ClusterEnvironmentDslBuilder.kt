/*
 * Copyright 2021 Couchbase, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.couchbase.client.kotlin.env.dsl

import com.couchbase.client.core.cnc.EventBus
import com.couchbase.client.core.cnc.Meter
import com.couchbase.client.core.cnc.RequestTracer
import com.couchbase.client.core.encryption.CryptoManager
import com.couchbase.client.core.env.CoreEnvironment
import com.couchbase.client.core.env.CoreEnvironment.DEFAULT_MAX_NUM_REQUESTS_IN_RETRY
import com.couchbase.client.core.env.NetworkResolution
import com.couchbase.client.core.retry.RetryStrategy
import com.couchbase.client.core.service.ServiceType
import com.couchbase.client.kotlin.annotations.UncommittedApi
import com.couchbase.client.kotlin.annotations.VolatileApi
import com.couchbase.client.kotlin.codec.JsonSerializer
import com.couchbase.client.kotlin.codec.RawJsonTranscoder
import com.couchbase.client.kotlin.codec.Transcoder
import com.couchbase.client.kotlin.env.ClusterEnvironment
import reactor.core.scheduler.Scheduler
import java.nio.file.Paths
import java.time.Duration
import kotlin.properties.Delegates.observable

/**
 * Starts a cluster environment configuration DSL block.
 * Returns the [ClusterEnvironment.Builder] configured by the block.
 *
 * @sample configureTls
 * @sample configureManyThings
 */
public fun clusterEnvironment(
    initializer: ClusterEnvironmentDslBuilder.() -> Unit
): ClusterEnvironment.Builder {
    val builder = ClusterEnvironmentDslBuilder()
    builder.initializer()
    return builder.toCore()
}

internal fun configureTls() {
    clusterEnvironment {
        security {
            enableTls = true
            trust = TrustSource.trustStore(
                Paths.get("/path/to/truststore.jks"),
                "password"
            )
        }
    }
}

internal fun configureManyThings() {
    clusterEnvironment {
        transcoder = RawJsonTranscoder

        ioEnvironment {
            enableNativeIo = false
        }

        io {
            enableDnsSrv = false
            networkResolution = NetworkResolution.EXTERNAL
            tcpKeepAliveTime = Duration.ofSeconds(45)

            captureTraffic(ServiceType.KV, ServiceType.QUERY)

            // specify defaults before customizing individual breakers
            allCircuitBreakers {
                enabled = true
                volumeThreshold = 30
                errorThresholdPercentage = 20
                rollingWindow = Duration.ofSeconds(30)
            }

            kvCircuitBreaker {
                enabled = false
            }

            queryCircuitBreaker {
                rollingWindow = Duration.ofSeconds(10)
            }
        }

        timeout {
            kvTimeout = Duration.ofSeconds(3)
            kvDurableTimeout = Duration.ofSeconds(20)
            connectTimeout = Duration.ofSeconds(15)
        }

        orphanReporter {
            emitInterval = Duration.ofSeconds(20)
        }
    }
}

@DslMarker
internal annotation class ClusterEnvironmentDslMarker

/**
 * DSL counterpart to [ClusterEnvironment.Builder].
 */
@ClusterEnvironmentDslMarker
public class ClusterEnvironmentDslBuilder {
    private val wrapped = ClusterEnvironment.builder()

    /**
     * @see ClusterEnvironment.Builder.jsonSerializer
     */
    public var jsonSerializer: JsonSerializer?
            by observable(null) { _, _, it -> wrapped.jsonSerializer(it) }

    /**
     * @see ClusterEnvironment.Builder.transcoder
     */
    public var transcoder: Transcoder?
            by observable(null) { _, _, it -> wrapped.transcoder(it) }

    /**
     * @see ClusterEnvironment.Builder.cryptoManager
     */
    public var cryptoManager: CryptoManager?
            by observable(null, { _, _, it -> wrapped.cryptoManager(it) })

    private var ioEnvironmentDslBuilder = IoEnvironmentDslBuilder(wrapped.ioEnvironment())
    public fun ioEnvironment(initializer: IoEnvironmentDslBuilder.() -> Unit) {
        ioEnvironmentDslBuilder.initializer()
    }

    private var ioConfigBuilder = IoConfigDslBuilder(wrapped.ioConfig())
    public fun io(initializer: IoConfigDslBuilder.() -> Unit) {
        ioConfigBuilder.initializer()
    }

    private var securityConfigDslBuilder = SecurityConfigDslBuilder(wrapped.securityConfig())
    public fun security(initializer: SecurityConfigDslBuilder.() -> Unit) {
        securityConfigDslBuilder.initializer()
    }

    private var compressionConfigDslBuilder = CompressionConfigDslBuilder(wrapped.compressionConfig())
    public fun compression(initializer: CompressionConfigDslBuilder.() -> Unit) {
        compressionConfigDslBuilder.initializer()
    }

    private var timeoutConfigDslBuilder = TimeoutConfigDslBuilder(wrapped.timeoutConfig())
    public fun timeout(initializer: TimeoutConfigDslBuilder.() -> Unit) {
        timeoutConfigDslBuilder.initializer()
    }

    private var loggerConfigDslBuilder = LoggerConfigDslBuilder(wrapped.loggerConfig())
    public fun logger(initializer: LoggerConfigDslBuilder.() -> Unit) {
        loggerConfigDslBuilder.initializer()
    }

    private var orphanReporterConfigDslBuilder = OrphanReporterConfigDslBuilder(wrapped.orphanReporterConfig())
    public fun orphanReporter(initializer: OrphanReporterConfigDslBuilder.() -> Unit) {
        orphanReporterConfigDslBuilder.initializer()
    }

    private var thresholdRequestTracerConfigDslBuilder =
        ThresholdRequestTracerConfigDslBuilder(wrapped.thresholdRequestTracerConfig())

    public fun thresholdRequestTracer(initializer: ThresholdRequestTracerConfigDslBuilder.() -> Unit) {
        thresholdRequestTracerConfigDslBuilder.initializer()
    }

    private var aggregatingMeterConfigDslBuilder = AggregatingMeterConfigDslBuilder(wrapped.aggregatingMeterConfig())
    public fun aggregatingMeter(initializer: AggregatingMeterConfigDslBuilder.() -> Unit) {
        aggregatingMeterConfigDslBuilder.initializer()
    }

    /**
     * @see CoreEnvironment.Builder.eventBus
     */
    @UncommittedApi
    public var eventBus: EventBus?
            by observable(null) { _, _, it -> wrapped.eventBus(it) }

    /**
     * @see CoreEnvironment.Builder.scheduler
     */
    @UncommittedApi
    public var scheduler: Scheduler?
            by observable(null) { _, _, it -> wrapped.scheduler(it) }

    /**
     * @see CoreEnvironment.Builder.requestTracer
     */
    @VolatileApi
    public var requestTracer: RequestTracer?
            by observable(null) { _, _, it -> wrapped.requestTracer(it) }

    /**
     * @see CoreEnvironment.Builder.meter
     */
    @VolatileApi
    public var meter: Meter?
            by observable(null) { _, _, it -> wrapped.meter(it) }

    /**
     * @see CoreEnvironment.Builder.retryStrategy
     */
    public var retryStrategy: RetryStrategy?
            by observable(null) { _, _, it -> wrapped.retryStrategy(it) }

    /**
     * @see CoreEnvironment.Builder.maxNumRequestsInRetry
     */
    public var maxNumRequestsInRetry: Long
            by observable(DEFAULT_MAX_NUM_REQUESTS_IN_RETRY) { _, _, it -> wrapped.maxNumRequestsInRetry(it) }

    internal fun toCore() = wrapped
}
