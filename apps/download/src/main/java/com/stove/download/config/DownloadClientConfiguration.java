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
    public LicenseHttpClient ownedProducts(DownloadClientProperties properties) {
        return new LicenseHttpClient(client(properties.licenseBaseUrl(), properties));
    }

    @Bean
    public CatalogBuildHttpClient publishedBuilds(DownloadClientProperties properties) {
        return new CatalogBuildHttpClient(client(properties.catalogBaseUrl(), properties), properties.catalogServiceKey());
    }

    private RestClient client(String baseUrl, DownloadClientProperties properties) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(properties.readTimeout());
        return RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }
}
