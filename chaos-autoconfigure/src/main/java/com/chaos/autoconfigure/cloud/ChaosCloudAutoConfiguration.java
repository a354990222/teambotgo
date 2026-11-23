package com.chaos.autoconfigure.cloud;

import com.chaos.core.exception.BizException;
import com.chaos.core.metrics.ChaosMetrics;
import com.chaos.core.resilience.ResilienceRegistry;
import com.chaos.http.client.ServiceErrorTranslator;
import com.chaos.http.client.ServiceHttpClients;
import com.chaos.http.client.feign.ChaosFeignErrorDecoder;
import com.chaos.http.client.feign.ChaosFeignResultDecoder;
import com.chaos.http.client.feign.ChaosResilientFeignClient;
import com.chaos.http.client.feign.RemoteServiceErrorCode;
import com.chaos.trace.feign.TraceFeignRequestInterceptor;
import com.fasterxml.jackson.databind.ObjectMapper;
import feign.Client;
import feign.RequestInterceptor;
import feign.codec.Decoder;
import feign.codec.ErrorDecoder;
import java.util.Map;
import org.springframework.beans.factory.ObjectFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.http.HttpMessageConverters;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.cloud.openfeign.support.ResponseEntityDecoder;
import org.springframework.cloud.openfeign.support.SpringDecoder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Cloud starter 自动装配。
 *
 * <p>不在自动装配类上声明无参 {@code @EnableFeignClients}（该注解会错误扫描自动装配包路径），
 * 业务应用应在自身启动类显式标注 {@code @EnableFeignClients}。</p>
 */
@AutoConfiguration(
        afterName = "org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration",
        beforeName = "org.springframework.cloud.openfeign.FeignAutoConfiguration")
@ConditionalOnClass(name = "com.chaos.http.client.ServiceHttpClients")
@EnableConfigurationProperties(ChaosCloudProperties.class)
public class ChaosCloudAutoConfiguration {

    /**
     * 服务间受限 HTTP 客户端与弹性策略装配。
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass({ServiceHttpClients.class, RestTemplateBuilder.class})
    static class HttpClientConfiguration {

        /**
         * 注册服务间调用弹性策略注册中心（供 OpenFeign 与 {@link ServiceHttpClients} 共用）。
         */
        @Bean(name = "chaosCloudResilienceRegistry")
        @ConditionalOnProperty(prefix = "chaos.cloud.resilience", name = "enabled", havingValue = "true", matchIfMissing = true)
        @ConditionalOnMissingBean(name = "chaosCloudResilienceRegistry")
        public ResilienceRegistry chaosCloudResilienceRegistry(
                ChaosCloudProperties properties,
                ObjectProvider<ChaosMetrics> metricsProvider) {
            return new ResilienceRegistry(
                    "feign",
                    properties.getResilience().toPolicy(),
                    Map.of(),
                    metricsProvider.getIfAvailable());
        }

        /**
         * 注册带超时、禁止重定向与弹性保护约定的 HTTP 客户端工厂。
         */
        @Bean
        @ConditionalOnMissingBean
        public ServiceHttpClients serviceHttpClients(
                ObjectProvider<RestTemplateBuilder> builderProvider,
                @org.springframework.beans.factory.annotation.Qualifier("chaosCloudResilienceRegistry")
                ObjectProvider<ResilienceRegistry> resilienceRegistryProvider) {
            return new ServiceHttpClients(
                    builderProvider.getIfAvailable(RestTemplateBuilder::new),
                    resilienceRegistryProvider.getIfAvailable());
        }
    }

    /**
     * OpenFeign Trace 请求头透传装配。
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass({RequestInterceptor.class, TraceFeignRequestInterceptor.class})
    static class FeignTraceConfiguration {

        /**
         * 注册 OpenFeign 出站 trace 透传拦截器。
         */
        @Bean
        @ConditionalOnMissingBean
        public TraceFeignRequestInterceptor traceFeignRequestInterceptor() {
            return new TraceFeignRequestInterceptor();
        }
    }

    /**
     * OpenFeign 统一响应解包与错误码翻译装配。
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass({
            Decoder.class,
            ErrorDecoder.class,
            SpringDecoder.class,
            ResponseEntityDecoder.class,
            HttpMessageConverters.class,
            ChaosFeignResultDecoder.class,
            ObjectMapper.class
    })
    static class FeignCodecConfiguration {

        /**
         * 注册默认下游错误码翻译器，将其转换为 {@link BizException}。
         */
        @Bean
        @ConditionalOnMissingBean
        public ServiceErrorTranslator chaosServiceErrorTranslator() {
            return (code, message) -> new BizException(new RemoteServiceErrorCode(code, message), message);
        }

        /**
         * 注册 OpenFeign 错误响应解码器。
         */
        @Bean
        @ConditionalOnMissingBean
        public ErrorDecoder chaosFeignErrorDecoder(
                ObjectProvider<ObjectMapper> objectMapperProvider,
                ServiceErrorTranslator errorTranslator) {
            return new ChaosFeignErrorDecoder(objectMapperProvider.getIfAvailable(ObjectMapper::new), errorTranslator);
        }

        /**
         * 注册 OpenFeign Result 信封自动拆包解码器。
         */
        @Bean
        @ConditionalOnProperty(prefix = "chaos.cloud.feign", name = "unwrap-result", havingValue = "true", matchIfMissing = true)
        @ConditionalOnMissingBean(Decoder.class)
        public Decoder chaosFeignResultDecoder(
                ObjectFactory<HttpMessageConverters> messageConverters,
                ObjectProvider<ObjectMapper> objectMapperProvider,
                ServiceErrorTranslator errorTranslator) {
            Decoder springDecoder = new ResponseEntityDecoder(new SpringDecoder(messageConverters));
            return new ChaosFeignResultDecoder(
                    springDecoder,
                    objectMapperProvider.getIfAvailable(ObjectMapper::new),
                    errorTranslator);
        }
    }

    /**
     * OpenFeign 客户端熔断与舱壁包装配置。
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass({Client.class, ChaosResilientFeignClient.class, ResilienceRegistry.class})
    @ConditionalOnProperty(prefix = "chaos.cloud.resilience", name = "enabled", havingValue = "true", matchIfMissing = true)
    static class ResilientFeignConfiguration {

        @Bean(name = "chaosResilientFeignClientPostProcessor")
        @ConditionalOnMissingBean(name = "chaosResilientFeignClientPostProcessor")
        static BeanPostProcessor chaosResilientFeignClientPostProcessor(
                @org.springframework.beans.factory.annotation.Qualifier("chaosCloudResilienceRegistry")
                ObjectProvider<ResilienceRegistry> registryProvider) {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if (bean instanceof Client client && !(bean instanceof ChaosResilientFeignClient)) {
                        ResilienceRegistry registry = registryProvider.getIfAvailable();
                        if (registry != null) {
                            return new ChaosResilientFeignClient(client, registry);
                        }
                    }
                    return bean;
                }
            };
        }
    }
}
