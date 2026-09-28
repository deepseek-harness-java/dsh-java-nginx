package cn.xiaofuge.dsh.nx.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(NginxProperties.class)
public class ApplicationConfiguration {
}
