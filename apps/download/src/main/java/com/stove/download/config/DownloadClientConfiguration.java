package com.stove.download.config;

import com.stove.download.adapter.CatalogBuildHttpClient;
import com.stove.download.adapter.LicenseHttpClient;
import java.net.http.HttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
public class DownloadClientConfiguration {
    @Bean
    public LicenseHttpClient ownedProducts(DownloadClientProperties properties, RestClient.Builder builder) {
        return new LicenseHttpClient(client(properties.licenseBaseUrl(), properties, builder));
    }

    @Bean
    public CatalogBuildHttpClient publishedBuilds(DownloadClientProperties properties, RestClient.Builder builder) {
        return new CatalogBuildHttpClient(client(properties.catalogBaseUrl(), properties, builder), properties.catalogServiceKey());
    }

    private RestClient client(String baseUrl, DownloadClientProperties properties, RestClient.Builder builder) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(properties.readTimeout());
        return builder.clone().baseUrl(baseUrl).requestFactory(factory).build();
    }
}
