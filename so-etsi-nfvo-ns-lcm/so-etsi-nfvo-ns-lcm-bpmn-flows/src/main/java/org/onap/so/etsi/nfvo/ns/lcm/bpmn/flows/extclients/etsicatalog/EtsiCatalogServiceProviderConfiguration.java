/*-
 * ============LICENSE_START=======================================================
 *  Copyright (C) 2020 Nordix Foundation.
 * ================================================================================
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 * ============LICENSE_END=========================================================
 */

package org.onap.so.etsi.nfvo.ns.lcm.bpmn.flows.extclients.etsicatalog;

import java.io.IOException;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.util.Iterator;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.NoopHostnameVerifier;
import org.apache.hc.client5.http.ssl.SSLConnectionSocketFactory;
import org.apache.hc.core5.ssl.SSLContextBuilder;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.onap.logging.filter.spring.SpringClientPayloadFilter;
import org.onap.so.configuration.BasicHttpHeadersProvider;
import org.onap.so.configuration.HttpClientConnectionConfiguration;
import org.onap.so.etsi.nfvo.ns.lcm.bpmn.flows.GsonProvider;
import org.onap.so.logging.jaxrs.filter.SOSpringClientFilter;
import org.onap.so.rest.service.HttpRestServiceProvider;
import org.onap.so.rest.service.HttpRestServiceProviderImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.GsonHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.client.RestTemplate;

/**
 * Configures the HttpRestServiceProvider to make REST calls to the ETSI Catalog Manager
 * 
 * @author gareth.roper@est.tech
 */

@Configuration
public class EtsiCatalogServiceProviderConfiguration {
    private static final Logger LOGGER = LoggerFactory.getLogger(EtsiCatalogServiceProviderConfiguration.class);

    public static final String ETSI_CATALOG_SERVICE_PROVIDER_BEAN = "etsiCatalogServiceProvider";

    public static final String ETSI_CATALOG_REST_TEMPLATE_BEAN = "etsiCatalogRestTemplate";

    private final HttpClientConnectionConfiguration clientConnectionConfiguration;

    @Value("${etsi-catalog-manager.http.client.ssl.trust-store:#{null}}")
    private Resource trustStore;
    @Value("${etsi-catalog-manager.http.client.ssl.trust-store-password:#{null}}")
    private String trustStorePassword;

    private final GsonProvider gsonProvider;

    @Autowired
    public EtsiCatalogServiceProviderConfiguration(
            final HttpClientConnectionConfiguration clientConnectionConfiguration, final GsonProvider gsonProvider) {
        this.clientConnectionConfiguration = clientConnectionConfiguration;
        this.gsonProvider = gsonProvider;
    }

    @Bean
    @Qualifier(ETSI_CATALOG_REST_TEMPLATE_BEAN)
    public RestTemplate etsiCatalogRestTemplate() {
        final RestTemplate restTemplate = new RestTemplate();
        restTemplate.getInterceptors().add(new SOSpringClientFilter());
        restTemplate.getInterceptors().add((new SpringClientPayloadFilter()));
        return restTemplate;
    }

    @Bean
    @Qualifier(ETSI_CATALOG_SERVICE_PROVIDER_BEAN)
    public HttpRestServiceProvider etsiCatalogHttpRestServiceProvider(
            @Qualifier(ETSI_CATALOG_REST_TEMPLATE_BEAN) final RestTemplate restTemplate) {
        setGsonMessageConverter(restTemplate);

        final HttpClientBuilder httpClientBuilder = getHttpClientBuilder();
        if (trustStore != null) {
            try {
                LOGGER.debug("Setting up HttpComponentsClientHttpRequestFactory with SSL Context");
                LOGGER.debug("Setting client trust-store: {}", trustStore.getURL());
                LOGGER.debug("Creating SSLConnectionSocketFactory with NoopHostnameVerifier ... ");
                final SSLContext sslContext = new SSLContextBuilder()
                        .loadTrustMaterial(trustStore.getURL(), trustStorePassword.toCharArray()).build();
                final SSLConnectionSocketFactory sslConnectionSocketFactory =
                        new SSLConnectionSocketFactory(sslContext, NoopHostnameVerifier.INSTANCE);

                httpClientBuilder.setConnectionManager(getConnectionManager(sslConnectionSocketFactory));
            } catch (final KeyManagementException | NoSuchAlgorithmException | KeyStoreException | CertificateException
                    | IOException exception) {
                LOGGER.error("Error reading truststore, TLS connection will fail.", exception);
            }

        } else {
            LOGGER.debug("Setting connection manager without SSL ConnectionSocketFactory ...");
            httpClientBuilder.setConnectionManager(getConnectionManager());
        }

        final HttpComponentsClientHttpRequestFactory factory =
                new HttpComponentsClientHttpRequestFactory(httpClientBuilder.build());
        restTemplate.setRequestFactory(new BufferingClientHttpRequestFactory(factory));

        return new HttpRestServiceProviderImpl(restTemplate, new BasicHttpHeadersProvider().getHttpHeaders());
    }

    private PoolingHttpClientConnectionManager getConnectionManager(
            final SSLConnectionSocketFactory sslConnectionSocketFactory) {
        return PoolingHttpClientConnectionManagerBuilder.create().setSSLSocketFactory(sslConnectionSocketFactory)
                .setMaxConnPerRoute(clientConnectionConfiguration.getMaxConnectionsPerRoute())
                .setMaxConnTotal(clientConnectionConfiguration.getMaxConnections())
                .setConnectionTimeToLive(
                        TimeValue.of(clientConnectionConfiguration.getTimeToLiveInMins(), TimeUnit.MINUTES))
                .build();
    }

    private PoolingHttpClientConnectionManager getConnectionManager() {
        return PoolingHttpClientConnectionManagerBuilder.create()
                .setMaxConnPerRoute(clientConnectionConfiguration.getMaxConnectionsPerRoute())
                .setMaxConnTotal(clientConnectionConfiguration.getMaxConnections())
                .setConnectionTimeToLive(
                        TimeValue.of(clientConnectionConfiguration.getTimeToLiveInMins(), TimeUnit.MINUTES))
                .build();
    }

    private HttpClientBuilder getHttpClientBuilder() {
        return HttpClientBuilder.create().setDefaultRequestConfig(getRequestConfig());
    }

    private RequestConfig getRequestConfig() {
        return RequestConfig.custom()
                .setResponseTimeout(
                        Timeout.ofMilliseconds(clientConnectionConfiguration.getSocketTimeOutInMiliSeconds()))
                .setConnectTimeout(
                        Timeout.ofMilliseconds(clientConnectionConfiguration.getConnectionTimeOutInMilliSeconds()))
                .build();
    }

    public void setGsonMessageConverter(final RestTemplate restTemplate) {
        final Iterator<HttpMessageConverter<?>> iterator = restTemplate.getMessageConverters().iterator();
        while (iterator.hasNext()) {
            if (iterator.next() instanceof MappingJackson2HttpMessageConverter) {
                iterator.remove();
            }
        }
        restTemplate.getMessageConverters().add(new GsonHttpMessageConverter(gsonProvider.getGson()));
    }



}
