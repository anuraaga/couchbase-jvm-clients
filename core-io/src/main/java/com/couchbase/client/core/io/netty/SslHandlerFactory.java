/*
 * Copyright (c) 2018 Couchbase, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.couchbase.client.core.io.netty;

import com.couchbase.client.core.cnc.events.io.CustomTlsCiphersEnabledEvent;
import com.couchbase.client.core.endpoint.EndpointContext;
import com.couchbase.client.core.env.SecurityConfig;
import com.couchbase.client.core.deps.io.netty.buffer.ByteBufAllocator;
import com.couchbase.client.core.deps.io.netty.handler.ssl.OpenSsl;
import com.couchbase.client.core.deps.io.netty.handler.ssl.SslContextBuilder;
import com.couchbase.client.core.deps.io.netty.handler.ssl.SslHandler;
import com.couchbase.client.core.deps.io.netty.handler.ssl.SslProvider;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;
import java.security.cert.X509Certificate;
import java.util.List;

/**
 * This factory creates {@link SslHandler} based on a given configuration.
 *
 * @since 2.0.0
 */
public class SslHandlerFactory {

  /**
   * Checks once if OpenSSL is available.
   */
  private static final boolean OPENSSL_AVAILABLE = OpenSsl.isAvailable();

  public static SslHandler get(final ByteBufAllocator allocator, final SecurityConfig config,
                               final EndpointContext endpointContext) throws Exception {
    SslProvider provider =  OPENSSL_AVAILABLE && config.nativeTlsEnabled() ? SslProvider.OPENSSL : SslProvider.JDK;

    SslContextBuilder context = SslContextBuilder.forClient().sslProvider(provider);

    if (config.trustManagerFactory() != null) {
      context.trustManager(config.trustManagerFactory());
    } else if (config.trustCertificates() != null && !config.trustCertificates().isEmpty()) {
      context.trustManager(config.trustCertificates().toArray(new X509Certificate[0]));
    }

    List<String> ciphers = config.ciphers();
    if (ciphers != null  && !ciphers.isEmpty()) {
      context.ciphers(ciphers);
      endpointContext.environment().eventBus().publish(
        new CustomTlsCiphersEnabledEvent(ciphers, endpointContext)
      );
    }

    endpointContext.authenticator().applyTlsProperties(context);

    final SslHandler sslHandler = context.build().newHandler(
      allocator,
      endpointContext.remoteSocket().hostname(),
      endpointContext.remoteSocket().port()
    );

    SSLEngine sslEngine = sslHandler.engine();
    SSLParameters sslParameters = sslEngine.getSSLParameters();

    if (config.hostnameVerificationEnabled()) {
      sslParameters.setEndpointIdentificationAlgorithm("HTTPS");
    }

    sslEngine.setSSLParameters(sslParameters);

    return sslHandler;
  }

}
