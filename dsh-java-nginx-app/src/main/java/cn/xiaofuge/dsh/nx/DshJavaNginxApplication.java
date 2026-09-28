package cn.xiaofuge.dsh.nx;

import cn.xiaofuge.dsh.nx.config.NginxProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(NginxProperties.class)
public class DshJavaNginxApplication {

    public static void main(String[] args) {
        SpringApplication.run(DshJavaNginxApplication.class, args);
    }
}
