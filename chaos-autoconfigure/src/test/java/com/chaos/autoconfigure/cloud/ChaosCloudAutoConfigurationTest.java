package com.chaos.autoconfigure.cloud;

import static org.assertj.core.api.Assertions.assertThat;

import com.chaos.core.resilience.ResilienceRegistry;
import com.chaos.http.client.ServiceErrorTranslator;
import com.chaos.http.client.ServiceHttpClients;
import com.chaos.http.client.feign.ChaosFeignErrorDecoder;
import com.chaos.http.client.feign.ChaosFeignResultDecoder;
import com.chaos.http.client.feign.ChaosResilientFeignClient;
import com.chaos.trace.feign.TraceFeignRequestInterceptor;
import feign.Client;
import feign.codec.Decoder;
import feign.codec.ErrorDecoder;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Cloud starter 自动装配测试。
 */
class ChaosCloudAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    JacksonAutoConfiguration.class,
                    HttpMessageConvertersAutoConfiguration.class,
                    ChaosCloudAutoConfiguration.class
            ));

    @Test
    void shouldRegisterFeignAdaptersAndHttpClientsByDefault() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(ServiceHttpClients.class);
            assertThat(context).hasSingleBean(TraceFeignRequestInterceptor.class);
            assertThat(context).hasSingleBean(ServiceErrorTranslator.class);
            assertThat(context).getBean(ErrorDecoder.class).isInstanceOf(ChaosFeignErrorDecoder.class);
            assertThat(context).getBean(Decoder.class).isInstanceOf(ChaosFeignResultDecoder.class);
        });
    }

    @Test
    void shouldSkipResultUnwrapDecoderWhenDisabled() {
        contextRunner.withPropertyValues("chaos.cloud.feign.unwrap-result=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(ChaosFeignResultDecoder.class);
                    assertThat(context).hasSingleBean(ChaosFeignErrorDecoder.class);
                });
    }

    @Test
    void shouldWrapFeignClientWithResilienceByDefaultAndAllowOptOut() {
        contextRunner
                .withBean(Client.class, () -> new Client.Default(null, null))
                .run(context -> {
                    assertThat(context).hasSingleBean(ResilienceRegistry.class);
                    assertThat(context.getBean(Client.class)).isInstanceOf(ChaosResilientFeignClient.class);
                });

        contextRunner
                .withPropertyValues("chaos.cloud.resilience.enabled=false")
                .withBean(Client.class, () -> new Client.Default(null, null))
                .run(context -> {
                    assertThat(context).doesNotHaveBean("chaosCloudResilienceRegistry");
                    assertThat(context.getBean(Client.class)).isNotInstanceOf(ChaosResilientFeignClient.class);
                });
    }
}
