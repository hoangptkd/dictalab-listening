package com.dictalab.config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.time.Clock;
@Configuration(proxyBeanMethods=false)
public class PaymentTimeConfig {
    @Bean public Clock paymentClock() { return Clock.systemUTC(); }
}
