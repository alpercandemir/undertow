package dev.undertow.cli;

import java.util.Map;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Scope;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import picocli.CommandLine;

/** Non-web application wiring; reviewed sources and policy never enter Spring configuration. */
@SpringBootConfiguration(proxyBeanMethods = false)
@EnableAutoConfiguration
public class UndertowApplication {
    public static void main(String[] args) {
        System.exit(execute(args));
    }

    public static int execute(String... args) {
        try (ConfigurableApplicationContext context = start()) {
            return context.getBean(CommandLine.class).execute(args);
        }
    }

    public static ConfigurableApplicationContext start() {
        var environment = new StandardEnvironment();
        // Boot normally reads ./application.properties and ./config. Those may be untrusted.
        Map<String, Object> bootstrapProperties =
                Map.of("spring.config.location", "classpath:/undertow.properties");
        var bootstrap = new MapPropertySource("undertowBootstrap", bootstrapProperties);
        environment.getPropertySources().addFirst(bootstrap);

        var application = new SpringApplication(UndertowApplication.class);
        application.setEnvironment(environment);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setBannerMode(Banner.Mode.OFF);
        application.setLogStartupInfo(false);
        application.setAddCommandLineProperties(false);
        application.setRegisterShutdownHook(false);
        return application.run();
    }

    @Bean
    ModelClientFactory modelClientFactory() {
        return new ModelClientFactory();
    }

    @Bean
    @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
    CommandLine commandLine(ModelClientFactory models) {
        // picocli mutates option fields. Every invocation owns a fresh command tree.
        return new CommandLine(new Undertow())
                .addSubcommand(new Undertow.Review(models))
                .addSubcommand(new Undertow.PrInfo())
                .addSubcommand(new Undertow.Publish())
                .addSubcommand(new Undertow.MarkStale())
                .addSubcommand(new SetupCommands.Doctor())
                .addSubcommand(new SetupCommands.Rules())
                .addSubcommand(new Undertow.Investigate())
                .addSubcommand(new dev.undertow.service.ServiceCommands.Serve())
                .addSubcommand(new dev.undertow.service.ServiceCommands.Worker())
                .addSubcommand(new dev.undertow.service.ServiceCommands.Publish());
    }
}
